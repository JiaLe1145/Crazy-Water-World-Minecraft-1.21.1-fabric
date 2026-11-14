package dev.nbw.domain.arena;

import java.util.List;

/**
 * 海域障碍（03 §3.2 的 `hazards`：暗礁/残骸）的航行减速规则。
 *
 * <p>设计口径：障碍<b>不阻断</b>航行，只让筏子变慢，逼玩家绕行或承担被追上的风险。
 * 当前最小实现是“筏心落在障碍矩形内 → 控制器目标速度 ×0.5”；矩形沿用 arena 的
 * <b>半开区间</b>约定 {@code [min,max)}。0.5 是起始配平值，后续可按实测调整。
 *
 * <p>纯函数：不依赖 Minecraft，也不依赖配置类；适配层负责把
 * {@code ConfigModels.Arena.Hazard} 翻成 {@link Zone}。
 */
public final class HazardRule {

	/** 筏子落在障碍内时，目标位移速度的倍率（起始配平值）。 */
	public static final double SLOW_MULTIPLIER = 0.5;

	/** 一个障碍区（方块坐标，半开区间）。{@code type} 目前不参与倍率，保留给后续分化。 */
	public record Zone(String type, int minX, int minZ, int maxX, int maxZ) {

		public boolean contains(double x, double z) {
			return x >= minX && x < maxX && z >= minZ && z < maxZ;
		}
	}

	private HazardRule() {
	}

	/** 该点所在障碍的航速倍率；不在任何障碍内返回 1.0。 */
	public static double speedMultiplier(List<Zone> zones, double x, double z) {
		for (Zone zone : zones) {
			if (zone.contains(x, z)) {
				return SLOW_MULTIPLIER;
			}
		}
		return 1.0;
	}
}
