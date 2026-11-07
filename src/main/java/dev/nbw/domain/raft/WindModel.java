package dev.nbw.domain.raft;

/**
 * 风对帆的作用模型（M2-5）。纯函数，零依赖。
 *
 * <p><b>为什么风要我们自己算，而不是塞进 Sable 的气动里。</b>
 * Sable 的翼型公式（{@code BlockSubLevelLiftProvider.sable$contributeLiftAndDrag}）
 * 只用"方块处相对空气的速度"，而空气速度就是零——它整个维度物理里
 * 只有 {@code basePressure}（随 Y 衰减的标量）与 {@code gravity}，<b>没有任何风向矢量</b>。
 * 想让它有风，就得改它那段翼型计算，也就是 fork Sable。
 * 本项目对 Sable 的纪律是"只读源码、不改源码"，所以风放在我们这侧。
 *
 * <p>好处不只是不 fork：风是我们自己的量，于是
 * 天气事件（事件池里的风暴/顺风）、圈层内的风场差异、以及"同局同风"的公平性保证，
 * 全都能直接表达，不用绕物理库。
 *
 * <p>模型刻意保持朴素：帆的法向只能轴对齐（Sable 用 {@code Vec3.atLowerCornerOf(dir.getNormal())}），
 * 所以一块帆的贡献就是 <b>法向 × max(0, 风·法向)</b>。
 * 负值一律截到 0——帆不会把筏子往后吸，背风的那面本来就不受力。
 */
public final class WindModel {

	/**
	 * 单块帆在单位风强下的推力。<b>现在它只是无量纲换算系数</b>：
	 * {@code |合力| / FORCE_PER_SAIL} 得到的就是"迎风帆数 × 风强"，
	 * 那才是 {@link SpeedGovernor} 要的档位输入。
	 *
	 * <p>历史包袱要说清：0.2 这个数当年是拿"峰值读数"标出来的（1.0 时 3 帆读到 1.546 格/tick，
	 * 除以 5 收到 0.31）。#39 之后航行数值已经搬到<b>位移</b>口径上，
	 * 这个系数不再决定快慢，只决定合力方向的大小比例——留着是为了 {@code 合力=} 读数不失真。
	 * （≈30.9 格/秒）—— 横穿 {@code default_sea} 的 280 格只要 9 秒，12 分钟的局根本没有节奏可言。
	 * Sable 的水阻是 {@code ∝浸没体积×速度}，所以终端速度随每帆推力线性变化；
	 * 从 1.0 除以 5 得到 0.2，对应 3 帆 ≈ 0.31 格/tick（≈6.2 格/秒，横穿约 45 秒）。
	 */
	public static final double FORCE_PER_SAIL = 0.2;

	/** 风强上限。超过它，一局里的加速度会盖过桨与水流，风变成唯一答案。 */
	public static final double MAX_STRENGTH = 4.0;

	/**
	 * "迎风帆数 × 风强"的无量纲量：把各朝向帆的迎风贡献加起来。
	 *
	 * <p>为什么用合力反推而不是重写一套计数：{@link #fleetThrust} 已经把
	 * "背风的帆不受力"和"两面反向的帆互相抵消"算对了，再写一遍迟早两边不一致。
	 *
	 * @return 0 表示这股风推不动这组帆（静风、或全背风）
	 */
	public static double windUnits(int northSouth, int southNorth, int eastWest, int westEast, Wind wind) {
		double[] f = fleetThrust(northSouth, southNorth, eastWest, westEast, wind);
		double mag = Math.hypot(f[0], f[1]);
		if (Double.isNaN(mag) || FORCE_PER_SAIL <= 0.0) {
			return 0.0;
		}
		return mag / FORCE_PER_SAIL;
	}

	/**
	 * 一阵风：水平方向上的单位矢量 × 强度。
	 *
	 * @param dirX 东西分量（-1..1）
	 * @param dirZ 南北分量（-1..1，Minecraft 里 +Z 是南）
	 * @param strength 0 = 无风
	 */
	public record Wind(double dirX, double dirZ, double strength) {

		public static Wind none() {
			return new Wind(0.0, 0.0, 0.0);
		}

		/** 方向矢量长度不校验：调用方给单位矢量，这里只做乘法，不擅自归一化掩盖错误。 */
		public double forceX() {
			return dirX * strength;
		}

		public double forceZ() {
			return dirZ * strength;
		}

		public boolean calm() {
			return strength <= 0.0;
		}
	}

	private WindModel() {
	}

	/**
	 * 一组帆受到的总推力。
	 *
	 * @param normalX 帆的法向 X 分量（轴对齐：只能是 -1/0/1）
	 * @param normalZ 帆的法向 Z 分量
	 * @param sails   帆的块数
	 * @return 长度 2 的数组 {推力X, 推力Z}
	 */
	public static double[] thrustOn(double normalX, double normalZ, int sails, Wind wind) {
		if (sails <= 0 || wind == null || wind.calm()) {
			return new double[]{0.0, 0.0};
		}
		// 迎风分量：只有风"吹向帆的正面"才有力，背面不受力，所以截负值
		double along = wind.forceX() * normalX + wind.forceZ() * normalZ;
		if (along <= 0.0) {
			return new double[]{0.0, 0.0};
		}
		double scale = along * sails * FORCE_PER_SAIL;
		return new double[]{normalX * scale, normalZ * scale};
	}

	/**
	 * 一队全部帆的合力。不同朝向的帆会互相抵消——这是有意的：
	 * 把帆乱转 90° 装上去不该白得动力。
	 *
	 * @param northSouth 朝北的帆数
	 * @param southNorth 朝南的帆数
	 * @param eastWest   朝东的帆数
	 * @param westEast   朝西的帆数
	 */
	public static double[] fleetThrust(int northSouth, int southNorth, int eastWest, int westEast, Wind wind) {
		double x = 0.0;
		double z = 0.0;
		// Minecraft：north = -Z，south = +Z，east = +X，west = -X
		double[] n = thrustOn(0.0, -1.0, northSouth, wind);
		x += n[0]; z += n[1];
		double[] s = thrustOn(0.0, 1.0, southNorth, wind);
		x += s[0]; z += s[1];
		double[] e = thrustOn(1.0, 0.0, eastWest, wind);
		x += e[0]; z += e[1];
		double[] w = thrustOn(-1.0, 0.0, westEast, wind);
		x += w[0]; z += w[1];
		return new double[]{x, z};
	}

	/**
	 * 风强是否越界。
	 * 单独抽出来是因为运维会想直接设一个很大的数看效果，而那会让风吃掉其它一切动力来源。
	 */
	public static boolean strengthInRange(double strength) {
		return strength >= 0.0 && strength <= MAX_STRENGTH;
	}

	/**
	 * 风在给定水平方向上的<b>顺风分量</b>（单位仍是风强，0..{@link #MAX_STRENGTH}）。
	 *
	 * <p>问题③（2026-11-07）：船帆方向永远跟随前进方向。所以帆不再按"方块朝哪"算受力，
	 * 而是按"风在筏的前进方向上有多少分量"算——顺风满额、横风为零、逆风截到 0（帆不把筏子往后吸）。
	 * {@code fwdX/fwdZ} 是单位矢量（{@code SableRafts.forward} 已经归一化）。
	 */
	public static double tailwindAlong(Wind wind, double fwdX, double fwdZ) {
		if (wind == null || wind.calm()) {
			return 0.0;
		}
		double dot = wind.forceX() * fwdX + wind.forceZ() * fwdZ;
		return Math.max(0.0, dot);
	}
}
