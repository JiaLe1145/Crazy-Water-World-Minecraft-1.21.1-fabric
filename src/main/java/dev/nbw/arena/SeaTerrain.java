package dev.nbw.arena;

import dev.nbw.cfg.ConfigModels;
import dev.nbw.domain.SpawnGeometry;
import dev.nbw.domain.arena.SeaAudit;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 海域地形的**只读**采样：把 arena 配置要求的坐标逐列读出来，交给 {@link SeaAudit} 判定。
 *
 * <p>这一层刻意不含任何写操作 —— 地形由地图作者（或随包发布的地形 function）负责，
 * 模组只回答一句"你要求的这片海，世界上到底有没有"。
 */
public final class SeaTerrain {

	private SeaTerrain() {
	}

	/**
	 * 采样点 = 圈心 + 每队实际落点。
	 * 每队的落点必须与 {@code RoundManager.buildAnchors()} 同一套口径（手写点压过等角自动点），
	 * 否则审计的是"配置里那张图"，玩家被扔进的却是另一个坐标。
	 */
	public static List<SeaAudit.Reading> sample(ServerWorld world, ConfigModels.Arena arena, int teamCount) {
		List<SeaAudit.Expect> expects = expects(arena, teamCount);
		List<SeaAudit.Reading> readings = new ArrayList<>();
		for (SeaAudit.Expect e : expects) {
			readings.add(new SeaAudit.Reading(e, readColumn(world, e)));
		}
		return readings;
	}

	/** 圈心 + 各队锚点，与建锚逻辑同序；单独抽出来好让日志与命令共用。 */
	public static List<SeaAudit.Expect> expects(ConfigModels.Arena arena, int teamCount) {
		ConfigModels.Arena.Sea sea = arena.sea;
		List<SeaAudit.Expect> out = new ArrayList<>();
		out.add(new SeaAudit.Expect(sea.centerX, sea.centerZ, "圈心", sea.waterLevel));
		List<SpawnGeometry.Spot> auto = SpawnGeometry.equidistant(sea.centerX, sea.centerZ, sea.radius,
				Math.max(1, teamCount), SpawnGeometry.DEFAULT_RADIUS_FACTOR);
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
				out.add(new SeaAudit.Expect(manual.x, manual.z, "队" + i, sea.waterLevel));
			} else {
				SpawnGeometry.Spot spot = auto.get(i);
				out.add(new SeaAudit.Expect(spot.x(), spot.z(), "队" + i, sea.waterLevel));
			}
		}
		return out;
	}

	/** 一列：先确认区块到位，再从上往下找水面，最后找水底下的第一块实体方块。 */
	public static SeaAudit.Column readColumn(ServerWorld world, SeaAudit.Expect e) {
		// create=false：审计只读，不能因为"想看看这里有没有海"就把区块生成出来
		if (world.getChunk(e.x() >> 4, e.z() >> 4, ChunkStatus.FULL, false) == null) {
			return SeaAudit.Column.notLoaded();
		}
		int top = e.airCeiling();
		int bottom = e.floorTop() - SeaAudit.FLOOR_DEPTH - 2;
		int waterY = -1;
		for (int y = top; y >= bottom; y--) {
			FluidState fluid = world.getFluidState(new BlockPos(e.x(), y, e.z()));
			if (fluid.isIn(FluidTags.WATER)) {
				waterY = y;
				break;
			}
		}
		int floorY = -1;
		for (int y = waterY - 1; y >= bottom; y--) {
			BlockState state = world.getBlockState(new BlockPos(e.x(), y, e.z()));
			// 空气和液体都不算"海床"：isAir() 对水是 false，不排液体的话第一格水会被读成地板，
			// 刚建好的海反而被报成"海床不符"
			if (!state.isAir() && state.getFluidState().isEmpty()) {
				floorY = y;
				break;
			}
		}
		return new SeaAudit.Column(true, waterY, floorY);
	}
}
