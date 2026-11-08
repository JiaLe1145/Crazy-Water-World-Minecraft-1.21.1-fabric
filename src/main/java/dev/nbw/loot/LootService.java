package dev.nbw.loot;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.domain.ZoneModel;
import dev.nbw.round.RoundManager;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
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

	/** 三档物资表：data/nbw/loot_table/sea_crate_t{1,2,3}.json。 */
	private static final Identifier TABLE_T1 = Identifier.of("nbw", "sea_crate_t1");
	private static final Identifier TABLE_T2 = Identifier.of("nbw", "sea_crate_t2");
	private static final Identifier TABLE_T3 = Identifier.of("nbw", "sea_crate_t3");

	/** 一个补给箱：位置 + 撒出时刻（用于 120s 后沉没）。 */
	private record Crate(BlockPos pos, int spawnedAt) {
	}

	/** 本局已撒出的箱子（局后清理用；跨局不残留）。 */
	private static final List<Crate> spawned = new ArrayList<>();

	/** 防蹲点：按 8×8 网格记「点位上一次被用的局号」，最近 2 轮用过的网格不再刷（01 §A4）。 */
	private static final java.util.Map<Long, Integer> cellLastRound = new java.util.HashMap<>();
	private static final int CELL_SIZE = 8;

	/** 定时补箱：每 {@code format.loot.respawnIntervalSec} 补 {@code format.loot.respawnBatch} 个（缺省 4，02 §4）。 */
	private static int RESPAWN_BATCH = 4;
	/** 同屏最大漂浮物（02 §4：quick 16 / standard 24 / big 32）；缺省 24。 */
	private static int MAX_FLOATING = 24;
	/** 漂浮物存活（02 §4：standard 120s），超时沉没。 */
	private static final int DESPAWN_TICKS = 120 * 20;
	private static int tick;
	private static int respawnIntervalTicks = 22 * 20;

	private LootService() {
	}

	/**
	 * PLAY 开始：读本局补箱间隔 + 按 prefill 撒首批箱子。
	 */
	public static void begin(MinecraftServer server) {
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
		int maxAttempts = Math.max(count * 4, count);
		while (ok < count && attempts < maxAttempts) {
			if (spawned.size() >= MAX_FLOATING) {
				break;   // 同屏上限（02 §4）：满了就不再撒
			}
			attempts++;
			double angle = rnd.nextDouble() * Math.PI * 2.0;
			double radius = minR + (maxR - minR) * rnd.nextDouble();
			int x = cx + (int) Math.round(Math.cos(angle) * radius);
			int z = cz + (int) Math.round(Math.sin(angle) * radius);
			long cell = cellKey(x, z);
			Integer usedRound = cellLastRound.get(cell);
			if (usedRound != null && usedRound >= round - 2) {
				skippedByAntiCamp++;
				continue;   // 防蹲点：最近两轮同一点位已刷过，换地方
			}
			BlockPos pos = new BlockPos(x, y, z);
			Identifier table = tableForTier(tierAt(zone, radius));
			if (placeCrate(world, pos, table, rnd.nextLong())) {
				spawned.add(new Crate(pos, server.getTicks()));
				cellLastRound.put(cell, round);
				ok++;
			}
		}
		NBW.LOGGER.info("[loot] 撒补给箱 {} 个（环带 {}-{} 格，防蹲点跳过 {} 次）", ok, minR, maxR, skippedByAntiCamp);
		return String.format(Locale.ROOT, "已撒补给箱 %d 个（环带 %d-%d 格）", ok, minR, maxR);
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

	private static Identifier tableForTier(int tier) {
		return switch (tier) {
			case 3 -> TABLE_T3;
			case 2 -> TABLE_T2;
			default -> TABLE_T1;
		};
	}

	/** 放一个带 loot table 的木桶；失败（区块没加载/位置被占）返回 false。 */
	private static boolean placeCrate(ServerWorld world, BlockPos pos, Identifier table, long seed) {
		if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
			return false;   // 不掉进未加载区块，交给下一轮
		}
		if (!world.getBlockState(pos).isReplaceable()) {
			return false;   // 已经有方块（岛/结构）就不覆盖
		}
		world.setBlockState(pos, Blocks.BARREL.getDefaultState(), 3);
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof LootableInventory li) {
			li.setLootTable(RegistryKey.of(RegistryKeys.LOOT_TABLE, table), seed);
			return true;
		}
		return false;
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

	/** 局后清理：箱子位置是局内状态；方块本身由下一局的地图重置/建海覆盖。 */
	public static void reset() {
		spawned.clear();
		tick = 0;
	}
}
