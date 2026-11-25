package dev.nbw.loot;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.domain.ZoneModel;
import dev.nbw.domain.loot.FlotsamMotion;
import dev.nbw.domain.loot.LootExclusion;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.round.RoundManager;
import dev.nbw.sable.SableRafts;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 海面物资刷新（M3-2）：按 {@code arena.loot.prefill} 在环带里撒「漂浮补给箱」，并按
 * {@code format.loot.respawnIntervalSec} 定时补箱。
 *
 * <p><b>载体</b>（2026-11-25 改）：{@link FloatingCrate} 实体，贴着 {@code sea.waterLevel}
 * 水面线做起伏，并随全图共享洋流漂移（{@link FlotsamMotion}）。旧实现是
 * {@code minecraft:barrel} 方块——方块没有起伏、不会漂，看起来是"插在水里的桶"，所以换掉。
 * 外观仍是 barrel 模型，右键仍开 3×9 箱子界面，物资抽卡逻辑未变。
 *
 * <p><b>同向约束</b>：水平速度由 {@link FlotsamMotion#velocity(double)} 唯一给出，不带每箱随机量；
 * 否则"所有箱子漂动方向相同"就守不住。箱子之间只在起伏相位上不同。
 *
 * <p><b>环带品质</b>：{@link ZoneModel#ringOf(double)} 按到圈心的距离查出该点属于哪一圈，
 * 圈层的 {@code tier} 决定用哪张 loot table（t1 外圈基础 / t2 中圈含铁与刀 / t3 内圈含钻与重武器）。
 */
public final class LootService {

	/** 本局撒出的箱子（局后清理 + 同屏上限；沉没由实体自己按寿命做）。 */
	private static final List<FloatingCrate> spawned = new ArrayList<>();

	/** 防蹲点：按 8×8 网格记「点位上一次被用的局号」，最近 2 轮用过的网格不再刷（01 §A4）。 */
	private static final java.util.Map<Long, Integer> cellLastRound = new java.util.HashMap<>();
	private static final int CELL_SIZE = 8;

	/**
	 * 补给箱离任意在场筏中心的最小水平距离（格）。
	 *
	 * <p>4×4 筏的半对角线约 2.8 格；取 4.0 留出余量。旧方块版实测：预铺箱刷在筏角下方会把筏子
	 * 顶住——目标速度与施力都有、位置却不动，朝向还会倾斜（2026-11-14 test_sea 队1）。
	 * 实体版不再顶住筏子，但"箱子贴在筏边"依然难看，所以保留这个间距。
	 */
	private static final double RAFT_CLEARANCE = 5.0;

	/** 定时补箱：每 {@code format.loot.respawnIntervalSec} 补 {@code format.loot.respawnBatch} 个（缺省 4，02 §4）。 */
	private static int RESPAWN_BATCH = 4;
	/** 同屏最大漂浮物（02 §4：quick 16 / standard 24 / big 32）；缺省 24。 */
	private static int MAX_FLOATING = 24;
	/** 存活 120s（02 §4 standard），超时下沉——寿命在实体里，本类只用于读数。 */
	private static final int DESPAWN_TICKS = FloatingCrate.LIFETIME_TICKS;
	private static int tick;
	private static int respawnIntervalTicks = 22 * 20;
	/** 本局的服务端引用：局后把本局箱子清掉，避免跨局累积。 */
	private static MinecraftServer server;

	private LootService() {
	}

	/**
	 * PLAY 开始：读本局补箱间隔 + 按 prefill 撒首批箱子。
	 */
	public static void begin(MinecraftServer server) {
		LootService.server = server;
		ConfigModels.Format f = RoundManager.formatOrNull();
		int sec = f != null && f.loot != null && f.loot.respawnIntervalSec > 0 ? f.loot.respawnIntervalSec : 22;
		int batch = f != null && f.loot != null && f.loot.respawnBatch != null && f.loot.respawnBatch > 0
				? f.loot.respawnBatch : 4;
		int maxFloating = f != null && f.loot != null && f.loot.maxFloating != null && f.loot.maxFloating > 0
				? f.loot.maxFloating : 24;
		respawnIntervalTicks = sec * 20;
		RESPAWN_BATCH = batch;
		MAX_FLOATING = maxFloating;
		tick = 0;
		spawned.clear();   // 上一局的箱子列表不能算进本局同屏上限
		ServerWorld world = server.getOverworld();
		int strays = discardStrays(world);   // 上一局/崩溃残留的箱子实体：新一局从干净的水面开始
		int cleared = clearLegacyBarrels(server);
		if (strays > 0 || cleared > 0) {
			NBW.LOGGER.info("[loot] 开局清场：旧箱子实体 {} 个、旧方块箱子 {} 个", strays, cleared);
		}
		String out = spawnPrefill(server);
		NBW.LOGGER.info("[loot] 开局：{}（补箱间隔 {}s / 每轮 {} 个 / 同屏上限 {} / 洋流 {} {} 格/秒）",
				out, sec, batch, maxFloating, FlotsamMotion.flowLabel(),
				String.format(Locale.ROOT, "%.2f", FlotsamMotion.FLOW_SPEED_PER_SECOND));
	}

	/**
	 * 重启恢复：服务端重启后不重新预铺（水面上的箱子还在世界里），
	 * 只恢复计时/补箱配置，并把已加载的海面箱子重新纳入计数与同屏上限。
	 *
	 * <p>箱子实体随区块存档保存，{@code spawnedAt} 也在 NBT 里，所以"寿命"能跨重启继续算；
	 * 这里要做的是把"本局箱子表"补回来，否则同屏上限与局后清理都会漏掉它们。
	 */
	public static void resume(MinecraftServer server) {
		LootService.server = server;
		ConfigModels.Format f = RoundManager.formatOrNull();
		int sec = f != null && f.loot != null && f.loot.respawnIntervalSec > 0 ? f.loot.respawnIntervalSec : 22;
		int batch = f != null && f.loot != null && f.loot.respawnBatch != null && f.loot.respawnBatch > 0
				? f.loot.respawnBatch : 4;
		int maxFloating = f != null && f.loot != null && f.loot.maxFloating != null && f.loot.maxFloating > 0
				? f.loot.maxFloating : 24;
		respawnIntervalTicks = sec * 20;
		RESPAWN_BATCH = batch;
		MAX_FLOATING = maxFloating;
		tick = 0;
		spawned.clear();
		spawned.addAll(scanCrates(server.getOverworld()));
		int cleared = clearLegacyBarrels(server);
		NBW.LOGGER.info("[loot] 重启恢复：接管水面已有箱子 {} 个（旧方块箱子清掉 {} 个），补箱计时从 0 开始",
				spawned.size(), cleared);
	}

	/**
	 * 开局撒箱子：读 {@code arena.loot.prefill}，在 radiusBand 里按数量随机布点。
	 *
	 * @return 一行结论（给日志/命令）
	 */
	public static String spawnPrefill(MinecraftServer server) {
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		if (arena == null || arena.sea == null) {
			return "没有海域配置，撒不了箱子";
		}
		ConfigModels.Arena.Loot loot = arena.loot;
		if (loot == null || loot.prefill == null || !loot.prefill.enabled) {
			return "本图未启用 loot.prefill";
		}
		int count = Math.max(0, loot.prefill.count);
		List<Integer> band = loot.prefill.radiusBand;
		int minR = band != null && band.size() >= 2 ? band.get(0) : 0;
		int maxR = band != null && band.size() >= 2 ? band.get(1) : arena.sea.radius;
		return spawn(server, count, minR, Math.min(maxR, arena.sea.radius));
	}

	/** PLAY 每 tick：每秒清一次表（沉没由实体自己做），到点从整片海补几个。 */
	public static void onEndTick(MinecraftServer server) {
		if (RoundManager.machine().phase() != dev.nbw.domain.round.Phase.PLAY) {
			return;
		}
		tick++;
		if (tick % 20 == 0) {
			prune();
		}
		if (tick % respawnIntervalTicks != 0) {
			return;
		}
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		if (arena == null || arena.sea == null) {
			return;
		}
		int radius = arena.sea.radius;
		String out = spawn(server, RESPAWN_BATCH, 0, radius);
		NBW.LOGGER.debug("[loot] 定时补箱：{}", out);
	}

	/** dev/命令入口：在 [minR, maxR] 环带里撒 {@code count} 个箱子。 */
	public static String spawn(MinecraftServer server, int count, int minR, int maxR) {
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		if (arena == null || arena.sea == null) {
			return "没有海域配置，撒不了箱子";
		}
		ServerWorld world = server.getOverworld();
		prune();
		Random rnd = new Random(world.getRandom().nextLong());
		ZoneModel zone = RoundManager.zoneOrNull();
		int round = RoundManager.machine().matchIndex();
		int cx = arena.sea.centerX;
		int cz = arena.sea.centerZ;
		double surfaceY = arena.sea.waterLevel + 0.5;   // 旧 barrel 方块的中心 = 现在的水面基准线
		int ok = 0;
		int attempts = 0;
		int skippedByAntiCamp = 0;
		int skippedByExclude = 0;
		int skippedByRaft = 0;
		int skippedByWater = 0;
		int maxAttempts = Math.max(count * 4, count);
		boolean legendary = legendaryUnlocked();
		List<LootExclusion.Rect> excluded = new ArrayList<>();
		if (arena.loot != null && arena.loot.exclude != null) {
			for (ConfigModels.Arena.Loot.Exclude e : arena.loot.exclude) {
				excluded.add(new LootExclusion.Rect(e.minX, e.minZ, e.maxX, e.maxZ));
			}
		}
		while (ok < count && attempts < maxAttempts) {
			if (spawned.size() >= MAX_FLOATING) {
				break;   // 同屏上限（02 §4）：满了就不再撒
			}
			attempts++;
			double angle = rnd.nextDouble() * Math.PI * 2.0;
			double radius = minR + (maxR - minR) * rnd.nextDouble();
			int x = cx + (int) Math.round(Math.cos(angle) * radius);
			int z = cz + (int) Math.round(Math.sin(angle) * radius);
			if (tooCloseToRaft(world, x, z)) {
				skippedByRaft++;   // 箱子不能刷在筏底/筏旁（旧方块版会顶住筏子，实体版只是难看）
				continue;
			}
			if (LootExclusion.contains(x, z, excluded)) {
				skippedByExclude++;   // 01 §5 / 03 loot.exclude：不刷物资区严格排除
				continue;
			}
			long cell = cellKey(x, z);
			Integer usedRound = cellLastRound.get(cell);
			if (usedRound != null && usedRound >= round - 2) {
				skippedByAntiCamp++;
				continue;   // 防蹲点：最近两轮同一点位已刷过，换地方
			}
			if (!world.isChunkLoaded(x >> 4, z >> 4)) {
				continue;   // 不掉进未加载区块，交给下一轮
			}
			double spawnX = x + 0.5;
			double spawnZ = z + 0.5;
			if (!FloatingCrate.canFloatAt(world, spawnX, surfaceY, spawnZ)) {
				skippedByWater++;   // 该点这一格不是水（岸边/结构）：箱子浮不住，换点
				continue;
			}
			FloatingCrate crate = FloatingCrate.spawn(world, spawnX, surfaceY, spawnZ,
					world.getTime(), rnd.nextDouble() * Math.PI * 2.0);
			if (crate == null) {
				continue;   // 入世界失败（区块竞态）：这轮跳过，不计入已撒
			}
			int tier = tierAt(zone, radius);
			fillCrate(crate, tier, legendary, rnd.nextLong());
			spawned.add(crate);
			cellLastRound.put(cell, round);
			ok++;
		}
		NBW.LOGGER.info("[loot] 撒补给箱 {} 个（环带 {}-{} 格，防蹲点跳过 {} 次，排除区跳过 {} 次，靠近筏体跳过 {} 次，非水面跳过 {} 次）",
				ok, minR, maxR, skippedByAntiCamp, skippedByExclude, skippedByRaft, skippedByWater);
		return String.format(Locale.ROOT, "已撒补给箱 %d 个（环带 %d-%d 格，%s漂流）", ok, minR, maxR,
				FlotsamMotion.flowLabel());
	}

	/** 把已沉没/被移除的箱子从本局表里剔掉，让同屏上限按"真正还在漂的"算。 */
	private static void prune() {
		spawned.removeIf(FloatingCrate::isRemoved);
	}

	/**
	 * 清掉任意在场筏中心 5 格内**旧方块版**留下的 barrel。
	 *
	 * <p>旧实现把箱子做成 {@code minecraft:barrel} 方块；那些方块是实心的，会把筏子顶住，
	 * 而且上一局/崩溃留下的不会自己消失。新实现已不写方块，这里只为兼容旧存档世界。
	 *
	 * @return 实际清掉的方块数
	 */
	private static int clearLegacyBarrels(MinecraftServer server) {
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		if (arena == null || arena.sea == null) {
			return 0;
		}
		ServerWorld world = server.getOverworld();
		int y = arena.sea.waterLevel;
		int cleared = 0;
		for (var entry : RaftSpawner.current().entrySet()) {
			double[] p = SableRafts.position(world, entry.getValue());
			if (Double.isNaN(p[0]) || Double.isNaN(p[2])) {
				continue;
			}
			int cx = (int) Math.floor(p[0]);
			int cz = (int) Math.floor(p[2]);
			int r = (int) Math.ceil(RAFT_CLEARANCE);
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.hypot(cx + dx + 0.5 - p[0], cz + dz + 0.5 - p[2]) >= RAFT_CLEARANCE) {
						continue;
					}
					BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
					if (world.getBlockState(pos).isOf(Blocks.BARREL)) {
						world.setBlockState(pos, Blocks.WATER.getDefaultState(), 3);
						cleared++;
					}
				}
			}
		}
		return cleared;
	}

	/** 候选点是否离任意在场筏太近；筏不在场/读不到位姿时跳过该筏，不阻止刷新。 */
	private static boolean tooCloseToRaft(ServerWorld world, int x, int z) {
		for (var entry : RaftSpawner.current().entrySet()) {
			double[] p = SableRafts.position(world, entry.getValue());
			if (Double.isNaN(p[0]) || Double.isNaN(p[2])) {
				continue;
			}
			if (Math.hypot(x + 0.5 - p[0], z + 0.5 - p[2]) < RAFT_CLEARANCE) {
				return true;
			}
		}
		return false;
	}

	/** 8×8 网格的键：防蹲点只需"这一片最近刷过没"，不需要精确坐标。 */
	private static long cellKey(int x, int z) {
		return ((long) Math.floorDiv(x, CELL_SIZE) << 32) | (Math.floorDiv(z, CELL_SIZE) & 0xFFFFFFFFL);
	}

	/** 距离落在哪一圈，就用那一圈的品质（圈层 tier 1..3）；没有圈层模型时退到 t1。 */
	private static int tierAt(ZoneModel zone, double distance) {
		if (zone == null) {
			return 1;
		}
		ZoneModel.Ring ring = zone.ringOf(distance);
		return ring == null ? 1 : Math.max(1, Math.min(3, ring.tier()));
	}

	/** 给箱子装物资：按 tier 抽稀有度，设施/工具单独掷骰，填 1–3 件。 */
	private static void fillCrate(FloatingCrate crate, int tier, boolean legendaryUnlocked, long seed) {
		Random rnd = new Random(seed);
		int count = 1 + rnd.nextInt(3);   // 02 §4：单点 1–3 件
		int facilityItems = 0;
		var inv = crate.getInventory();
		for (int i = 0; i < count; i++) {
			// 设施/工具独立掷骰：不能塞进稀有度池，否则武器掉率会被摊薄（见 LootRoll.FACILITY_*）
			boolean facility = dev.nbw.domain.loot.LootRoll.facilityRoll(rnd.nextDouble());
			dev.nbw.domain.loot.LootRoll.Item item;
			if (facility) {
				item = dev.nbw.domain.loot.LootRoll.rollFacility(tier, rnd.nextDouble());
				facilityItems++;
			} else {
				var rarity = dev.nbw.domain.loot.LootRoll.rollRarity(tier, legendaryUnlocked, rnd.nextDouble());
				item = dev.nbw.domain.loot.LootRoll.rollItem(rarity, rnd.nextDouble());
			}
			int amount = item.min() + rnd.nextInt(Math.max(1, item.max() - item.min() + 1));
			Identifier id = Identifier.tryParse(item.id());
			if (id == null) {
				continue;
			}
			var mcItem = net.minecraft.registry.Registries.ITEM.get(id);
			inv.setStack(rnd.nextInt(Math.max(1, inv.size())), new ItemStack(mcItem, amount));
		}
		if (facilityItems > 0) {
			NBW.LOGGER.debug("[loot] 补给箱 {} 含 {} 件设施/工具", BlockPos.ofFloored(crate.getPos()), facilityItems);
		}
	}

	/** 海域范围（略放宽）：扫箱子实体用，只覆盖已加载区块里的实体查找。 */
	private static Box arenaBox() {
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		if (arena == null || arena.sea == null) {
			return null;
		}
		int margin = 16;
		double r = arena.sea.radius + margin;
		double minY = arena.sea.waterLevel - 24.0;
		double maxY = arena.sea.waterLevel + 32.0;
		return new Box(arena.sea.centerX - r, minY, arena.sea.centerZ - r,
				arena.sea.centerX + r, maxY, arena.sea.centerZ + r);
	}

	/** 已加载区块里、属于本图的全部箱子实体（重启接管 / 局后清理用）。 */
	private static List<FloatingCrate> scanCrates(ServerWorld world) {
		Box box = arenaBox();
		if (box == null) {
			return List.of();
		}
		return world.getEntitiesByClass(FloatingCrate.class, box, crate -> !crate.isRemoved());
	}

	/** 清掉本图范围内所有箱子实体（上一局残留），返回个数。 */
	private static int discardStrays(ServerWorld world) {
		int removed = 0;
		for (FloatingCrate crate : scanCrates(world)) {
			crate.discard();
			removed++;
		}
		return removed;
	}

	/** 是否已过 P3（Legendary 解锁门槛）：取圈表第 3 段的触发秒数。 */
	private static boolean legendaryUnlocked() {
		ZoneModel zone = RoundManager.zoneOrNull();
		if (zone == null || zone.stages().size() < 3) {
			return false;
		}
		int p3At = zone.stages().get(2).atSecond();
		return RoundManager.machine().tickInPhase() / 20 >= p3At;
	}

	/** 一行读数：给 {@code /cww loot status}（带最近几个位置 + 最新箱子的 UUID，方便探针核对）。 */
	public static String status() {
		prune();
		if (spawned.isEmpty()) {
			return String.format(Locale.ROOT, "[loot] 已撒箱子 0 个（洋流 %s %s 格/秒，锚定水面 waterLevel+0.5）｜last=-",
					FlotsamMotion.flowLabel(),
					String.format(Locale.ROOT, "%.2f", FlotsamMotion.FLOW_SPEED_PER_SECOND));
		}
		List<String> tail = new ArrayList<>();
		for (int i = Math.max(0, spawned.size() - 3); i < spawned.size(); i++) {
			FloatingCrate crate = spawned.get(i);
			tail.add(String.format(Locale.ROOT, "%.1f,%.2f,%.1f", crate.getX(), crate.getY(), crate.getZ()));
		}
		// last=<uuid>：箱子会漂，坐标不再是稳定标识；探针按 UUID 取 data get entity 才不会抓错箱
		String last = spawned.get(spawned.size() - 1).getUuidAsString();
		return String.format(Locale.ROOT,
				"[loot] 已撒箱子 %d 个（上限 %d，存活 %ds，洋流 %s %s 格/秒）｜最近 %s｜last=%s",
				spawned.size(), MAX_FLOATING, DESPAWN_TICKS / 20, FlotsamMotion.flowLabel(),
				String.format(Locale.ROOT, "%.2f", FlotsamMotion.FLOW_SPEED_PER_SECOND),
				String.join(" ", tail), last);
	}

	/**
	 * 局后清理：把本局箱子实体全部丢弃，再清表。
	 *
	 * <p>不能只清列表：`reset()` 后记忆丢失，下一局 `begin` 的扫场也扫不到"没被接管"的箱子；
	 * 残留在水面的箱子会继续占同屏上限。所以这里按实体表 + 海域扫描双保险。
	 */
	public static void reset() {
		if (server != null) {
			int removed = 0;
			for (FloatingCrate crate : spawned) {
				if (!crate.isRemoved()) {
					crate.discard();
					removed++;
				}
			}
			int strays = discardStrays(server.getOverworld());
			if (removed + strays > 0) {
				NBW.LOGGER.info("[loot] 局后清掉补给箱 {} 个（表内 {} + 扫场 {}）", removed + strays, removed, strays);
			}
		}
		spawned.clear();
		tick = 0;
		server = null;
	}
}
