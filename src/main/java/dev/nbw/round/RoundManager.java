package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.arena.ArenaChunkLoader;
import dev.nbw.arena.ArenaPool;
import dev.nbw.arena.BuiltinArenaJson;
import dev.nbw.arena.MapResetService;
import dev.nbw.arena.SeaTerrain;
import dev.nbw.loot.LootService;
import dev.nbw.survival.SurvivalService;
import dev.nbw.combat.WeaponService;
import dev.nbw.construct.BuildService;
import dev.nbw.rescue.RescueService;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.cfg.ConfigService;
import dev.nbw.domain.SpawnGeometry;
import dev.nbw.domain.ZoneModel;
import dev.nbw.domain.arena.SeaAudit;
import dev.nbw.domain.round.Phase;
import dev.nbw.domain.round.PlayerSession;
import dev.nbw.domain.round.RoundConfig;
import dev.nbw.domain.round.RoundEvent;
import dev.nbw.domain.round.RoundMachine;
import dev.nbw.domain.round.RoundSnapshot;
import dev.nbw.raft.RaftCoreService;
import dev.nbw.raft.RaftMotionService;
import dev.nbw.raft.RaftSinking;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.raft.RemoteService;
import dev.nbw.raft.WindService;
import dev.nbw.domain.round.Transitions;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 回合骨架的服务端适配层（04 §1：domain 出阶段，这里出副作用）。
 *
 * 三条纪律：
 * 1. 玩法判定只在 domain，本类只搬运事实与执行表现，因此规则可以纯单测覆盖；
 * 2. 玩家集合一律**多来源采集 + UUID 去重**（05 C23：真实玩家在 world.getPlayers()、Carpet 假玩家在实体分区，
 *    PlayerManager 只保证真实玩家），只取一个来源会出现"假人测试全绿、线上漏人"；
 * 3. 阶段是唯一改权限的入口（04 §2 不变式 4），PVP / 建造 / 物品栏由 {@link Phase} 决定；
 *    任何表现层异常都不得拖垮对局（04 §6 降级要求）。
 */
public final class RoundManager {

	private static RoundMachine machine = new RoundMachine(RoundConfig.standard());
	private static MinecraftServer server;
	private static ConfigModels.Arena arena;
	private static ConfigModels.Format format;
	private static ZoneModel zone;   // 圈层：由 arena 环带 + format 阶段合成，PLAY 里由 ZoneService 应用
	private static ConfigModels.Lobby lobby;
	private static String arenaId = "default_sea";
	private static String formatId = "standard_12min";

	/** READY 时算好的每队出生锚点（下标 = teamIndex）。 */
	private static final List<Anchor> anchors = new ArrayList<>();
	/** 上一局参战者 UUID：RETURN 阶段要靠他们做清理与残留复检（那时 domain 会话已经清空）。 */
	private static final Set<UUID> lastParticipants = new LinkedHashSet<>();
	/**
	 * 生命周期事件的落位延后到下一 tick：1.21.1 的 fabric-networking-api-v1 **没有 AFTER_JOIN**（实测），
	 * 而原版在 JOIN/AFTER_RESPAWN 之后还会按存档或世界出生点落位，直接在我们的事件里传送会被它盖掉。
	 */
	private static final Set<UUID> pendingPlacement = new LinkedHashSet<>();
	private static int placementStallTicks;

	/** 报名期席位清扫的节拍计数（每秒一次就够，不必每 tick 点一遍在场列表）。 */
	private static int lobbySweepTicks;

	/** 出生锚点：方块坐标 + 朝向圈心的 yaw。 */
	public record Anchor(int x, int y, int z, float yaw) {
	}

	private RoundManager() {
	}

