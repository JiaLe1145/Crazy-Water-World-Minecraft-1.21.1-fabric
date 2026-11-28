package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.arena.ArenaChunkLoader;
import dev.nbw.arena.ArenaPool;
import dev.nbw.arena.BuiltinArenaJson;
import dev.nbw.arena.BuiltinConfigJson;
import dev.nbw.arena.MapResetService;
import dev.nbw.arena.SeaTerrain;
import dev.nbw.loot.LootService;
import dev.nbw.survival.SurvivalService;
import dev.nbw.combat.CombatStats;
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
import dev.nbw.raft.Sailing;
import dev.nbw.raft.WindService;
import dev.nbw.domain.round.Transitions;
import dev.nbw.domain.raft.RaftLayout;
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
import java.util.Locale;
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
	/**
	 * 待强制复活的玩家（死亡那一 tick 只登记，下一 tick 再走 respawn）。
	 *
	 * <p>为什么不直接在 AFTER_DEATH 里调 respawn：那个回调在实体死亡处理的中途，
	 * 当场替换玩家实体会把死亡流程搅乱。隔一 tick 做是安全的。
	 */
	private static final Set<UUID> pendingRespawn = new LinkedHashSet<>();
	private static int placementStallTicks;
	/**
	 * 重启恢复后筏子可能比状态机晚载入；帆/设施登记要等重认领成功后再从 plot 扫一次。
	 * 这个标记只在对局中恢复时为 true，由 20 tick 的重试块消费。
	 */
	private static boolean restoredRuntimePending;
	/** 幸存者必须在筏子所在区块加载之后再 spawn，否则世界还找不到实体、下一拍就被当死亡清掉。 */
	private static boolean restoredSurvivorsPending;

	/** 报名期席位清扫的节拍计数（每秒一次就够，不必每 tick 点一遍在场列表）。 */
	private static int lobbySweepTicks;

	/**
	 * READY 的"入图闸门"是否还挂着（{@code true} = 人还在大厅，等地图重置完 + 筏停稳）。
	 *
	 * <p>2026-11-22：原来是"发完筏立刻传人"，于是人先掉水里、木筏再浮上来。
	 * 现在按用户要求改成严格顺序：<b>重置地图 → 生成木筏 → 木筏停稳 → 才传送玩家</b>。
	 */
	private static boolean entryPending;
	/** 闸门挂起后经过的 tick 数（用于"观察期"与超时兜底）。 */
	private static int entryWaitTicks;
	/** 入图后第几 tick 去读"人到底站到哪了"；{@code >0} 表示还在倒计时。 */
	private static int entryProbeTicks;
	/** 本局已经被"PLAY 兜底补传"救过的玩家（同一局每人只报一次 WARN，避免假玩家刷屏）。 */
	private static final Set<UUID> pulledThisMatch = new LinkedHashSet<>();
	/** 发筏之后至少等这么多 tick 才放人：Sable 推物理管线、再把 sub-level 同步给客户端都要 tick。 */
	private static final int ENTRY_MIN_TICKS = 10;
	/** 闸门最多挂这么久：超时也要放人，绝不能因为某艘筏一直"没停稳"把人整局留在大厅。 */
	private static final int ENTRY_MAX_TICKS = 120;

	/** 出生锚点：方块坐标 + 朝向圈心的 yaw。 */
	public record Anchor(int x, int y, int z, float yaw) {
	}

	private RoundManager() {
	}

	// ---------------------------------------------------------------- 事件注册

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {			server = s;
			// 大厅/海域配置要在开服时就位，否则玩家连进服都无处安置（实测踩过：lobby==null 时 placeInLobby 直接返回）
			String err = loadConfigs(null, null);
			if (err != null) {
				NBW.LOGGER.warn("[round] 开服配置载入未完成（玩家会留在原地，/cww start 会再试一次）：{}", err);
			}
			// 恢复必须在配置就位之后：快照里的队伍数要与 format 规格对得上，
			// 对不上时 restore() 会抛，那正是我们想要的（宁可起不来，也不要按错误前提跑完整局）
			NBW.LOGGER.info("[state] 重启恢复：{}", restoreFromDisk());
			CareerService.load(worldRoot());   // 跨局战绩与外观（01 §A11）
			AnnouncementService.load(worldRoot());   // 公告持久化（参照 RicordRailExpress）
			WorldRules.apply(s);   // 锁正午/关刷怪/锁天气：竞技图不能天黑也不能刷怪
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			CareerService.flushIfDirty(worldRoot());   // 停机前刷盘：崩服不再丢整局战绩
			server = null;
		});
		ServerTickEvents.END_SERVER_TICK.register(RoundManager::onEndTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> onJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
			ServerPlayerEntity player = handler.getPlayer();
			NBW.LOGGER.info("[round] DISCONNECT {} (uuid={}, phase={}, seat={})",
					player.getNameForScoreboard(), player.getUuid(), machine.phase(),
					machine.session(player.getUuid()) != null);
			// 死亡导致的断线（典型是 /kill 后的 "lost connection: Killed"）必须按淘汰处理：
			// 按"掉线"开保席会让这条命一直算存活，aliveTeamCount 减不下去，
			// 最后可能把胜负判给已经死了的那一队（2026-11-20 生命周期排查实测踩到）。
			if (!player.isAlive()) {
				NBW.LOGGER.info("[round] DISCONNECT-AS-DEATH {}（掉线时已死亡，按淘汰处理）",
						player.getNameForScoreboard());
				machine.onDeath(player.getUuid());
			} else {
				machine.onDisconnect(player.getUuid());
			}
			CareerService.flushIfDirty(worldRoot());   // 掉线也刷一次：崩溃丢掉的是"这一局"，不是"这个人的历史"
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> onRespawn(newPlayer));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayerEntity player) {
				PlayerSession s = machine.session(player.getUuid());
				NBW.LOGGER.info("[round] DEATH {} (uuid={}, phase={}, session={}, team={}, eliminated={})",
						player.getNameForScoreboard(), player.getUuid(), machine.phase(), s != null,
						s == null ? "-" : s.teamIndex(), s != null && s.isEliminated());
				machine.onDeath(player.getUuid());
				// 淘汰后观战（M5-3）：死亡瞬间先切成旁观，重生时就是旁观视角（不必等 onRespawn）
				if (machine.phase() == Phase.PLAY) {
					player.changeGameMode(GameMode.SPECTATOR);
					// 登记"下一 tick 强制复活"：不复活的话客户端会一直卡在死亡屏，
					// 实体也停在 isAlive()==false（下一局会被 sweep 判成淘汰）。
					pendingRespawn.add(player.getUuid());
					String tail = spectatorTail(player.getUuid());
					player.sendMessage(Text.literal("§7[cww] 你已被淘汰｜" + tail), false);
					// 这条日志是探针唯一能读到的"观战提示已下发"证据（RCON 读不到玩家聊天）。
					NBW.LOGGER.info("[round] spectator notice: {}｜{}", player.getNameForScoreboard(), tail);
				}
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

	/** 大厅与非 PLAY 阶段禁止玩家互殴（04 §3 阶段内权限集中）；PLAY 内同队也禁止（02 §8 friendly fire 默认关闭）。 */
	private static boolean allowPlayerVsPlayer(Entity target, Entity attacker) {
		if (!(target instanceof ServerPlayerEntity) || !(attacker instanceof ServerPlayerEntity)) {
			return true;
		}
		if (!machine.phase().allowsPvp()) {
			NBW.LOGGER.info("[round] pvp blocked in phase {}: {} -> {}", machine.phase(),
					attacker.getNameForScoreboard(), target.getNameForScoreboard());
			return false;
		}
		if (!target.equals(attacker) && machine.sameTeam(target.getUuid(), attacker.getUuid())) {
			NBW.LOGGER.info("[round] friendly fire blocked: {} -> {}", attacker.getNameForScoreboard(),
					target.getNameForScoreboard());
			return false;
		}
		return true;
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
		drainPendingRespawns();
		// 入图闸门只在 READY 里有意义；每 tick 判一次"该不该放人"
		tickEntryGate();
		// 预热队列跨阶段推进（ENROLL 就开始排队），所以不能塞进下面那个 PLAY-only 块里
		ArenaChunkLoader.tick(srv.getOverworld());
		MapResetService.onEndTick();   // 模板复制：READY 期间逐 tick 推进，不能只放在 PLAY 块里
		sweepLobbySeats();   // 报名期的幽灵席位清扫，同理不能塞进 PLAY-only 块里
		LobbyReadyService.onEndTick(srv);   // 大厅内默认参加 / 出界取消（每 10 tick）
		if (machine.phase() != Phase.PLAY) {
			// 非对局阶段不该有人死着：死亡屏是客户端 UI，服务端不主动 respawn 就永远关不掉。
			// 实测（2026-11-22）：SETTLE 里淹死一个人 → 之后一直卡在 "你死了"，连大厅都回不来。
			respawnDeadOutsidePlay(srv);
		}
		if (machine.phase() == Phase.PLAY) {
			sweepDeaths();
			ZoneService.onEndTick(srv);   // 黑海圈层：收缩预告 + 圈外每秒伤害
			MinimapService.onEndTick(srv);   // 小地图：每 10 tick 推队友/木筏/黑海位置
			LootService.onEndTick(srv);   // 海面物资：按 respawnIntervalSec 定时补箱
			SurvivalService.onEndTick(srv);   // 营养/水分：每秒衰减 + debuff + HUD
			EventService.onEndTick(srv);   // 事件池：到点抽一发
			SurvivorService.onEndTick(srv);   // 幸存者：口粮消耗 + 断粮离队
			GuardService.onEndTick(srv);   // 卫兵：守筏自动射击（幸存者贴回甲板之后）
			BallistaService.onEndTick(srv);   // 弩炮：已放置炮位自动打最近敌方（09 §2.2）
			CatapultService.onEndTick(srv);   // 投石机：40 格拆家，只打敌方筏心（09 §2.2）
			RamService.onEndTick(srv);   // 撞角：筏移动时撞击贴身敌人（09 §2.2）
			RaftSinking.onEndTick(srv);
			RaftCoreService.onEndTick(srv);
			WindService.apply(srv);   // 帆：登记风力目标（被动等风，方向跟随前进）
			RemoteService.onEndTick(srv);   // 遥控器：登记马达目标 + 施转向角冲量
			RescueService.onEndTick(srv);   // 扒边：只挪玩家位置，筏的目标仍由下一行合成
			RaftMotionService.onEndTick(srv);   // 各目标合成之后，本 tick 只施一发控制冲量
			broadcastSpectatorStatus(srv);   // 观战 actionbar 最后发，避免被圈层 actionbar 覆盖
		}
		// 恢复出来的这一局必须能晚点认领筏子：重启那一刻 Sable 还没把 sub-level 载进容器，
		// 一次性 rebindFromWorld 实测就是"重认领 0 艘筏"，之后这一局再也看不见自己的筏。
		// rebind 是幂等的（补 UUID + 绑沉筏 + 补票），所以只在还有队没绑上时按节拍重试。
		if (machine.phase().matchActive() && machine.tickInPhase() % 20 == 0 && RaftSpawner.needsRebind()) {
			NBW.LOGGER.info("[state] 延后重试认领：{}", RaftSpawner.rebindFromWorld(srv));
			if (restoredRuntimePending) {
				rebuildRestoredRegistriesIfReady();
			}
		}
		broadcastCountdown();
	}

	/**
	 * 局结算：把本局结果累加进跨局战绩并落盘（01 §A11：局外只累计外观与战绩，无数值）。
	 *
	 * <p>胜者按"胜者队的全部成员"各 +1 胜；平局不记胜。击杀数取 {@link CombatStats} 的本局快照。
	 */
	private static void recordCareer() {
		if (server == null) {
			return;
		}
		RoundMachine.Result r = machine.lastResult();
		if (r == null) {
			return;
		}
		java.util.Set<UUID> participants = new LinkedHashSet<>(lastParticipants);
		for (var s : machine.sessions().values()) {
			participants.add(s.id());
		}
		java.util.Set<UUID> winners = new LinkedHashSet<>();
		if (!r.isDraw() && r.winnerTeamIndex() >= 0) {
			for (UUID id : participants) {
				var s = machine.session(id);
				if (s != null && s.teamIndex() == r.winnerTeamIndex()) {
					winners.add(id);
				}
			}
		}
		CareerService.recordMatch(participants, winners, CombatStats.killsByPlayer());
		CareerService.save(worldRoot());
		NBW.LOGGER.info("[career] 本局结算已并入跨局战绩：胜者 {} 人、击杀 {} 条", winners.size(),
				CombatStats.killsByPlayer().size());
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
				// ★ 2026-11-21 修真 bug：淘汰之后**必须**把实体真正复活一次。
				// 死亡屏是客户端 UI，只有服务端走一次 respawn 才会关掉；我们原来只
				// changeGameMode(SPECTATOR) 就完事，于是实体永远停在 isAlive()==false：
				//   · 客户端一直卡在 You Died（用户凌晨看到的"死亡后没有正常复活"）；
				//   · 下一局一进 PLAY，本 sweep 立刻把它判成"在场但不存活"→按淘汰处理，
				//     整队瞬间全灭（实测 match=80 的 PLAY 只存在 1 tick）。
				forceRespawn(p);
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
	 * 强制走一次服务端复活流程（关掉客户端的死亡界面，让实体重新 isAlive()）。
	 *
	 * <p>为什么非做不可：MC 的死亡屏是**客户端 UI**，只有收到 respawn 的数据包才会消失；
	 * 服务端把玩家切成旁观、改血量、传送，都关不掉它。玩家卡在死亡屏时实体一直
	 * {@code isAlive()==false}，于是：
	 * <ul>
	 *   <li>客户端画面停在 You Died（用户实际遇到的现象）；</li>
	 *   <li>下一局 {@link #sweepDeaths()} 会把它判成"在场但不存活"→ 立刻淘汰，
	 *       整队全灭（实测 match=80 的 PLAY 只存在 1 tick）。</li>
	 * </ul>
	 * 复活会触发 {@code AFTER_RESPAWN} → {@link #onRespawn}，那里本来就有
	 * "复活兜底淘汰"（幂等）与重新落位，所以这里只管把人从死亡状态里拉出来。
	 */
	private static void forceRespawn(ServerPlayerEntity p) {
		if (server == null) {
			return;
		}
		try {
			server.getPlayerManager().respawnPlayer(p, false,
					net.minecraft.entity.Entity.RemovalReason.KILLED);
			NBW.LOGGER.info("[round] force respawn（关掉客户端死亡界面）：{}", p.getNameForScoreboard());
		} catch (Throwable t) {
			// 不吞异常地静默失败：这条如果坏了，后面的每一局都会被"1 tick 全灭"污染
			NBW.LOGGER.warn("[round] force respawn 失败（{}）：{}", p.getNameForScoreboard(), t.toString());
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

	/**
	 * 把"死亡下一 tick 要复活"的玩家真正复活一次（关掉客户端死亡屏）。
	 *
	 * <p>只处理还在线、且实体确实 {@code !isAlive()} 的人；已经复活的直接跳过。
	 */
	private static void drainPendingRespawns() {
		if (pendingRespawn.isEmpty() || server == null) {
			return;
		}
		List<UUID> ids = new ArrayList<>(pendingRespawn);
		pendingRespawn.clear();
		for (UUID id : ids) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
			if (p == null) {
				continue;   // 已离线：下次进来时 onRespawn 会重新落位
			}
			if (p.isAlive()) {
				continue;   // 已经活过来了（比如被别的路径复活），不要重复换实体
			}
			forceRespawn(p);
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
				// 新一轮正常开局：上一局残留的“重启恢复待办”绝不能带进来。
				restoredRuntimePending = false;
				restoredSurvivorsPending = false;
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
				// ★ 2026-11-22 修（用户实机："开局直接掉水里，木筏刷新后才被传回筏上"）：
				// **不再"发完筏立刻传人"**。发筏那一刻筏只是被写进了坐标，Sable 还没把它推进
				// 物理管线、客户端也还没收到；立刻传送等于把人放在空气里，他先掉进水里，
				// 等筏子浮上来、到 PLAY 再补传一次，看起来就是"掉水→木筏出现→被弹回筏上"。
				// 现在的顺序是：重置地图 → 发筏 → 等筏停稳 → 再放人（闸门见 tickEntryGate）。
				entryPending = true;
				entryWaitTicks = 0;
				pulledThisMatch.clear();   // 本局的"兜底补传"告警按人只报一次，跨局要重置
				NBW.LOGGER.info("[round] 入图闸门已挂起：等模板复制与发筏稳定后再放人（READY {} tick）",
						machine.config().readyTicks());
				announce("§e正在重置地图并生成木筏，" + seconds(machine.config().readyTicks()) + " 秒后开局");
			}
			case PLAY -> {
				LobbyReadyService.hideAll(server);   // 开局后收起顶部"参加人数"面板
				setParticipantsGameMode(GameMode.SURVIVAL);
				// 入图已由 READY 闸门保证（人在自己筏的甲板上）。这里只做**兜底**：
				// 闸门超时、或者客户端没跟上被判定在水里的，补一次落位。
				// 正常情况下这一段一次都不会触发 —— 触发就会留 WARN，便于以后回溯。
				pullFallenParticipants();
				setParticipantsNoGravity(false);   // 安全网：任何路径进 PLAY 都确保重力是开的
				WorldRules.apply(server);   // 每局再刷一遍，防管理员中途改规则
				MetricsService.begin(server);   // 埋点：局时长/首战/生产参与率的起点
				ZoneService.begin(server);   // 黑海圈层：抓半径/伤害表与圈心
				MinimapService.begin(server);   // 小地图：给本局参赛者随机分配颜色
				LootService.begin(server);   // 海面物资：按 loot.prefill 撒补给箱 + 定补箱间隔
				SurvivalService.begin(server);   // 生存：参赛者满营养/满水分
				EventService.begin(server);   // 事件池（M5-1）：排第一发
				if (!restoredSurvivorsPending) {
					// 从 READY 恢复进 PLAY 时，幸存者要等筏重认领后再 spawn（见 rebuildRestoredRegistriesIfReady）。
					SurvivorService.begin(server);   // 幸存者（M5-2）：每队按人数发人/口粮
				}
				PowerService.begin(server);   // 功率池（M4 补）：每队电量清零
				if (restoredRuntimePending) {
					// 从 READY 恢复后第一次进 PLAY：筏已载入，补扫帆/设施登记。
					rebuildRestoredRegistriesIfReady();
				}
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
				for (String line : CombatStats.summary(server)) {   // 战绩面板（M5-3）
					announce(line);
				}
				CombatStats.persist(server);   // 战绩落盘（M5-3）：state/matches/<match>.json
				recordCareer();   // 跨局战绩与外观（01 §A11）：胜者 +1 胜、击杀累加
				setParticipantsGameMode(GameMode.ADVENTURE);
			}
			case RETURN -> {
				cleanupParticipants();
				setParticipantsNoGravity(false);   // 安全网：任何路径离开对局都恢复重力
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
					setParticipantsNoGravity(false);   // 安全网：任何路径离开对局都恢复重力
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
		AnnouncementService.syncTo(player);   // 补发最近几条公告：刚进来的人也该看到
	}

	/**
	 * 复活迁移（05 C21 / R13）：实体对象被替换，会话按 UUID 存在 domain 所以身份不丢；
	 * 但游戏模式与锚点要重新下发，否则复活点回到世界出生地、权限也错。
	 * M3 起营养/水分/武器身份等附件也要在这里迁移。
	 */
	private static void onRespawn(ServerPlayerEntity newPlayer) {
		NBW.LOGGER.info("[C21] respawn: old={} uuid={} phase={}",
				newPlayer.getNameForScoreboard(), newPlayer.getUuid(), machine.phase());
		// 复活兜底淘汰：有些死法（管理员 /kill、以及任何绕过 damage 链的移除）不触发
		// AFTER_DEATH，会话会一直停在"存活"，于是 aliveTeamCount 永远减不下去、判胜被堵死。
		// 这里认"对局中还能复活 = 说明这一条命已经没了"，补记一次淘汰（幂等，正常死亡时是空操作）。
		PlayerSession rs = machine.session(newPlayer.getUuid());
		if (machine.phase() == Phase.PLAY && rs != null && rs.teamIndex() >= 0 && !rs.isEliminated()) {
			machine.onDeath(newPlayer.getUuid());
			NBW.LOGGER.info("[round] respawn 兜底淘汰：{}（未走 AFTER_DEATH 的死亡）",
					newPlayer.getNameForScoreboard());
		}
		pendingPlacement.add(newPlayer.getUuid());
	}

	/** 按"当时的阶段 + 席位"决定这名玩家该待在哪里；生命周期事件都延后一 tick 走这里。 */
	private static void placeByPhase(ServerPlayerEntity player) {
		PlayerSession s = machine.session(player.getUuid());
		if (machine.phase().matchActive() && s != null && s.teamIndex() >= 0) {
			teleportToAnchor(player, s.teamIndex());
			if (s.isEliminated()) {
				// 淘汰后观战（M5-3）：留在图里飞着看，而不是直接踹回大厅
				player.changeGameMode(GameMode.SPECTATOR);
			} else {
				player.changeGameMode(machine.phase().allowsPvp() ? GameMode.SURVIVAL : GameMode.ADVENTURE);
			}
			NBW.LOGGER.info("[round] placed {} at team {} anchor (phase={}, eliminated={})",
					player.getNameForScoreboard(), s.teamIndex(), machine.phase(), s.isEliminated());
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
				// 锚点 y = <b>筏底基准</b>（= 水线那一格），不是玩家落脚点：
				// 筏体生成在 anchor.y+1、甲板占 anchor.y+1..anchor.y+2，玩家的实际落点由
				// RaftSpawner.entryPoint 按 plot 局部坐标算（甲板面）。2026-11-22 之前这里用的
				// waterLevel+1，配手写点用 generateY+4，两种口径混着用，最后人全被放进甲板方块里。
				anchors.add(new Anchor(spot.x(), sea.waterLevel, spot.z(), spot.yaw()));
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

	/**
	 * 把参赛者放到"自己那艘筏的甲板面"上。
	 *
	 * <p><b>2026-11-22 修的真 bug：</b>原来用的是出生锚点的 Y。锚点是"筏底基准"，
	 * 筏体生成在 {@code y+1}、甲板占 {@code y+1..y+2}，所以玩家被放进去时脑袋正好埋进甲板方块，
	 * 原版把他往下顶 → 开局掉水里。现在落点由 {@link RaftSpawner#entryPoint} 从筏的
	 * plot 局部坐标正变换出来（可站面 = 局部 {@link RaftLayout#DECK_SURFACE_Y} + 余量），
	 * 筏漂到哪、转到哪都对。
	 *
	 * <p>没有筏（或筏已沉到水线以下）时退回锚点上方 {@code +2.05}：那是"甲板顶 ≈ 锚点+2"
	 * 的经验值再加一点余量，宁可多落半格，也不能再把人放到甲板方块里。
	 */
	private static void teleportToAnchor(ServerPlayerEntity player, int teamIndex) {
		if (teamIndex < 0 || teamIndex >= anchors.size()) {
			return;
		}
		Anchor a = anchors.get(teamIndex);
		ServerWorld world = arenaWorld();
		if (world == null) {
			return;
		}
		double x = a.x() + 0.5, y = a.y() + 2.05, z = a.z() + 0.5;
		double[] deck = server == null ? null : RaftSpawner.entryPoint(server, teamIndex);
		if (deck != null && deck[1] >= a.y() + 0.5) {
			// 只有"落在水线以上"的甲板落点才可信：筏已经沉了的话，甲板面在水下，
			// 把人放上去等于直接把他塞进水里，不如退回锚点上方的浮空落点。
			x = deck[0];
			y = deck[1] + 0.05;
			z = deck[2];
		} else if (deck != null) {
			NBW.LOGGER.warn("[round] 队 {} 的甲板落点在水线以下（y={}，水线 {}），退回锚点上方",
					teamIndex, Math.round(deck[1] * 100) / 100.0, a.y());
		}
		// ★ 2026-11-21 第二次修：只发 requestTeleport，**不要**再先调 teleport()。
		// 同时调两个会发两份位置包：服务端实体先跳过去，客户端那份还没确认就又报了旧坐标，
		// 反作弊于是判定 "moved too quickly" 把它拉回来 —— 实测日志刷屏
		// `Tester2 moved too quickly! -1000.0,60.95,-1048.0`，玩家被卡在大厅进不了图。
		// requestTeleport 会让 networkHandler 进入"等待确认"状态，正好避免这套打架
		// （RescueService 的绳索传送一直只用它，所以那条链路从来没出过这个问题）。
		player.requestTeleport(x, y, z);
		if (player.getYaw() != a.yaw()) {
			player.setYaw(a.yaw());
		}
	}

	/**
	 * READY 的入图闸门：<b>模板重置完成 + 每队筏都生成并停稳</b>之后，才把参赛者放进图里。
	 *
	 * <p>用户 2026-11-22 的原话是要改时间逻辑：先重置地图，重置完先不传人，先生成木筏，
	 * 木筏出来了再把玩家传进去，避免"先掉水 → 生成木筏 → 传回木筏"。
	 * 这里就按这个顺序做，另外补了一条"观察期"：Sable 把 sub-level 推进物理管线、
	 * 再把方块同步给客户端都需要 tick，发筏当 tick 就传人，客户端那一刻还什么都看不到。
	 *
	 * <p>超时兜底：{@link #ENTRY_MAX_TICKS} 之内没等到（例如某艘筏一直判定"还在动"），
	 * 仍然照常传送并写 ERROR —— 卡在大厅比"稍微掉一下"更糟。
	 */
	private static void tickEntryGate() {
		// 闸门放行之后还要补一次"实测读数"：requestTeleport 当 tick 读到的还是大厅坐标
		if (entryProbeTicks > 0 && --entryProbeTicks == 0) {
			logSettledDeckY();
		}
		if (!entryPending) {
			return;
		}
		if (machine.phase() != Phase.READY) {
			// 阶段被中止（abort/管理员跳阶段）时闸门作废。
			// ★ 但如果已经进了 PLAY（例如 READY 倒计时走完、闸门还卡着），**必须**补一次传送：
			// 否则整局人都在大厅里，"入图闸门"反而变成"没入图"。这正是我们要避免的那类新 bug。
			boolean intoMatch = machine.phase().matchActive();
			entryPending = false;
			entryWaitTicks = 0;
			if (intoMatch) {
				int moved = teleportParticipants();
				entryProbeTicks = 10;
				NBW.LOGGER.error("[round] 入图闸门没等到就进入了 {}：仍然传送 {} 人（请查发筏日志）",
						machine.phase(), moved);
			} else {
				NBW.LOGGER.info("[round] 入图闸门作废：阶段已离开 READY（{}），未入图的人留在大厅",
						machine.phase());
			}
			return;
		}
		entryWaitTicks++;
		String blocked = entryBlockedReason();
		if (blocked == null) {
			int moved = teleportParticipants();
			entryPending = false;
			NBW.LOGGER.info("[round] 入图闸门放行（等了 {} tick）：{} 人落到各自木筏的甲板上", entryWaitTicks, moved);
			entryWaitTicks = 0;
			// requestTeleport 是"发出去等客户端确认"的：当 tick 读 p.getY() 还是旧坐标（大厅）。
			// 所以要隔几 tick 再读一次实测读数，那才是"人到底站在哪"。
			entryProbeTicks = 10;
			return;
		}
		if (entryWaitTicks >= ENTRY_MAX_TICKS) {
			int moved = teleportParticipants();   // 兜底：宁可回到旧行为，也不能让人整局留在大厅
			entryPending = false;
			NBW.LOGGER.error("[round] 入图闸门超时（{} tick，原因：{}）：仍然传送 {} 人 —— 请查上面的发筏日志",
					entryWaitTicks, blocked, moved);
			entryWaitTicks = 0;
		} else if (entryWaitTicks % 20 == 0) {
			NBW.LOGGER.info("[round] 入图闸门等待中（{} tick）：{}", entryWaitTicks, blocked);
		}
	}

	/** 闸门为什么还没放行；{@code null} = 可以放人。 */
	private static @Nullable String entryBlockedReason() {
		if (server == null) {
			return "服务端未就绪";
		}
		if (entryWaitTicks < ENTRY_MIN_TICKS) {
			return "观察期未满（" + entryWaitTicks + "/" + ENTRY_MIN_TICKS + "）";
		}
		if (MapResetService.isRunning()) {
			return "模板复制还没结束：" + MapResetService.status();
		}
		return RaftSpawner.entryBlockedReason(server);
	}

	/**
	 * 入图之后把"人站上去以后的实测读数"写一行日志：脚底 Y、落点 Y、筏位姿 Y、以及脚底−位姿。
	 *
	 * <p>为什么要专门记这一行：{@link RaftLayout#DECK_SURFACE_Y} 那个数是**实测**出来的
	 * （站上去以后脚底 = 位姿 + 0.295），不是从方块几何推出来的。以后有人怀疑"落点偏高/偏低"，
	 * 这一行就是第一手证据：<b>差(脚底-位姿) 应当稳定在 0.30 附近</b>；
	 * 明显大于 0.5 说明落点偏高（人会看到下落），明显小于 0.1 说明压进了甲板方块。
	 */
	private static void logSettledDeckY() {
		if (server == null) {
			return;
		}
		for (ServerPlayerEntity p : collect(currentParticipants())) {
			PlayerSession s = machine.session(p.getUuid());
			if (s == null || s.teamIndex() < 0) {
				continue;
			}
			double[] entry = RaftSpawner.entryPoint(server, s.teamIndex());
			double poseY = RaftSpawner.poseY(server, s.teamIndex());
			NBW.LOGGER.info("[entry] {} 队={} 脚底Y={} 落点Y={} 筏位姿Y={} 差(脚底-位姿)={}",
					p.getNameForScoreboard(), s.teamIndex(),
					Math.round(p.getY() * 100) / 100.0,
					entry == null ? "?" : Math.round(entry[1] * 100) / 100.0,
					Double.isNaN(poseY) ? "?" : Math.round(poseY * 100) / 100.0,
					Double.isNaN(poseY) ? "?" : Math.round((p.getY() - poseY) * 100) / 100.0);
		}
	}

	/**
	 * PLAY 开局兜底：还在水里的参赛者补一次落位。
	 *
	 * <p>入图已经由 READY 闸门保证，这一段正常<b>一次都不该触发</b>；
	 * 它存在是为了"闸门超时兜底"与"客户端没跟上"这两种残余情况不至于毁掉整局。
	 */
	private static void pullFallenParticipants() {
		if (server == null) {
			return;
		}
		for (ServerPlayerEntity p : collect(currentParticipants())) {
			PlayerSession s = machine.session(p.getUuid());
			if (s == null || s.teamIndex() < 0 || s.isEliminated() || p.isSpectator()) {
				continue;
			}
			Anchor a = anchorAt(s.teamIndex());
			if (a == null || p.getY() >= a.y() + 0.5) {
				continue;   // 站得比水线高：正常
			}
			// 必须在传送**之前**把读数取下来：requestTeleport 之后 p.getY() 就可能已经是新坐标了，
			// 拿它写日志会得到"y=151 却报'仍在水里'"这种自相矛盾的行（第一版就这么写错了）。
			double yBefore = p.getY();
			teleportToAnchor(p, s.teamIndex());
			boolean first = pulledThisMatch.add(p.getUuid());
			String line = String.format(Locale.ROOT,
					"[round] PLAY 兜底补传 %s：入图后仍在水里（y=%.2f，水线 %d）",
					p.getNameForScoreboard(), yBefore, a.y());
			if (first) {
				NBW.LOGGER.warn(line);
			} else {
				// 假玩家（Carpet）不受 sub-level 碰撞约束，一局里会反复掉下去：
				// 第一次 WARN 足够报警，后面降成 INFO，别把日志刷满。
				NBW.LOGGER.info(line + "（本局已补传过，降级为 INFO）");
			}
		}
	}

	/**
	 * 非对局阶段把"死着的人"复活回来。
	 *
	 * <p>为什么要单独有一条：{@link #sweepDeaths()} 只在 PLAY 跑，AFTER_DEATH 里登记重生的分支也只在
	 * PLAY 生效。于是"SETTLE 里淹死一个人"就变成一个永久死亡屏 —— 实测 2026-11-22：
	 * 结算阶段玩家淹死后客户端一直停在"你死了"，回不到大厅，下一局也进不去。
	 *
	 * <p>每秒最多处理一次：这不是玩法判定，只是把人从死亡状态里拉出来的兜底。
	 */
	private static void respawnDeadOutsidePlay(MinecraftServer srv) {
		if (srv.getTicks() % 20 != 0) {
			return;
		}
		for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
			if (p.isAlive() || p.isRemoved()) {
				continue;
			}
			NBW.LOGGER.info("[round] 非对局阶段发现死亡实体（phase={}）：{} —— 强制复活回大厅", machine.phase(),
					p.getNameForScoreboard());
			forceRespawn(p);
		}
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

	/**
	 * 开关参赛者的重力。
	 *
	 * <p><b>2026-11-22 起这只是安全网</b>：入图改成"闸门放行后落到甲板面上"（见
	 * {@link #tickEntryGate()}），不再需要在 READY 里悬停，所以现在只会在
	 * 进入 PLAY / 离开对局时统一置 {@code false}（恢复重力）。
	 * 留着这个入口是因为历史路径（重启恢复、中止对局）可能带着 {@code noGravity=true}
	 * 的实体进入下一阶段 —— 那种实体会永远飘着。
	 */
	private static void setParticipantsNoGravity(boolean noGravity) {
		Set<UUID> ids = currentParticipants().isEmpty() ? new LinkedHashSet<>(lastParticipants) : currentParticipants();
		for (ServerPlayerEntity p : collect(ids)) {
			p.setNoGravity(noGravity);
		}
		if (!ids.isEmpty()) {
			NBW.LOGGER.info("[round] 参赛者重力={} （{} 人）", noGravity ? "关（悬停）" : "开", ids.size());
		}
	}

	/**
	 * 淘汰观战提示的尾巴：本队还有谁活着。
	 *
	 * <p>只拼表现层文字，存活判定在 {@link RoundMachine#aliveTeammates(UUID)}，两者分工明确。
	 */
	private static String spectatorTail(UUID id) {
		PlayerSession s = machine.session(id);
		if (s == null) {
			return "观战中";
		}
		List<UUID> mates = machine.aliveTeammates(id);
		if (mates.isEmpty()) {
			return "观战中｜本队已全灭";
		}
		List<String> names = new ArrayList<>();
		for (UUID mate : mates) {
			ServerPlayerEntity p = server == null ? null : server.getPlayerManager().getPlayer(mate);
			names.add(p == null ? "?" : p.getNameForScoreboard());
		}
		return "观战中｜存活队友：" + String.join("、", names);
	}

	/** 淘汰后每 2 秒刷一次 actionbar；放在 PLAY tick 末尾，避免被圈层 actionbar 覆盖。 */
	private static void broadcastSpectatorStatus(MinecraftServer srv) {
		if (machine.tickInPhase() % 40 != 0) {
			return;
		}
		for (UUID id : currentParticipants()) {
			PlayerSession s = machine.session(id);
			if (s == null || !s.isEliminated()) {
				continue;
			}
			ServerPlayerEntity p = srv.getPlayerManager().getPlayer(id);
			if (p != null) {
				p.sendMessage(Text.literal("§7[cww] " + spectatorTail(id)), true);
			}
		}
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
		// 同 teleportToAnchor：只发 requestTeleport，别和 teleport() 一起发（会触发 moved too quickly 拉锯）
		player.requestTeleport(spawn.x + 0.5, spawn.y, spawn.z + 0.5);
		player.setYaw(spawn.yaw);
		player.setPitch(spawn.pitch);
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
		dev.nbw.raft.Sailing.reset();   // 帆表（队→帆格）是局内的：残留=下一局 sail read 报旧帆数、rig 容量算错
		BuildService.reset();   // 建造提示的节流表按玩家 UUID 涨；判定计数是跨局运维量，故意不清
		RescueService.reset();   // 扒边状态与续包窗口都是局内的：残留=下一局开局就有人在半空爬
		dev.nbw.rescue.RopeService.reset();   // 绳索/钩竿冷却表是局内的（D-2）
		WeaponService.reset();   // 攻速闸门的"上次有效攻击"是局内的：残留=下一局开局被超频卡住
		MapResetService.cancel();   // 未完成的模板复制不许跨局写到新图上
		ZoneService.reset();   // 圈层模型与预告状态都是局内的：残留=下一局开局就按上一局的圈算
		MinimapService.reset();   // 小地图颜色是局内的
		LobbyReadyService.onMatchReset();   // 自动报名记录是局内的（"不参加"选择保留）
		LootService.reset();   // 已撒箱子列表是局内的：残留=下一局把旧箱子当成本局的
		MetricsService.reset();   // 本局计数清空（上一局的一行结论保留给 /cww metrics）
		SurvivalService.hideAll(server);   // 未开局不显示营养/水分条（2026-11-28）
		SurvivalService.reset();   // 营养/水分是局内的：残留=下一局开局直接是上一局的状态
		dev.nbw.content.SeawaterFilterBlock.reset();   // 过滤器冷却表是局内的
		dev.nbw.content.MedicalKitBlock.reset();   // 医疗箱冷却表是局内的
		if (dev.nbw.content.NbwContent.compostFarm != null) {
			dev.nbw.content.NbwContent.compostFarm.resetCooldown();
		}
		if (dev.nbw.content.NbwContent.kitchen != null) {
			dev.nbw.content.NbwContent.kitchen.resetCooldown();
		}
		for (var ranged : new Object[]{dev.nbw.content.NbwContent.handCrossbow, dev.nbw.content.NbwContent.harpoonGun,
				dev.nbw.content.NbwContent.musket, dev.nbw.content.NbwContent.javelin}) {
			if (ranged instanceof dev.nbw.content.RangedWeaponItem r) {
				r.resetCooldown();
			}
		}
		EventService.reset();   // 事件调度是局内的
		CombatStats.reset();   // 战绩是局内的
		SurvivorService.reset();   // 幸存者是局内的
		GuardService.reset();   // 卫兵冷却与命中计数是局内的
		BallistaService.reset();   // 弩炮炮位与冷却表是局内的
		CatapultService.reset();   // 投石机炮位与冷却表是局内的
		RamService.reset();   // 撞角位置与冷却表是局内的
		PowerService.reset();   // 电量池是局内的
		dev.nbw.content.GeneratorBlock.reset();   // 发电机冷却表是局内的
		dev.nbw.content.RepairTableBlock.reset();   // 修理台冷却表是局内的
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
			// 状态机恢复只是第一步：anchors / lastParticipants / 各玩法 Service 全是运行时状态，
			// 不重建就会出现“phase=PLAY 但圈层未初始化、幸存者/电量/事件/帆表全空”的半死局。
			buildAnchors();
			lastParticipants.clear();
			lastParticipants.addAll(currentParticipants());
			restoredRuntimePending = true;
			restoredSurvivorsPending = true;
			if (machine.phase() == Phase.PLAY) {
				resumePlayServices();
			}
			NBW.LOGGER.info("[state] 玩法子系统恢复完成：anchors={} participants={} phase={}",
					anchors.size(), lastParticipants.size(), machine.phase());
		}
		NBW.LOGGER.info("[state] 已恢复到阶段 {}（match={}，阶段内 {} tick，参战 {} 人）",
				machine.phase(), machine.matchIndex(), machine.tickInPhase(),
				machine.snapshot(0).participantCount());
		return RoundStateStore.describe(load);
	}

	/**
	 * 对局中重启后重做一次“进入 PLAY”的运行时初始化。
	 *
	 * <p>快照只存回合状态机，不存圈层模型、物资列表、幸存者、电量、事件窗口、帆表/炮位表；
	 * 这些全是内存状态。恢复时把它们按当前阶段重新起一遍，比让对局带着半套系统继续跑要好。
	 * 能保住的部分（阶段秒数、队伍、席位）已经在 {@code machine.restore} 里恢复。
	 *
	 * <p>已知边界：筏心 HP、沉筏倒计时、弩炮弹药/冷却没有持久化，重启后回到初始值；
	 * 这一层要真正无损需要把对应状态写进快照，属于后续工作。
	 */
	private static void resumePlayServices() {
		MetricsService.begin(server);
		ZoneService.begin(server);
		MinimapService.begin(server);
		LootService.resume(server);
		SurvivalService.begin(server);
		EventService.begin(server);
		PowerService.begin(server);
		rebuildRestoredRegistriesIfReady();
	}

	/** 筏子载入后从 plot 重建帆/弩炮/投石机/撞角登记；筏还没载入时留给 20 tick 重试块。 */
	private static void rebuildRestoredRegistriesIfReady() {
		if (server == null) {
			return;
		}
		if (RaftSpawner.current().isEmpty()) {
			restoredRuntimePending = true;
			NBW.LOGGER.info("[state] 筏尚未载入，帆/设施/幸存者将在重认领后重建");
			return;
		}
		// 所有“有成员的队”都认出筏之后再重建：Sable 可能分两拍才把两艘筏载进来，
		// 只看到第一艘就重建会让第二队的帆/设施漏登记。
		for (var team : machine.teams()) {
			if (!team.members().isEmpty() && RaftSpawner.raftOf(team.index()) == null) {
				restoredRuntimePending = true;
				NBW.LOGGER.info("[state] 还有队未认出筏（队 {}），登记重建推迟到下一拍", team.index());
				return;
			}
		}
		if (restoredSurvivorsPending) {
			// 此刻筏已重认领、区块被 Sable 的持有票加载，spawn 的村民才找得到实体。
			SurvivorService.begin(server);
			restoredSurvivorsPending = false;
		}
		Sailing.rebuildFromWorld(server);
		BallistaService.rebuildFromWorld(server);
		CatapultService.rebuildFromWorld(server);
		RamService.rebuildFromWorld(server);
		restoredRuntimePending = false;
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
			f = ConfigService.loadFormat(root, fid, BuiltinConfigJson.SOURCE);
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
		ConfigService.LobbyLoaded lobbyLoaded = ConfigService.loadLobby(root, true, BuiltinConfigJson.SOURCE);
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
		LobbyReadyService.markManualJoin(player.getUuid());
		String err = machine.queue(player.getUuid(), player.getNameForScoreboard());
		if (err != null) {
			return err;
		}
		return team == null ? null : machine.assignTeam(player.getUuid(), team);
	}

	public static String quit(ServerPlayerEntity player) {
		LobbyReadyService.markManualQuit(player.getUuid());
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
		LobbyReadyService.markManualJoin(target.getUuid());   // 管理员指定 = 明确参加，清掉"不参加"
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

	/** 大厅配置（自动准备的"大厅范围"从这里取；未加载时为 null）。 */
	public static ConfigModels.Lobby lobbyOrNull() {
		return lobby;
	}

	/** 大厅所在世界（MVP 固定 overworld）。 */
	public static ServerWorld lobbyWorldPublic() {
		return lobbyWorld();
	}

	private static void announce(String message) {
		if (server == null) {
			return;
		}
		server.sendMessage(Text.literal(message));
		NBW.LOGGER.info("[round] broadcast: {}", message.replaceAll("§.", ""));
	}
}
