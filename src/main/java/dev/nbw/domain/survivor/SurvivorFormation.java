package dev.nbw.domain.survivor;

/**
 * 幸存者在筏面上的局部阵位（纯函数，零 Minecraft 依赖）。
 *
 * <p>甲板局部坐标以甲板中心为原点，甲板面在 {@code y=1}（与
 * {@code ClingRule.DECK_SURFACE_OFFSET}、{@code RaftLayout.FACILITY_Y} 同一口径）。
 * 4×4 初始筏的水平范围是 {@code [-2, 1]}，半径 {@link #RADIUS}=0.8 的圆完全落在甲板内，
 * 不会把村民放到船壳外面。
 *
 * <p>阵位只决定"站在哪"，不决定 AI：实体位置由 {@code SurvivorService} 每 tick 按筏的
 * 当前位姿换算回世界坐标，所以筏子移动、转向、下沉时幸存者都会跟着。
 */
public final class SurvivorFormation {

	/** 幸存者离甲板中心的水平半径（格）。必须小于 4×4 甲板的最短半边。 */
	public static final double RADIUS = 0.8;

	/** 幸存者脚底的甲板局部高度：甲板方块占 {@code [0,1)}，顶面是 {@code 1}。 */
	public static final double LOCAL_Y = 1.0;

	private SurvivorFormation() {
	}

	/**
	 * 第 {@code index} 名幸存者在 {@code total} 人阵型里的局部坐标。
	 *
	 * <p>{@code total <= 0} 时按 1 人处理，避免除零；调用方仍会拿到一个有限点。
	 *
	 * @return {@code {lx, ly, lz}}，单位为格
	 */
	public static double[] localOffset(int index, int total) {
		int n = Math.max(1, total);
		double angle = Math.PI * 2.0 * index / n;
		return new double[]{Math.cos(angle) * RADIUS, LOCAL_Y, Math.sin(angle) * RADIUS};
	}
}