	// ---------------------------------------------------------------- 事件注册

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			// 大厅/海域配置要在开服时就位，否则玩家连进服都无处安置（实测踩过：lobby==null 时 placeInLobby 直接返回）
			String err = loadConfigs(null, null);
			if (err != null) {
				NBW.LOGGER.warn("[round] 开服配置载入未完成（玩家会留在原地，/cww start 会再试一次）：{}", err);
			}
			// 恢复必须在配置就位之后：快照里的队伍数要与 format 规格对得上，
			// 对不上时 restore() 会抛，那正是我们想要的（宁可起不来，也不要按错误前提跑完整局）
			NBW.LOGGER.info("[state] 重启恢复：{}", restoreFromDisk());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> server = null);
		ServerTickEvents.END_SERVER_TICK.register(RoundManager::onEndTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> onJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
			ServerPlayerEntity player = handler.getPlayer();
			NBW.LOGGER.info("[round] DISCONNECT {} (uuid={}, phase={}, seat={})",
					player.getNameForScoreboard(), player.getUuid(), machine.phase(),
					machine.session(player.getUuid()) != null);
			machine.onDisconnect(player.getUuid());
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> onRespawn(newPlayer));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayerEntity player) {
				PlayerSession s = machine.session(player.getUuid());
				NBW.LOGGER.info("[round] DEATH {} (uuid={}, phase={}, session={}, team={}, eliminated={})",
						player.getNameForScoreboard(), player.getUuid(), machine.phase(), s != null,
						s == null ? "-" : s.teamIndex(), s != null && s.isEliminated());
				machine.onDeath(player.getUuid());
			}
		});
		// 05 C18：死亡/伤害一律用 ServerLivingEntityEvents，不用已废弃的 ServerPlayerEvents.ALLOW_DEATH
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
				allowPlayerVsPlayer(entity, source == null ? null : source.getAttacker()));
		// 必须排在 PVP 闸门之后：Allow 事件一旦有处理器返回 false 就短路，
		// 于是"被闸门挡下的互殴"不会走到这里，不会误伤正在引导修复的玩家。
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (amount > 0f && entity instanceof ServerPlayerEntity victim) {
				RaftCoreService.notifyDamaged(victim.getUuid());
			}
			return true;
		});
		NBW.LOGGER.info("[round] registered: {} transition edges, spec={}x{}",
				Transitions.edgeCount(), machine.config().teamCount(), machine.config().teamSize());
	}

	/** 大厅与非 PLAY 阶段禁止玩家互殴（04 §3 阶段内权限集中）。 */
	private static boolean allowPlayerVsPlayer(Entity target, Entity attacker) {
		if (!(target instanceof ServerPlayerEntity) || !(attacker instanceof ServerPlayerEntity)) {
			return true;
		}
		if (machine.phase().allowsPvp()) {
			return true;
		}
		NBW.LOGGER.info("[round] pvp blocked in phase {}: {} -> {}", machine.phase(),
				attacker.getNameForScoreboard(), target.getNameForScoreboard());
		return false;
	}

	// ---------------------------------------------------------------- tick 与副作用

	private static void onEndTick(MinecraftServer srv) {
		machine.tick();
		List<RoundMachine.Transition> effects = machine.drainPendingEffects();
		for (RoundMachine.Transition t : effects) {
			try {
				onPhaseEnter(t);
			} catch (RuntimeException e) {
				NBW.LOGGER.error("[round] phase side effect failed for match={} -> {}", t.matchIndex(), t.to(), e);
			}
		}
		if (!effects.isEmpty()) {
			persist("阶段转移");   // 03 §6.1：每次转移落盘
		}
		drainPendingPlacements();
		// 预热队列跨阶段推进（ENROLL 就开始排队），所以不能塞进下面那个 PLAY-only 块里
		ArenaChunkLoader.tick(srv.getOverworld());
		MapResetService.onEndTick();   // 模板复制：READY 期间逐 tick 推进，不能只放在 PLAY 块里
		sweepLobbySeats();   // 报名期的幽灵席位清扫，同理不能塞进 PLAY-only 块里
		if (machine.phase() == Phase.PLAY) {
			sweepDeaths();
			ZoneService.onEndTick(srv);   // 黑海圈层：收缩预告 + 圈外每秒伤害
			LootService.onEndTick(srv);   // 海面物资：按 respawnIntervalSec 定时补箱
			SurvivalService.onEndTick(srv);   // 营养/水分：每秒衰减 + debuff + HUD
			RaftSinking.onEndTick(srv);
			RaftCoreService.onEndTick(srv);
			WindService.apply(srv);   // 帆：登记风力目标（被动等风，方向跟随前进）
			RemoteService.onEndTick(srv);   // 遥控器：登记马达目标 + 施转向角冲量
			RescueService.onEndTick(srv);   // 扒边：只挪玩家位置，筏的目标仍由下一行合成
			RaftMotionService.onEndTick(srv);   // 各目标合成之后，本 tick 只施一发控制冲量
		}
		// 恢复出来的这一局必须能晚点认领筏子：重启那一刻 Sable 还没把 sub-level 载进容器，
		// 一次性 rebindFromWorld 实测就是"重认领 0 艘筏"，之后这一局再也看不见自己的筏。
		// rebind 是幂等的（补 UUID + 绑沉筏 + 补票），所以只在还有队没绑上时按节拍重试。
		if (machine.phase().matchActive() && machine.tickInPhase() % 20 == 0 && RaftSpawner.needsRebind()) {
			NBW.LOGGER.info("[state] 延后重试认领：{}", RaftSpawner.rebindFromWorld(srv));
		}
		broadcastCountdown();
	}

	/**
	 * 死亡与在场的 tick 兜底。只用 PlayerManager 列表与各世界 getPlayers()（都是现成列表），
	 * 绝不在 tick 里做全图包围盒扫描（04 §12 性能预算）。
	 *
	 * 为什么需要：实测 Carpet 假玩家既不触发 AFTER_DEATH 也不触发 DISCONNECT（vanilla 那边有
	 * "lost connection: Killed"，我们的事件却是静的），真实玩家两个事件都正常。事件仍是主路径
	 * （05 C18），这里补的是"事件漏报导致席位一直占着/淘汰不生效"这一类静默故障。
	 */
	private static void sweepDeaths() {
		if (server == null) {
			return;
		}
		Set<UUID> present = presentIds();
		for (PlayerSession s : machine.sessions().values()) {
			if (s.teamIndex() < 0 || s.isEliminated()) {
				continue;
			}
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(s.id());
			if (p != null && (p.isRemoved() || !p.isAlive())) {
				NBW.LOGGER.info("[round] sweep: {} 实体在场但不存活，按淘汰处理", s.name());
				machine.onDeath(s.id());
				continue;
			}
			if (p == null && !present.contains(s.id()) && !s.isDisconnected()) {
				// 没抓到 DISCONNECT：按掉线开席位倒计时，避免"人早没了还占着活席"
				NBW.LOGGER.warn("[round] sweep: {} 不在场且未收到 DISCONNECT，按掉线开席位倒计时", s.name());
				machine.onDisconnect(s.id());
			}
		}
	}

	/**
	 * 在场玩家 UUID。<b>只用现成列表</b>（PlayerManager + 各世界 getPlayers()），
	 * 绝不做全图扫描（04 §12 性能预算）。两处兜底共用这一份口径，
	 * 免得"在场"在两个地方有两种定义。
	 */
	private static Set<UUID> presentIds() {
		Set<UUID> present = new HashSet<>();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			present.add(p.getUuid());
		}
		for (ServerWorld world : server.getWorlds()) {
			for (ServerPlayerEntity p : world.getPlayers()) {
				present.add(p.getUuid());
			}
		}
		return present;
	}

	/**
	 * 报名期的幽灵席位清扫（每秒一次，只在局没开/已收场时跑）。
	 *
	 * <p>存在理由见 {@link RoundMachine#releaseAbsent}：Carpet 假玩家被 kill 时不触发 DISCONNECT，
	 * 而 PLAY 才有在场兜底，于是 dev 循环里上一局的假玩家一直占着队席，
	 * 下一局 {@code cww force} 被"队伍已满"拒掉，现场看着像"分队被赛制变更改了"。
	 */
	private static void sweepLobbySeats() {
		if (server == null || machine.phase().matchActive()) {
			lobbySweepTicks = 0;
			return;
		}
		if (++lobbySweepTicks < 20) {
			return;   // 每秒一次就够：这不是玩法判定，是漏报兜底
		}
		lobbySweepTicks = 0;
		List<String> released = machine.releaseAbsent(presentIds());
		if (!released.isEmpty()) {
			NBW.LOGGER.warn("[round] 报名期放掉 {} 个不在场的席位（事件漏报兜底）：{}",
					released.size(), String.join("、", released));
		}
	}

	/** 延后落位的排队项：等实体真正进了世界再传送，才不会被原版的出生点落位覆盖。 */
	private static void drainPendingPlacements() {
		if (pendingPlacement.isEmpty()) {
			return;
		}
		List<ServerPlayerEntity> players = collect(pendingPlacement);
		if (players.isEmpty() && ++placementStallTicks > 40) {
			NBW.LOGGER.warn("[round] 丢弃 {} 个等不到实体的落位请求（玩家可能已离线）", pendingPlacement.size());
			pendingPlacement.clear();
			placementStallTicks = 0;
			return;
		}
		for (ServerPlayerEntity p : players) {
			try {
				placeByPhase(p);
			} catch (RuntimeException e) {
				NBW.LOGGER.error("[round] placement failed for {}", p.getNameForScoreboard(), e);
			}
			pendingPlacement.remove(p.getUuid());
			placementStallTicks = 0;
		}
	}

	private static void onPhaseEnter(RoundMachine.Transition t) {
		NBW.LOGGER.info("[AUDIT] match={} {} --{}--> {} @tick{}", t.matchIndex(), t.from(), t.event(), t.to(), t.totalTick());
		switch (t.to()) {
			case ENROLL -> {
				anchors.clear();
				lastParticipants.clear();
				announce("§b第 " + t.matchIndex() + " 局开始分队（" + seconds(machine.config().enrollTicks())
						+ "s），/cww join [队伍号] 自主选队");
			}
			case READY -> {
				buildAnchors();
				// 重启恢复 / 中途换图都没走过 start()，这里补一次登记（begin 幂等，不会重复下票）
				ArenaChunkLoader.begin(server.getOverworld(), arenaId, arena);
				warnIfSeaMissing();
				// 模板复制式重置：把模板区复制回贴合区（逐 tick 推进，READY 的 10s 足够小结构复完）
				MapResetService.begin(server.getOverworld(), arena);
				// 先有筏再传人：玩家应当落到自己那艘筏上，而不是落到水裡等筏子凭空出现
				String gen = RaftSpawner.generateForTeams(server);
				if (gen.contains("失败")) {
					NBW.LOGGER.error("[round] READY 发筏有问题：{}", gen);
				}
				RemoteService.distributeRemotes(server);   // 每筏发一个遥控器（问题③）
				int moved = teleportParticipants();
				announce("§e进入对局（" + moved + " 人已传送），" + seconds(machine.config().readyTicks()) + " 秒后开局");
			}
			case PLAY -> {
				setParticipantsGameMode(GameMode.SURVIVAL);
				MetricsService.begin(server);   // 埋点：局时长/首战/生产参与率的起点
				ZoneService.begin(server);   // 黑海圈层：抓半径/伤害表与圈心
				LootService.begin(server);   // 海面物资：按 loot.prefill 撒补给箱 + 定补箱间隔
				SurvivalService.begin(server);   // 生存：参赛者满营养/满水分
				announce("§c对局开始！最后存活队获胜");
			}
			case SETTLE -> {
				MetricsService.finish(server);   // 埋点：结算本局（局时长/首战/生产参与率）
				RoundMachine.Result r = machine.lastResult();
				if (r != null) {
					announce("§a本局结束：" + (r.isDraw() ? "平局（未分出唯一存活队）" : r.winnerName() + " 获胜")
							+ "｜原因 " + r.cause() + "｜时长 " + r.durationSeconds() + "s｜存活 "
							+ r.survivors() + "/" + r.participants());
				}
				setParticipantsGameMode(GameMode.ADVENTURE);
			}
			case RETURN -> {
				cleanupParticipants();
				returnToLobby();
				auditResidue();
			}
			case LOBBY -> {
				List<String> recheck = machine.residueReport();
				if (recheck.isEmpty()) {
					NBW.LOGGER.info("[S10] lobby-recheck=CLEAN matchesPlayed={} queued={}",
							machine.matchIndex(), machine.queuedCount());
				} else {
					NBW.LOGGER.error("[S10] lobby-recheck=FAIL {}", recheck);
				}
				if (t.from() == Phase.RETURN) {
					announce("§a已回大厅，局内状态已清理；/cww join 报名下一局");
				} else {
					// 中止路径（ENROLL/READY/PLAY 直接回 LOBBY）同样必须走一遍清理，否则残留会带进下一局
					cleanupParticipants();
					returnToLobby();
					auditResidue();
					announce("§7本局已中止（原因 " + t.event() + "），局内状态已清理");
				}
			}
			default -> {
			}
		}
	}

	/** 最后 10 秒每秒播报；走全局广播，不做玩家集合扫描（tick 热路径要便宜）。 */
	private static void broadcastCountdown() {
		Phase p = machine.phase();
		if (p == Phase.LOBBY || server == null) {
			return;
		}
		int remain = machine.config().ticksIn(p) - machine.tickInPhase();
		if (remain > 0 && remain < 10_000 && remain % 20 == 0 && remain / 20 <= 10) {
			announce("§e[cww] " + p + " 剩余 " + (remain / 20) + "s");
		}
	}

	private static int seconds(int ticks) {
		return ticks / 20;
	}

	// ---------------------------------------------------------------- 玩家集合（05 C23）

	private static Set<UUID> currentParticipants() {
		Set<UUID> out = new LinkedHashSet<>();
		for (PlayerSession s : machine.sessions().values()) {
			if (s.teamIndex() >= 0) {
				out.add(s.id());
			}
		}
		return out;
	}

	/** 合并三个来源的在线玩家：PlayerManager、world.getPlayers()、实体分区。 */
	private static List<ServerPlayerEntity> onlinePlayers() {
		List<ServerPlayerEntity> out = new ArrayList<>();
		if (server == null) {
			return out;
		}
		Set<UUID> seen = new HashSet<>();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (seen.add(p.getUuid())) {
				out.add(p);
			}
		}
		for (ServerWorld world : server.getWorlds()) {
			for (ServerPlayerEntity p : world.getPlayers()) {
				if (seen.add(p.getUuid())) {
					out.add(p);
				}
			}
		}
		return out;
	}

	/**
	 * 按 UUID 找参战者。缺失是常态（离线），但必须记下来：
	 * "传送了 3/4 人"这种证据比"看起来没报错"有用得多。
	 */
	private static List<ServerPlayerEntity> collect(Set<UUID> wanted) {
		List<ServerPlayerEntity> out = new ArrayList<>();
		Set<UUID> found = new HashSet<>();
		for (ServerPlayerEntity p : onlinePlayers()) {
			if (wanted.contains(p.getUuid()) && found.add(p.getUuid())) {
				out.add(p);
			}
		}
		int missing = wanted.size() - found.size();
		if (missing > 0) {
			NBW.LOGGER.warn("[round] {} 名参战者查无实体（离线或未被任何来源收录）", missing);
		}
		return out;
	}

	// ---------------------------------------------------------------- 阶段副作用实现

	private static void onJoin(ServerPlayerEntity player) {
		NBW.LOGGER.info("[round] JOIN fired for {} (uuid={}, phase={})",
				player.getNameForScoreboard(), player.getUuid(), machine.phase());
		machine.onReconnect(player.getUuid(), player.getNameForScoreboard());
		pendingPlacement.add(player.getUuid());
		player.sendMessage(Text.literal("§a[cww] 大厅｜阶段 " + machine.phase()
				+ (machine.phase().allowsEnrollment() ? "｜/cww join 报名本局" : "｜本局进行中，可报名下一局")), false);
	}

	/**
	 * 复活迁移（05 C21 / R13）：实体对象被替换，会话按 UUID 存在 domain 所以身份不丢；
	 * 但游戏模式与锚点要重新下发，否则复活点回到世界出生地、权限也错。
	 * M3 起营养/水分/武器身份等附件也要在这里迁移。
	 */
	private static void onRespawn(ServerPlayerEntity newPlayer) {
		NBW.LOGGER.info("[C21] respawn: old={} uuid={} phase={}",
				newPlayer.getNameForScoreboard(), newPlayer.getUuid(), machine.phase());
		pendingPlacement.add(newPlayer.getUuid());
	}

	/** 按"当时的阶段 + 席位"决定这名玩家该待在哪里；生命周期事件都延后一 tick 走这里。 */
	private static void placeByPhase(ServerPlayerEntity player) {
		PlayerSession s = machine.session(player.getUuid());
		if (machine.phase().matchActive() && s != null && s.teamIndex() >= 0) {
			teleportToAnchor(player, s.teamIndex());
			player.changeGameMode(machine.phase().allowsPvp() ? GameMode.SURVIVAL : GameMode.ADVENTURE);
			NBW.LOGGER.info("[round] placed {} at team {} anchor (phase={})",
					player.getNameForScoreboard(), s.teamIndex(), machine.phase());
		} else {
			placeInLobby(player);
			NBW.LOGGER.info("[round] {} placed in lobby (phase={}, hasSeat={})",
					player.getNameForScoreboard(), machine.phase(), s != null);
		}
	}

	private static void buildAnchors() {
		anchors.clear();
		if (arena == null) {
			NBW.LOGGER.error("[round] buildAnchors 但 arena 未加载");
			return;
		}
		ConfigModels.Arena.Sea sea = arena.sea;
		int teamCount = machine.config().teamCount();
		List<SpawnGeometry.Spot> auto = SpawnGeometry.equidistant(sea.centerX, sea.centerZ, sea.radius, teamCount,
				SpawnGeometry.DEFAULT_RADIUS_FACTOR);
		List<ConfigModels.Arena.Spawns.TeamSpawn> defined =
				arena.spawns == null || arena.spawns.teams == null ? List.of() : arena.spawns.teams;
		for (int i = 0; i < teamCount; i++) {
			ConfigModels.Arena.Spawns.TeamSpawn manual = null;
			for (ConfigModels.Arena.Spawns.TeamSpawn t : defined) {
				if (t.teamIndex == i) {
					manual = t;
				}
			}
			if (manual != null) {
				// 03 §3.3：teams 与 auto 并存时以手写点为准
				anchors.add(new Anchor(manual.x, manual.y, manual.z, yawToward(manual.x, manual.z, sea.centerX, sea.centerZ)));
			} else {
				SpawnGeometry.Spot spot = auto.get(i);
				anchors.add(new Anchor(spot.x(), sea.waterLevel + 1, spot.z(), spot.yaw()));
			}
		}
		int separation = SpawnGeometry.minSeparation(toSpots());
		int minRequired = arena.spawns != null && arena.spawns.auto != null ? arena.spawns.auto.minSeparation : 60;
		if (separation > 0 && separation < minRequired) {
			NBW.LOGGER.warn("[round] 出生点最小间距 {} < 配置要求 {}，可能出现两队贴脸", separation, minRequired);
		}
		NBW.LOGGER.info("[round] anchors built: {} 队，最小间距 {}（要求 >= {}）", anchors.size(), separation, minRequired);
	}

	/**
	 * READY 前把"配置要的海"和"世界上实际的地形"对读一遍，不一致就写日志。
	 *
	 * <p><b>只警告、不阻断转移，这是刻意的：</b>m1_round（120 条断言）与 g1_guards（9 条）都在旱地
	 * {@code flat_test} 上开整局，它们测的是回合骨架、跟海没关系，硬闸门会把整套回归打红。
	 * 玩家真被扔进石头里是画面问题，最终得由真人判定；这里的职责是让现场读数先存在、可追溯。
	 */
	private static void warnIfSeaMissing() {
		if (arena == null || server == null) {
			return;
		}
		List<SeaAudit.Reading> readings = SeaTerrain.sample(server.getOverworld(), arena,
				machine.config().teamCount());
		List<String> problems = SeaAudit.audit(readings);
		if (problems.isEmpty()) {
			NBW.LOGGER.info("[arena] 地形与配置一致：{} 列全过（水面 y={}，海床顶 y={}）",
					readings.size(), arena.sea.waterLevel, arena.sea.waterLevel - SeaAudit.WATER_DEPTH);
			return;
		}
		NBW.LOGGER.warn("[arena] {} 列里有 {} 列对不上 —— 对局照常开始。实测传送会同步生成未加载的区块，"
				+ "代价是一次约 2 秒的服务端冻结（2001ms / 40 tick 落后）：",
				readings.size(), problems.size());
		for (String line : problems) {
			NBW.LOGGER.warn("[arena]   {}", line);
		}
	}

	private static List<SpawnGeometry.Spot> toSpots() {
		List<SpawnGeometry.Spot> spots = new ArrayList<>();
		for (Anchor a : anchors) {
			spots.add(new SpawnGeometry.Spot(spots.size(), a.x(), a.z(), a.yaw(), 0));
		}
		return spots;
	}

	private static float yawToward(int x, int z, int cx, int cz) {
		return (float) Math.toDegrees(Math.atan2(x - cx, cz - z));
	}

	private static void teleportToAnchor(ServerPlayerEntity player, int teamIndex) {
		if (teamIndex < 0 || teamIndex >= anchors.size()) {
			return;
		}
		Anchor a = anchors.get(teamIndex);
		ServerWorld world = arenaWorld();
		if (world == null) {
			return;
		}
		// 权威传送（05 C25）；真人手感与橡皮筋需真人验证
		player.teleport(world, a.x() + 0.5, a.y(), a.z() + 0.5, a.yaw(), 0f);
	}

	private static int teleportParticipants() {
		Set<UUID> ids = currentParticipants();
		lastParticipants.addAll(ids);
		int moved = 0;
		for (ServerPlayerEntity p : collect(ids)) {
			PlayerSession s = machine.session(p.getUuid());
			if (s != null && s.teamIndex() >= 0) {
				teleportToAnchor(p, s.teamIndex());
				p.changeGameMode(GameMode.ADVENTURE); // READY 冻结输入（01 §2）
				Anchor a = s.teamIndex() < anchors.size() ? anchors.get(s.teamIndex()) : null;
				NBW.LOGGER.info("[round] moved {} team={} -> anchor={} nowAt=({},{},{})",
						p.getNameForScoreboard(), s.teamIndex(),
						a == null ? "none" : (a.x() + "," + a.y() + "," + a.z()),
						Math.round(p.getX() * 100) / 100.0, Math.round(p.getY() * 100) / 100.0,
						Math.round(p.getZ() * 100) / 100.0);
				moved++;
			}
		}
		NBW.LOGGER.info("[round] teleported {} / {} participants into arena {}", moved, ids.size(), arenaId);
		return moved;
	}

	private static void setParticipantsGameMode(GameMode mode) {
		Set<UUID> ids = currentParticipants().isEmpty() ? new LinkedHashSet<>(lastParticipants) : currentParticipants();
		List<ServerPlayerEntity> players = collect(ids);
		for (ServerPlayerEntity p : players) {
			p.changeGameMode(mode);
		}
		NBW.LOGGER.info("[round] game mode {} applied to {} / {} participants", mode, players.size(), ids.size());
	}

	private static void placeInLobby(ServerPlayerEntity player) {
		boolean fly = lobby != null && lobby.rules != null && lobby.rules.flyAllowed;
		player.changeGameMode(fly ? GameMode.CREATIVE : GameMode.ADVENTURE);
		if (lobby == null || lobby.spawn == null) {
			return;
		}
		ConfigModels.Lobby.Point spawn = lobby.spawn;
		ServerWorld world = lobbyWorld();
		if (world == null) {
			return;
		}
		player.teleport(world, spawn.x + 0.5, spawn.y, spawn.z + 0.5, spawn.yaw, spawn.pitch);
	}

	private static void returnToLobby() {
		List<ServerPlayerEntity> players = collect(lastParticipants);
		for (ServerPlayerEntity p : players) {
			placeInLobby(p);
		}
		NBW.LOGGER.info("[round] returned {} / {} participants to lobby", players.size(), lastParticipants.size());
	}

	/** A13 局后清理（01 §2）：界面、物品栏、效果归零；建造权限由阶段（非 PLAY）关闭。 */
	private static void cleanupParticipants() {
		int cleared = 0;
		for (ServerPlayerEntity p : collect(lastParticipants)) {
			p.closeHandledScreen();
			PlayerInventory inv = p.getInventory();
			for (int i = 0; i < inv.size(); i++) {
				inv.setStack(i, ItemStack.EMPTY);
			}
			inv.markDirty();
			p.playerScreenHandler.onContentChanged(inv);
			p.clearStatusEffects();
			cleared++;
		}
		List<String> raftResidue = RaftSpawner.clearAll(server);
		if (!raftResidue.isEmpty()) {
			NBW.LOGGER.error("[round] 局后筏体清理失败 {} 项：{}", raftResidue.size(), raftResidue);
		}
		NBW.LOGGER.info("[round] cleanup executed for {} participants (inventory + effects)", cleared);
	}

	/** S10 断言：domain 侧与 MC 侧都查，日志给出可核对的证据（不通过就 FAIL，不静默）。 */
	private static void auditResidue() {
		List<String> issues = new ArrayList<>(machine.residueReport());
		issues.addAll(RaftSpawner.auditResidue(server));
		// 海域预热票一起回收并纳入断言：这类票会跨重启存活，靠"记得撤"永远清不干净
		issues.addAll(ArenaChunkLoader.release(server.getOverworld()));
		for (ServerPlayerEntity p : collect(lastParticipants)) {
			if (!p.getInventory().isEmpty()) {
				issues.add(p.getNameForScoreboard() + " 物品栏非空");
			}
			if (!p.getStatusEffects().isEmpty()) {
				issues.add(p.getNameForScoreboard() + " 残留效果 " + p.getStatusEffects().size() + " 个");
			}
			if (p.interactionManager.getGameMode() != GameMode.ADVENTURE) {
				issues.add(p.getNameForScoreboard() + " 游戏模式残留 " + p.interactionManager.getGameMode());
			}
			int hidden = 0;
			var ender = p.getEnderChestInventory();
			for (int i = 0; i < ender.size(); i++) {
				if (!ender.getStack(i).isEmpty()) {
					hidden++;
				}
			}
			if (hidden > 0) {
				// M1 局内还没有可拾取物品；等有 loot 之后这条必须升级成"清空"而不是"告警"
				issues.add(p.getNameForScoreboard() + " 末影箱藏有 " + hidden + " 件物品（局内物品泄漏面）");
			}
		}
		if (issues.isEmpty()) {
			NBW.LOGGER.info("[S10] residue=CLEAN phase={} matchesPlayed={} participantsChecked={}",
					machine.phase(), machine.matchIndex(), lastParticipants.size());
		} else {
			NBW.LOGGER.error("[S10] residue=FAIL matches={} issues={}", machine.matchIndex(), issues);
		}
		lastParticipants.clear();
		RaftSinking.reset();
		RaftCoreService.reset();
		WindService.set("calm", 0.0);   // 风是局内状态，不许跨局残留成"永久顺风"
		RemoteService.reset();   // 遥控器意图是局内的：残留=下一局开局就有一艘筏自己在开
		RaftMotionService.reset();   // 位移采样的位姿基线必须重取，否则跨局第一 tick 会算出一个假速度
		BuildService.reset();   // 建造提示的节流表按玩家 UUID 涨；判定计数是跨局运维量，故意不清
		RescueService.reset();   // 扒边状态与续包窗口都是局内的：残留=下一局开局就有人在半空爬
		WeaponService.reset();   // 攻速闸门的"上次有效攻击"是局内的：残留=下一局开局被超频卡住
		MapResetService.cancel();   // 未完成的模板复制不许跨局写到新图上
		ZoneService.reset();   // 圈层模型与预告状态都是局内的：残留=下一局开局就按上一局的圈算
		LootService.reset();   // 已撒箱子列表是局内的：残留=下一局把旧箱子当成本局的
		MetricsService.reset();   // 本局计数清空（上一局的一行结论保留给 /cww metrics）
		SurvivalService.reset();   // 营养/水分是局内的：残留=下一局开局直接是上一局的状态
	}

	// ---------------------------------------------------------------- 配置与命令入口

	// ---------------------------------------------------------------- 落盘与重启恢复

	/**
	 * 落盘当前回合状态。
	 *
	 * <p>失败不抛（{@link RoundStateStore#write} 内部已吞 IOException 并记 ERROR）：
	 * 磁盘问题不该中断正在进行的对局，但也不能假装恢复能力还在。
	 */
	private static void persist(String why) {
		RoundStateStore.write(worldRoot(), machine.snapshot(RoundStateStore.AUDIT_KEEP));
		NBW.LOGGER.debug("[state] 已落盘（{}）", why);
	}

	/**
	 * 重启时把状态从盘上拉回来（Gate G1"重启能恢复到当前阶段"）。
	 *
	 * <p>三种结果的处理方式必须不同：
	 * <ul>
	 *   <li>{@code ABSENT}：首次启动，正常，留在 LOBBY；</li>
	 *   <li>{@code OK}：<b>先按快照里记的 format/arena 载入配置</b>，再恢复状态机。
	 *       顺序反了就会出现"落盘时 2x2、恢复时默认 4x3"这种规格不符，
	 *       然后被 restore 的校验正确地拒掉——等于有快照却恢复不了（真机踩过）。</li>
	 *   <li>{@code INVALID}：文件存在但读不出来。记 ERROR 并留在 LOBBY，
	 *       绝不"尽力恢复"——半损坏的状态比干净重启危险得多。</li>
	 * </ul>
	 *
	 * @return 一行可读结论，进日志也进 /cww state
	 */
	public static String restoreFromDisk() {
		RoundStateStore.Load load = RoundStateStore.read(worldRoot());
		if (load.status() != RoundStateStore.Status.OK) {
			return RoundStateStore.describe(load);
		}
		RoundSnapshot snap = load.snapshot();
		// 必须先用快照自己记的 format/arena 载入配置，再恢复状态机。
		// 顺序反了机器还停在默认规格（4x3），而快照是 2x2，
		// restore 的队伍数校验会正确地拒绝恢复——等于有快照却永远恢复不了（真机踩过）。
		String cfgErr = loadConfigs(snap.formatId(), snap.arenaId());
		if (cfgErr != null) {
			NBW.LOGGER.error("[state] 快照指定的配置载不出来（format={} arena={}：{}），放弃恢复，留在 LOBBY",
					snap.formatId(), snap.arenaId(), cfgErr);
			return "恢复中止：快照引用的 format/arena 载入失败（" + cfgErr + "）";
		}
		try {
			machine.restore(snap);
		} catch (RuntimeException e) {
			// restore 里包含"队伍数与当前 format 规格不符"这类跨配置校验，
			// 抛出来时状态可能已经改了一半，所以直接重建一台干净的机器最稳妥
			machine = new RoundMachine(RoundConfig.standard());
			String err = loadConfigs(null, null);   // 退回默认配置，而不是继续用载不出来的那套
			NBW.LOGGER.error("[state] 快照存在但无法安全恢复，已退回 LOBBY 干净启动（原因：{}；配置重载：{}）",
					e.getMessage(), err == null ? "ok" : err);
			return "恢复失败，已退回 LOBBY：" + e.getMessage();
		}
		// 筏子本身活着，但我们这边和它的连接（spawned / RaftSinking 绑定 / 票）是运行时的，
		// 不在快照里。不重认领的话，恢复出来的这一局会看不见自己的筏。
		if (machine.phase().matchActive()) {
			NBW.LOGGER.info("[state] {}", RaftSpawner.rebindFromWorld(server));
		}
		NBW.LOGGER.info("[state] 已恢复到阶段 {}（match={}，阶段内 {} tick，参战 {} 人）",
				machine.phase(), machine.matchIndex(), machine.tickInPhase(),
				machine.snapshot(0).participantCount());
		return RoundStateStore.describe(load);
	}

	/** 运维手动落盘（正常路径是每次转移自动落，这里给"我想现在就存一份"留个口子）。 */
	public static String saveNow() {
		persist("手动");
		return describeStateFile();
	}

	public static String describeStateFile() {
		return RoundStateStore.describe(RoundStateStore.read(worldRoot()));
	}

	/** 只读暴露 domain 状态机：供 raft/ 等上层读队伍与在场情况，不得在这里改状态。 */
	public static RoundMachine machine() {
		return machine;
	}

	/** 只读：给 raft/ 拿初始筏的材质与尺寸约束，不暴露可写引用。 */
	public static ConfigModels.Arena arenaOrNull() {
		return arena;
	}

	/** 只读：当前/上一局的地图 id（给 /cww reset 这类离线命令现读配置用）。 */
	public static String arenaId() {
		return arenaId;
	}

	/** 只读：当前/上一局的圈层模型（arena 环带 + format 阶段合成）。 */
	public static @Nullable ZoneModel zoneOrNull() {
		return zone;
	}

	/** 只读：当前/上一局的赛制配置（给 loot 的 respawnIntervalSec 之类读）。 */
	public static @Nullable ConfigModels.Format formatOrNull() {
		return format;
	}

	/** 只读：本局参赛者 UUID（给 ZoneService 结算圈外伤害用）。 */
	public static java.util.Set<UUID> participantIds() {
		return java.util.Set.copyOf(lastParticipants);
	}

	/** 队号对应的出生锚点；下标越界返回 null 而不是抛，调用方自己决定怎么报。 */
	public static @Nullable Anchor anchorAt(int teamIndex) {
		return teamIndex >= 0 && teamIndex < anchors.size() ? anchors.get(teamIndex) : null;
	}

	public static Path worldRoot() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("world").toAbsolutePath();
	}

	public static Phase phase() {
		return machine.phase();
	}

	public static RoundConfig config() {
		return machine.config();
	}

	public static boolean matchRunning() {
		Phase p = machine.phase();
		return p == Phase.ENROLL || p.matchActive();
	}

	public static String state() {
		return machine.dump() + "arena=" + arenaId + " format=" + formatId
				+ " lobby=" + (lobby == null ? "未加载" : "已加载")
				+ " anchors=" + anchors.size() + " lastParticipants=" + lastParticipants.size()
				+ " online=" + onlinePlayers().size();
	}

	public static String auditLog(int limit) {
		List<RoundMachine.Transition> all = machine.audit();
		int from = Math.max(0, all.size() - limit);
		StringBuilder sb = new StringBuilder();
		for (RoundMachine.Transition t : all.subList(from, all.size())) {
			sb.append(t.matchIndex()).append(' ').append(t.from()).append(" --").append(t.event()).append("--> ")
					.append(t.to()).append(" @tick").append(t.totalTick()).append('\n');
		}
		for (String n : machine.auditNotes()) {
			sb.append("note: ").append(n).append('\n');
		}
		return sb.toString();
	}

	/** 载入 arena + format + lobby 并完整校验；成功返回 null，失败返回可读原因（03 §6.2：拒绝加载、不自动纠正）。 */
	public static String loadConfigs(String fidArg, String aidArg) {
		Path root = worldRoot();
		String fid = fidArg == null ? formatId : fidArg;
		String aid = aidArg == null ? arenaId : aidArg;
		if (!ConfigService.isSafeName(fid) || !ConfigService.isSafeName(aid)) {
			return "id 不合法（只允许 [a-z0-9_]{1,32}）";
		}
		ConfigModels.Format f;
		try {
			f = ConfigService.loadFormat(root, fid);
		} catch (Exception e) {
			return "format '" + fid + "' 读取失败：" + e.getMessage();
		}
		if (f == null) {
			return "format '" + fid + "' 内容为空";
		}
		ConfigService.Loaded arenaLoaded = ConfigService.loadArena(root, aid, true, BuiltinArenaJson.SOURCE);
		if (!arenaLoaded.ok()) {
			return "arena '" + aid + "' 被拒绝（不自动纠正）：" + String.join(" | ", arenaLoaded.errors());
		}
		ConfigService.LobbyLoaded lobbyLoaded = ConfigService.loadLobby(root, true);
		if (!lobbyLoaded.ok()) {
			return "lobby 被拒绝（不自动纠正）：" + String.join(" | ", lobbyLoaded.errors());
		}
		List<String> stageErrors = ConfigService.toZone(arenaLoaded.arena(), f).validateStages();
		if (!stageErrors.isEmpty()) {
			return "format '" + fid + "' 圈阶段被拒绝：" + String.join(" | ", stageErrors);
		}
		arena = arenaLoaded.arena();
		format = f;
		lobby = lobbyLoaded.lobby();
		zone = ConfigService.toZone(arena, f);
		arenaId = aid;
		formatId = fid;
		machine.setConfigIds(aid, fid);   // 本局身份要交给状态机，它才会随快照一起落盘
		machine.setConfig(RoundConfig.fromFormat(f.teams, f.teamSize, f.minPlayers,
				f.duration == null || f.duration.targetTotal <= 0 ? 720 : f.duration.targetTotal));
		NBW.LOGGER.info("[round] configs loaded: arena={} format={} spec={}x{} min={} lobby=({},{},{})",
				aid, fid, f.teams, f.teamSize, f.minPlayers,
				lobby.spawn == null ? "?" : lobby.spawn.x, lobby.spawn == null ? "?" : lobby.spawn.y,
				lobby.spawn == null ? "?" : lobby.spawn.z);
		return null;
	}

	/** /cww start：装配置 → 校验人数下限 → LOBBY --START--> ENROLL。返回 null 表示已开局。 */
	public static String start(String fid, String aid) {
		if (machine.phase() != Phase.LOBBY) {
			return "已有对局/报名在进行中（阶段 " + machine.phase() + "），先 /cww stop 或 /cww abort";
		}
		String resolvedArena = resolveArenaId(fid, aid);
		if (resolvedArena == null) {
			return "没指定地图，随机池里也没有满足当前赛制/人数的图";
		}
		String err = loadConfigs(fid, resolvedArena);
		if (err != null) {
			return err;
		}
		if (!machine.enrollmentReached()) {
			return "报名 " + machine.queuedCount() + " 人 < 下限 " + machine.config().minPlayers()
					+ "；用 /cww join 报名或 /cww force <玩家> <队伍> 拉人";
		}
		if (!machine.fire(RoundEvent.START)) {
			return "开局被状态机拒绝（阶段 " + machine.phase() + "）";
		}
		// 一开局就把海域区块排队预热：报名那段时间足够吸收生成开销，
		// 否则这笔账全落在 READY 传送那一刻（实测一次 2001ms / 40 tick 落后）
		ArenaChunkLoader.begin(server.getOverworld(), arenaId, arena);
		return null;
	}

	/**
	 * 解析本局地图：管理员显式指定优先；否则从随机池按赛制 + 报名人数抽一张。
	 * 池空时返回 null（调用方给出可读原因，不静默回退到默认图）。
	 */
	private static @Nullable String resolveArenaId(@Nullable String fid, @Nullable String aid) {
		if (aid != null && !aid.isBlank()) {
			return aid;
		}
		String format = (fid != null && !fid.isBlank()) ? fid : formatId;
		String picked = ArenaPool.random(worldRoot(), format, machine.queuedCount(), new java.util.Random());
		if (picked != null) {
			NBW.LOGGER.info("[round] 未指定地图，随机池按 format={} 人数={} 选中 {}", format, machine.queuedCount(), picked);
		}
		return picked;
	}

	public static String stop() {
		return fire(RoundEvent.ADMIN_STOP);
	}

	public static String abort() {
		return fire(RoundEvent.ADMIN_ABORT);
	}

	private static String fire(RoundEvent event) {
		if (!machine.fire(event)) {
			return "阶段 " + machine.phase() + " 不接受 " + event + "（转移表见 04 §3）";
		}
		return null;
	}

	/** /cww join [team]：报名 + 可选直接选队（01 §2 自主分队）。 */
	public static String join(ServerPlayerEntity player, Integer team) {
		String err = machine.queue(player.getUuid(), player.getNameForScoreboard());
		if (err != null) {
			return err;
		}
		return team == null ? null : machine.assignTeam(player.getUuid(), team);
	}

	public static String quit(ServerPlayerEntity player) {
		return machine.unqueue(player.getUuid());
	}

	/** /cww force <player> <team>：管理员把玩家拉进对局并分队（仅 LOBBY/ENROLL）。 */
	public static String force(String playerName, int team) {
		ServerPlayerEntity target = null;
		for (ServerPlayerEntity p : onlinePlayers()) {
			if (p.getNameForScoreboard().equalsIgnoreCase(playerName)) {
				target = p;
				break;
			}
		}
		if (target == null) {
			return "找不到玩家 " + playerName + "（真实玩家与假玩家都查过，见 05 C23）";
		}
		String err = machine.queue(target.getUuid(), target.getNameForScoreboard());
		if (err != null) {
			return err;
		}
		return machine.assignTeam(target.getUuid(), team);
	}

	/** 03 §6.2：对局进行中禁止替换正在使用的 arena。 */
	public static String arenaSwapBlocker() {
		return matchRunning() ? "对局进行中（阶段 " + machine.phase() + "）禁止替换正在使用的 arena" : null;
	}

	private static ServerWorld arenaWorld() {
		if (server == null) {
			return null;
		}
		String dim = arena == null || arena.dimension == null || arena.dimension.isEmpty()
				? "minecraft:overworld" : arena.dimension;
		if (!"minecraft:overworld".equals(dim)) {
			NBW.LOGGER.warn("[round] M1 只支持 overworld；arena 维度 {} 已按 overworld 处理", dim);
		}
		return server.getOverworld();
	}

	private static ServerWorld lobbyWorld() {
		return server == null ? null : server.getOverworld();
	}

	private static void announce(String message) {
		if (server == null) {
			return;
		}
		server.sendMessage(Text.literal(message));
		NBW.LOGGER.info("[round] broadcast: {}", message.replaceAll("§.", ""));
	}
}
