package dev.nbw.domain.loot;

/**
 * 海面漂浮物的水面运动（2026-11-25 新增）：整片海共用一条洋流，箱子只在起伏相位上不同。
 *
 * <p><b>为什么速度必须是"共享"的</b>：产品口径是「所有箱子漂动方向相同」。如果每个箱子各自
 * 随机方向，玩家会看到箱子互相穿插、也没有"被海流推着走"的体感；如果连速度也各不相同，
 * 同批刷出来的箱子会散成一团。所以水平速度只由 {@link #velocity(double)} 给出——不接收相位，
 * 所有箱子拿到的矢量完全一致。相位（{@code phase}）只参与 {@link #bob} / {@link #rollDeg} /
 * {@link #pitchDeg}，也就是只影响"摇"和"起伏"，不影响"往哪走"。
 *
 * <p><b>纯函数</b>：不碰 Minecraft 类型，直接单测"同向"与"速度有界"这两条硬约束。
 */
public final class FlotsamMotion {

	/**
	 * 洋流方向：{@code (+X, 0)} = 东。
	 *
	 * <p>改这两个数就是改整片海的漂移方向；箱子之间的相对关系不变。
	 */
	public static final double FLOW_X = 1.0;
	public static final double FLOW_Z = 0.0;

	/**
	 * 洋流基准速度（格/秒）。
	 *
	 * <p>0.30 → 一分钟漂 18 格、一个 120s 生命周期漂 36 格：盯着看能看出在动，
	 * 又不至于一转眼跑出视野。再快就不像"漂浮物"，像"会游的箱子"。
	 */
	public static final double FLOW_SPEED_PER_SECOND = 0.30;

	/** 阵性：同一条洋流统一的 ±12% 速度起伏（所有箱子同步，不破坏"同向"）。 */
	public static final double GUST_RATIO = 0.12;
	/** 阵性周期（tick）。 */
	public static final double GUST_PERIOD_TICKS = 8 * 20.0;

	/** 上下起伏幅度（格）：只改观感，不改水面线（箱子仍以水面为基准）。 */
	public static final double BOB_AMPLITUDE = 0.06;
	/** 上下起伏周期（tick）：约 2.2s 一个来回，接近真实涌浪。 */
	public static final double BOB_PERIOD_TICKS = 44.0;

	/** 横滚幅度（度）与周期：比纵摇大，看起来像被侧向涌浪推。 */
	public static final double ROLL_DEG = 4.5;
	public static final double ROLL_PERIOD_TICKS = 61.0;
	/** 纵摇幅度（度）与周期。 */
	public static final double PITCH_DEG = 2.0;
	public static final double PITCH_PERIOD_TICKS = 37.0;

	private static final double TICKS_PER_SECOND = 20.0;
	private static final double TWO_PI = Math.PI * 2.0;

	private FlotsamMotion() {
	}

	/**
	 * 当前这一 tick 的水平位移（格/tick），{@code [dx, dz]}。
	 *
	 * <p>不接收相位——这是"所有箱子同向同速"的实现点，改签名前先看类注释。
	 */
	public static double[] velocity(double ticks) {
		double speed = speedPerTick(ticks);
		double len = Math.hypot(FLOW_X, FLOW_Z);
		if (len <= 1.0e-9) {
			return new double[]{0.0, 0.0};   // 配成 (0,0) 就是"不漂"，不能除以 0
		}
		return new double[]{FLOW_X / len * speed, FLOW_Z / len * speed};
	}

	/** 本 tick 沿洋流方向的速率（格/tick，已含阵性起伏）。 */
	public static double speedPerTick(double ticks) {
		double gust = 1.0 + GUST_RATIO * Math.sin(ticks * TWO_PI / GUST_PERIOD_TICKS);
		return FLOW_SPEED_PER_SECOND / TICKS_PER_SECOND * gust;
	}

	/** 相对水面线的上下偏移（格）；{@code phase} 让每个箱子错过波峰。 */
	public static double bob(double ticks, double phase) {
		return BOB_AMPLITUDE * Math.sin(ticks * TWO_PI / BOB_PERIOD_TICKS + phase);
	}

	/** 横滚角（度）。渲染用；正负号在客户端按右手系套 X/Z 轴。 */
	public static double rollDeg(double ticks, double phase) {
		return ROLL_DEG * Math.sin(ticks * TWO_PI / ROLL_PERIOD_TICKS + phase * 1.7);
	}

	/** 纵摇角（度）。 */
	public static double pitchDeg(double ticks, double phase) {
		return PITCH_DEG * Math.sin(ticks * TWO_PI / PITCH_PERIOD_TICKS + phase * 0.9);
	}

	/**
	 * 洋流的方位名（给 {@code /cww loot status} 和日志）：东 / 东南 / 南 / …
	 *
	 * <p>MC 坐标：+X 东、+Z 南。所以 8 方位按 atan2(z, x) 的 45° 分档展开。
	 */
	public static String flowLabel() {
		String[] names = {"东", "东南", "南", "西南", "西", "西北", "北", "东北"};
		double deg = Math.toDegrees(Math.atan2(FLOW_Z, FLOW_X));
		int index = (int) Math.round(((deg % 360.0) + 360.0) % 360.0 / 45.0) % 8;
		return names[index];
	}
}
