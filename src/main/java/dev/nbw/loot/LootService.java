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
 * 海面物资刷新（M3-2）：按 {@code arena.loot.prefill} 在环带里撒「漂浮补给箱」。
 *
 * <p>载体用原版 {@code minecraft:barrel}（箱形最像补给箱，且自带 loot table 容器），
 * 放在水面那一格（{@code sea.waterLevel}）——玩家游过去右键开箱。
 *
 * <p>环带品质留给后续：{@link ZoneModel#ringOf(double)} 已经能算"某距离属于哪一圈"，
 * 这里先把箱子撒进 {@code prefill.radiusBand}；每圈的 loot table 分化等物资表定稿再接。
 */
public final class LootService {

	/** 补给箱的 loot table：{@code data/nbw/loot_table/sea_crate.json}。 */
	private static final Identifier CRATE_TABLE = Identifier.of("nbw", "sea_crate");

	/** 本局已撒出的箱子位置（局后清理用；跨局不残留）。 */
	private static final List<BlockPos> spawned = new ArrayList<>();

	private LootService() {
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

	/** dev/命令入口：在 [minR, maxR] 环带里撒 {@code count} 个箱子。 */
	public static String spawn(MinecraftServer server, int count, int minR, int maxR) {
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		if (arena == null || arena.sea == null) {
			return "没有海域配置，撒不了箱子";
		}
		ServerWorld world = server.getOverworld();
		Random rnd = new Random(world.getRandom().nextLong());
		int cx = arena.sea.centerX;
		int cz = arena.sea.centerZ;
		int y = arena.sea.waterLevel;
		int ok = 0;
		for (int i = 0; i < count; i++) {
			double angle = rnd.nextDouble() * Math.PI * 2.0;
			double radius = minR + (maxR - minR) * rnd.nextDouble();
			int x = cx + (int) Math.round(Math.cos(angle) * radius);
			int z = cz + (int) Math.round(Math.sin(angle) * radius);
			BlockPos pos = new BlockPos(x, y, z);
			if (placeCrate(world, pos, rnd.nextLong())) {
				spawned.add(pos);
				ok++;
			}
		}
		NBW.LOGGER.info("[loot] 撒补给箱 {} 个（环带 {}-{} 格）", ok, minR, maxR);
		return String.format(Locale.ROOT, "已撒补给箱 %d 个（环带 %d-%d 格）", ok, minR, maxR);
	}

	/** 放一个带 loot table 的木桶；失败（区块没加载/位置被占）返回 false。 */
	private static boolean placeCrate(ServerWorld world, BlockPos pos, long seed) {
		if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
			return false;   // 不掉进未加载区块，交给下一轮
		}
		if (!world.getBlockState(pos).isReplaceable()) {
			return false;   // 已经有方块（岛/结构）就不覆盖
		}
		world.setBlockState(pos, Blocks.BARREL.getDefaultState(), 3);
		BlockEntity be = world.getBlockEntity(pos);
		if (be instanceof LootableInventory li) {
			li.setLootTable(RegistryKey.of(RegistryKeys.LOOT_TABLE, CRATE_TABLE), seed);
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
			BlockPos b = spawned.get(i);
			tail.add(b.getX() + "," + b.getY() + "," + b.getZ());
		}
		return String.format(Locale.ROOT, "[loot] 已撒箱子 %d 个｜最近 %s", spawned.size(), String.join(" ", tail));
	}

	/** 局后清理：箱子位置是局内状态；方块本身由下一局的地图重置/建海覆盖。 */
	public static void reset() {
		spawned.clear();
	}
}
