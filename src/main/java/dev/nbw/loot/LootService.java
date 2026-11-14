package dev.nbw.loot;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.domain.ZoneModel;
import dev.nbw.domain.loot.LootExclusion;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.round.RoundManager;
import dev.nbw.sable.SableRafts;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 海面物资刷新（M3-2）：按 {@code arena.loot.prefill} 在环带里撒「漂浮补给箱」，并按
 * {@code format.loot.respawnIntervalSec} 定时补箱。
 *
 * <p>载体用原版 {@code minecraft:barrel}（箱形最像补给箱，且自带 loot table 容器），
 * 放在水面那一格（{@code sea.waterLevel}）——玩家游过去右键开箱。
 *
 * <p><b>环带品质</b>：{@link ZoneModel#ringOf(double)} 按到圈心的距离查出该点属于哪一圈，
 * 圈层的 {@code tier} 决定用哪张 loot table（t1 外圈基础 / t2 中圈含铁与刀 / t3 内圈含钻与重武器）。
 */
public final class LootService {

	/** 一个补给箱：位置 + 撒出时刻（用于 120s 后沉没）。 */
	private record Crate(BlockPos pos, int spawnedAt) {
	}

	/** 本局已撒出的箱子（局后清理用；跨局不残留）。 */
	private static final List<Crate> spawned = new ArrayList<>();

	/** 防蹲点：按 8×8 网格记「点位上一次被用的局号」，最近 2 轮用过的网格不再刷（01 §A4）。 */
	private static final java.util.Map<Long, Integer> cellLastRound = new java.util.HashMap<>();
	private static final int CELL_SIZE = 8;

	/**
	 * 补给箱离任意在场筏中心的最小水平距离（格）。
	 *
	 * <p>4×4 筏的半对角线约 2.8 格；取 4.0 留出余量。实测：预铺箱刷在筏角下方会把筏子
	 * 顶住——目标速度与施力都有、位置却不动，朝向还会倾斜（2026-11-14 test_sea 队1）。
	 */
	private static final double RAFT_CLEARANCE = 4.0;

	/** 定时补箱：每 {@code format.loot.respawnIntervalSec} 补 {@code format.loot.respawnBatch} 个（缺省 4，02 §4）。 */
	private static int RESPAWN_BATCH = 4;
	/** 同屏最大漂浮物（02 §4：quick 16 / standard 24 / big 32）；缺省 24。 */
	private static int MAX_FLOATING = 24;
	/** 漂浮物存活（02 §4：standard 120s），超时沉没。 */
	private static final int DESPAWN_TICKS = 120 * 20;
	private static int tick;
	private static int respawnIntervalTicks = 22 * 20;
	/** 本局的服务端引用：局后把本局箱子方块还原，避免跨局卡筏/累积。 */
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
		int cleared = clearNearRafts(server);
		if (cleared > 0) {
			NBW.LOGGER.info("[loot] 开局清掉 {} 个刷在筏体附近的旧补给箱", cleared);
		}
		String out = spawnPrefill(server);
		NBW.LOGGER.info("[loot] 开局：{}（补箱间隔 {}s / 每轮 {} 个 / 同屏上限 {}）", out, sec, batch, maxFloating);
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

	/** PLAY 每 tick：每秒清超时箱子（沉没），到点从整片海补几个。 */
	public static void onEndTick(MinecraftServer server) {
		if (RoundManager.machine().phase() != dev.nbw.domain.round.Phase.PLAY) {
			return;
		}
		tick++;
		if (tick % 20 == 0) {
			despawnOld(server);
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

	/** 超过存活时间的箱子沉没（还原成水），并从计数里移除。 */
	private static void despawnOld(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		int now = server.getTicks();
		java.util.Iterator<Crate> it = spawned.iterator();
		int removed = 0;
		while (it.hasNext()) {
			Crate c = it.next();
			if (now - c.spawnedAt() >= DESPAWN_TICKS) {
				if (world.getBlockState(c.pos()).isOf(Blocks.BARREL)) {
					world.setBlockState(c.pos(), Blocks.WATER.getDefaultState(), 3);
					removed++;
				}
				it.remove();
			}
		}
		if (removed > 0) {
			NBW.LOGGER.debug("[loot] 沉没 {} 个超时箱子（存活 {}, 剩 {}）", removed, DESPAWN_TICKS / 20, spawned.size());
		}
	}

	/** dev/命令入口：在 [minR, maxR] 环带里撒 {@code count} 个箱子。 */
	public static String spawn(MinecraftServer server, int count, int minR, int maxR) {
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		if (arena == null || arena.sea == null) {
			return "没有海域配置，撒不了箱子";
		}
		ServerWorld world = server.getOverworld();
		Random rnd = new Random(world.getRandom().nextLong());
		ZoneModel zone = RoundManager.zoneOrNull();
		int round = RoundManager.machine().matchIndex();
		int cx = arena.sea.centerX;
		int cz = arena.sea.centerZ;
		int y = arena.sea.waterLevel;
		int ok = 0;
		int attempts = 0;
		int skippedByAntiCamp = 0;
		int skippedByExclude = 0;
		int skippedByRaft = 0;
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
				skippedByRaft++;   // 箱子不能刷在筏底/筏旁，否则会把筏子顶住（2026-11-14 实测）
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
			BlockPos pos = new BlockPos(x, y, z);
			int tier = tierAt(zone, radius);
			if (placeCrate(world, pos, tier, legendary, rnd.nextLong())) {
				spawned.add(new Crate(pos, server.getTicks()));
				cellLastRound.put(cell, round);
				ok++;
			}
		}
		NBW.LOGGER.info("[loot] 撒补给箱 {} 个（环带 {}-{} 格，防蹲点跳过 {} 次，排除区跳过 {} 次，靠近筏体跳过 {} 次）",
				ok, minR, maxR, skippedByAntiCamp, skippedByExclude, skippedByRaft);
		return String.format(Locale.ROOT, "已撒补给箱 %d 个（环带 %d-%d 格）", ok, minR, maxR);
	}

	/**
	 * 开局清掉任意在场筏中心 4 格内的旧补给箱。
	 *
	 * <p>为什么不能只靠 {@link #tooCloseToRaft}：那个只拦“新刷”的候选点；上一局/崩溃留下的
	 * barrel 是实体方块，`reset()` 不会清它，下一局继续顶住筏子。2026-11-14 test_sea 队1
	 * 就是被旧箱子顶住的。
	 *
	 * @return 实际清掉的箱子数
	 */
	private static int clearNearRafts(MinecraftServer server) {
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
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
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

	/** 放一个补给箱：按 tier 抽稀有度、填 1–3 件进 barrel；失败（区块没加载/位置被占）返回 false。 */
	private static boolean placeCrate(ServerWorld world, BlockPos pos, int tier, boolean legendaryUnlocked,
			long seed) {
		if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
			return false;   // 不掉进未加载区块，交给下一轮
		}
		if (!world.getBlockState(pos).isReplaceable()) {
			return false;   // 已经有方块（岛/结构）就不覆盖
		}
		world.setBlockState(pos, Blocks.BARREL.getDefaultState(), 3);
		BlockEntity be = world.getBlockEntity(pos);
		if (!(be instanceof net.minecraft.inventory.Inventory inv)) {
			return false;
		}
		Random rnd = new Random(seed);
		int count = 1 + rnd.nextInt(3);   // 02 §4：单点 1–3 件
		for (int i = 0; i < count; i++) {
			var rarity = dev.nbw.domain.loot.LootRoll.rollRarity(tier, legendaryUnlocked, rnd.nextDouble());
			var item = dev.nbw.domain.loot.LootRoll.rollItem(rarity, rnd.nextDouble());
			int amount = item.min() + rnd.nextInt(Math.max(1, item.max() - item.min() + 1));
			var id = Identifier.tryParse(item.id());
			if (id == null) {
				continue;
			}
			var mcItem = net.minecraft.registry.Registries.ITEM.get(id);
			inv.setStack(rnd.nextInt(Math.max(1, inv.size())), new ItemStack(mcItem, amount));
		}
		return true;
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

	/** 一行读数：给 {@code /cww loot status}（带最近几个位置，方便探针核对）。 */
	public static String status() {
		if (spawned.isEmpty()) {
			return "[loot] 已撒箱子 0 个";
		}
		List<String> tail = new ArrayList<>();
		for (int i = Math.max(0, spawned.size() - 3); i < spawned.size(); i++) {
			BlockPos b = spawned.get(i).pos();
			tail.add(b.getX() + "," + b.getY() + "," + b.getZ());
		}
		return String.format(Locale.ROOT, "[loot] 已撒箱子 %d 个（上限 %d，存活 %ds）｜最近 %s",
				spawned.size(), MAX_FLOATING, DESPAWN_TICKS / 20, String.join(" ", tail));
	}

	/**
	 * 局后清理：把本局撒出的箱子方块还原成水，再清列表。
	 *
	 * <p>不能只清列表：`reset()` 后记忆丢失，下一局 `clearNearRafts` 也找不到它们；
	 * 残留在水面的 barrel 会继续卡筏（2026-11-14 test_sea 实测）或占用同屏上限。
	 */
	public static void reset() {
		if (server != null && !spawned.isEmpty()) {
			ServerWorld world = server.getOverworld();
			int removed = 0;
			for (Crate c : spawned) {
				if (world.getBlockState(c.pos()).isOf(Blocks.BARREL)) {
					world.setBlockState(c.pos(), Blocks.WATER.getDefaultState(), 3);
					removed++;
				}
			}
			if (removed > 0) {
				NBW.LOGGER.info("[loot] 局后还原 {} 个补给箱方块", removed);
			}
		}
		spawned.clear();
		tick = 0;
		server = null;
	}
}
