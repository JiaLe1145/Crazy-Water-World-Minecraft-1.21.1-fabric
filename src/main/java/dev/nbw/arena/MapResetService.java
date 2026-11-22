package dev.nbw.arena;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 模板复制式地图重置（2026-11-08，参照 RicordRailExpress 的 resetTemplateArea/resetPasteArea）。
 *
 * <p>参考项目是「把模板区的方块复制回贴合区」，我们的实现同样分两个区：
 * <b>模板区</b>（{@code resetTemplateArea}）是永久保留的“原始结构”，
 * <b>贴合区</b>（{@code resetPasteArea}）是每局实际使用的位置。
 * 开局时按 {@link #begin} 登记任务，之后每 tick 复制一批方块（{@link #onEndTick}），
 * 避免一次性写几千块把服务器卡住——这与参考项目的「逐 tick 跑重置任务」是同一思路。
 *
 * <p>与参考项目的差异：它默认只重置“会变的特殊方块”（马桶/门/床…），我们有 {@code mustCopy}
 * 但当前就是全量复制（结构小、没有需要单独维护的特殊方块）。{@code noReset} 跳过。
 */
public final class MapResetService {

	/** 每 tick 复制的方块数上限：8×8×8=512 一 tick 就完，更大的结构会分摊到几 tick。 */
	private static final int BLOCKS_PER_TICK = 8192;

	/** 进行中的复制任务；null = 空闲。 */
	private static @Nullable Task active;

	private MapResetService() {
	}

	/** 开局（READY）登记一次模板复制。没有配置 reset / noReset 时直接跳过。 */
	public static void begin(ServerWorld world, @Nullable ConfigModels.Arena arena) {
		active = null;
		if (arena == null || arena.reset == null) {
			return;
		}
		ConfigModels.Arena.Reset r = arena.reset;
		if (r.noReset) {
			NBW.LOGGER.info("[reset] 地图 {} 声明 noReset，跳过还原", arena.id);
			return;
		}
		if (r.resetTemplateArea == null || r.resetPasteArea == null) {
			NBW.LOGGER.warn("[reset] 地图 {} 的 reset 配置缺 template/paste 区，跳过", arena.id);
			return;
		}
		forceLoad(world, r.resetTemplateArea);
		forceLoad(world, r.resetPasteArea);
		active = new Task(world, r.resetTemplateArea, r.resetPasteArea);
		NBW.LOGGER.info("[reset] 模板复制开始 src={} dst={} 共 {} 块",
				fmt(r.resetTemplateArea), fmt(r.resetPasteArea), active.total());
	}

	/** 把一块区域的区块强制加载：建模板/复制都要求源区块已加载，否则 setblock 会落空。 */
	public static void forceLoad(ServerWorld world, @Nullable ConfigModels.Arena.Bounds b) {
		if (b == null) {
			return;
		}
		for (int cx = b.minX >> 4; cx <= b.maxX >> 4; cx++) {
			for (int cz = b.minZ >> 4; cz <= b.maxZ >> 4; cz++) {
				world.setChunkForced(cx, cz, true);
			}
		}
	}

	/** 每 tick 推进复制；任务完成或取消后自动清空。 */
	public static void onEndTick() {
		if (active == null) {
			return;
		}
		if (active.tick()) {
			NBW.LOGGER.info("[reset] 模板复制完成（{} 块）", active.copied);
			active = null;
		}
	}

	/** 一行读数（给 /cww reset status）。 */
	public static String status() {
		if (active == null) {
			return "[reset] 空闲";
		}
		return String.format(Locale.ROOT, "[reset] 复制中 %d/%d 块", active.copied, active.total());
	}

	/** 还有未完成的复制任务吗。开局入图闸门用它把"地图重置完"变成硬条件。 */
	public static boolean isRunning() {
		return active != null;
	}

	/** 局后/换局取消未完成的复制，避免旧任务写到新地图上。 */
	public static void cancel() {
		if (active != null) {
			NBW.LOGGER.info("[reset] 取消未完成的模板复制");
		}
		active = null;
	}

	private static String fmt(ConfigModels.Arena.Bounds b) {
		return String.format(Locale.ROOT, "(%d,%d,%d)..(%d,%d,%d)", b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
	}

	/** 块坐标按 x→y→z 顺序展开的复制任务。 */
	private static final class Task {
		private final ServerWorld world;
		private final int minX, minY, minZ;
		private final int sizeX, sizeY, sizeZ;
		private final int dx, dy, dz;
		private final int total;
		private int index;
		private int copied;

		Task(ServerWorld world, ConfigModels.Arena.Bounds src, ConfigModels.Arena.Bounds dst) {
			this.world = world;
			this.minX = src.minX;
			this.minY = src.minY;
			this.minZ = src.minZ;
			this.sizeX = src.maxX - src.minX + 1;
			this.sizeY = src.maxY - src.minY + 1;
			this.sizeZ = src.maxZ - src.minZ + 1;
			this.dx = dst.minX - src.minX;
			this.dy = dst.minY - src.minY;
			this.dz = dst.minZ - src.minZ;
			this.total = sizeX * sizeY * sizeZ;
		}

		int total() {
			return total;
		}

		/** @return true = 复制完成 */
		boolean tick() {
			int end = Math.min(index + BLOCKS_PER_TICK, total);
			for (; index < end; index++) {
				int x = index % sizeX;
				int y = (index / sizeX) % sizeY;
				int z = index / (sizeX * sizeY);
				BlockPos srcPos = new BlockPos(minX + x, minY + y, minZ + z);
				BlockPos dstPos = srcPos.add(dx, dy, dz);
				BlockState state = world.getBlockState(srcPos);
				world.setBlockState(dstPos, state, 3);   // 3 = 通知客户端 + 不触发邻块更新（结构复制够用）
				copied++;
			}
			return index >= total;
		}
	}
}
