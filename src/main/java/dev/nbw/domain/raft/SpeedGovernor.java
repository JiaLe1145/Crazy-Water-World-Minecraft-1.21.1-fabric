package dev.nbw.domain.raft;

/**
 * 航速控制器（#39 的修法）：把"航速"定义在<b>真实位移</b>上，而不是每 tick 施力后的峰值读数上。
 *
 * <p><b>为什么必须换口径。</b>我们每 tick 调一次 {@code applyLinearImpulse}，
 * {@code SableRafts.linearVelocity} 读回来的是施力那一瞬的峰值，而 Sable 的水阻
 * （{@code buoyancy.rs} 的 {@code do_drag}）在同一个 tick 内就把绝大部分速度吃掉 ——
 * 实测满员划桨峰值 6.41 格/秒而实际只走 0.29 格/秒，差约 22 倍
 * （{@code docs/dev/M2-RESULTS.md} §3.10/§3.11）。02 §6 与 P-01 写的"几格/秒"
 * 是玩家能看见的位移速度；拿峰值去对档位，等于所有航行数值都在自说自话。
 *
 * <p><b>控制律刻意保持笨。</b>只有一个比例项：目标位移速度减去<b>沿请求方向的实测位移速度</b>，
 * 乘质量与增益得到冲量；实测已经超过目标就一句力都不施，<b>不倒车刹车</b>
 * （要减速是玩法的事，不该由航行控制器偷偷做）。好处是数值可控、
 * 目标是把同规格筏的航速方差压到 P-01 要求的 5% 以内（<b>这条还没实测验过，见任务 #40</b>）；
 * 代价是每 tick 要读一次位姿 —— 只是读，不写。
 *
 * <p><b>档位现在是 02 §6 原表的 2 倍</b>（用户 2026-11-06 指示"适当提高速度"）：
 * 原表折算只有单桨 1/3 格/秒、1 帆 1/2 格/秒，实测下来画面里几乎看不出筏子在动。
 * 抬的时候刻意<b>连同筏级上限一起抬</b>，好让"帆多更快"一路有效到 7 帆、
 * "人多更快"一路有效到满编，而不是提前撞在一个低天花板上被削平。
 * 桨:帆的单档比仍是原表的 2:3（放大两边都不变）。
 */
public final class SpeedGovernor {

	/**
	 * 筏级位移速度上限（格/tick）。0.22 = 4.4 格/秒，是<b>船体</b>的属性，帆与桨共用。
	 *
	 * <p><b>2026-11-22 用户反馈"速度过快、移动不平稳、木筏会翻倒入水"后下调</b>：
	 * 原值 0.35（7 格/秒）在 7 帆 + 风暴下实测能跑到 0.33 格/tick（6.6 格/秒），
	 * 而客户端每 tick 一步就是 0.33 格 —— 看上去像在跳，转向时倾斜也明显。
	 * 现在 0.22 对应 4.4 格/秒（与玩家步行 4.3 格/秒同量级），单步位移 ≤0.22 格。
	 *
	 * <p>成套约束：稳态需要 {@code dv ≈ 2×位移速度}（实测阻尼率 k≈2 /tick），
	 * 所以 0.22 这一档追到 90% 时要 dv≈0.4，必须低于 {@link #MAX_DV_PER_TICK}。
	 * 抬上限不抬限幅，上限就成了摆设（之前取 0.02 的限幅就亲手造出过这个假象）。
	 */
	public static final double MAX_TRAVEL_SPEED = 0.22;

	/**
	 * 一名划桨者的目标位移速度：0.022 格/tick ≈ 0.44 格/秒。
	 *
	 * <p><b>2026-11-22 随上限一起下调</b>（原 0.0333）。满编 5 人 ≈ 2.2 格/秒，
	 * 仍然"看得见在走"，但不再比步行还快地擦着水面飞。
	 */
	public static final double ROW_SPEED_PER_ROWER = 0.022;

	/**
	 * 一块帆在单位风强下的目标位移速度：0.0165 格/tick。
	 *
	 * <p><b>2026-11-22 随上限一起下调</b>（原 0.025）。常态风 2.0 下一块帆 0.033 格/tick
	 * （0.64 格/秒），7 帆满配 + 常态风 = 0.231，正好越过 {@link #MAX_TRAVEL_SPEED}；
	 * 风暴 4.0 时 3 帆就顶到上限 —— 顺风满帆仍是天花板，但天花板本身降下来了。
	 */
	public static final double SAIL_SPEED_PER_SAIL_PER_WIND = 0.0165;

	/**
	 * 遥控器的马达目标位移速度：0.11 格/tick ≈ 2.2 格/秒。
	 *
	 * <p>问题③（2026-11-07）：木浆操控改为每筏一个遥控器，速度对齐满编划桨档。
	 * <b>2026-11-22 下调</b>（原 0.1665 = 3.33 格/秒）：用户反馈开起来太快、不稳。
	 * 现在 2.2 格/秒，与 5 人划桨档一致，仍比"漂"明显快。
	 */
	public static final double REMOTE_SPEED = 0.11;

	/**
	 * 比例增益。取 <b>10</b>（2026-11-22 由 18 下调）。
	 *
	 * <p><b>只认这条口径：</b>每 tick 施加的速度增量 {@code dv = GAIN×(目标 − 实测)}，
	 * 而稳态实测位移只有 dv 的一半左右 —— 由实测反推的等效阻尼率 {@code k≈2 /tick}。
	 * 纯比例控制的稳态解是 {@code X/T = G/(G + k)}：G=10 → 83%，G=18 → 90%。
	 * 少掉的那 7% 用"更少过冲"换：G=18 时启动与转向的瞬态会把筏子甩得点头，
	 * 用户反馈的"移动不平稳"就有它一份。
	 *
	 * <p><b>为什么敢把增益和限幅一起降：</b>真正决定"能不能被推飞"的是
	 * {@link #MAX_DV_PER_TICK}，增益只决定追得多快；两者一起降之后，
	 * 瞬态更软、稳态残差稍大 —— 对"靠风慢慢漂"的玩法，这是划算的交换。
	 */
	public static final double GAIN = 10.0;

	/**
	 * 每 tick 允许施加的速度增量<b>相对目标</b>的倍数：{@code dv ≤ 1.8 × 目标}。
	 *
	 * <p><b>2026-11-22 的关键改动（用户："速度过快、移动不平稳、会翻倒"）。</b>
	 * 原来只有一个绝对硬顶（0.8），而所有档位的目标（0.11~0.22）都远小于它，
	 * 于是<b>限幅永远先撞上</b>，实际航速由"限幅 ÷ 水阻"决定、与档位无关 ——
	 * 遥控、划桨、帆全都被推成同一个速度（实测 0.33 格/tick ≈ 6.6 格/秒），
	 * 而且每 tick 都是一记猛踢。
	 *
	 * <p>改成相对目标之后：低速档（遥控 0.11）每 tick 只加 0.198，高速档（满帆 0.22）
	 * 加 0.396 —— 各档回到自己的目标上，加速是"3~4 拍爬上去"的小坡，不再是猛踢。
	 * 稳态仍满足 {@code v ≈ 0.9×目标}（阻尼率 k≈2）：{@code 1.8/2 = 0.9}。
	 */
	public static final double DV_CAP_RATIO = 1.8;

	/**
	 * 每 tick 速度增量的绝对上限（格/tick），给"目标本身异常大"的情况兜底。
	 *
	 * <p>0.45 已经高于 {@code 1.8 × MAX_TRAVEL_SPEED = 0.396}，所以正常情况下
	 * 起作用的是 {@link #DV_CAP_RATIO}；这条只防"目标值被算成天文数字"时的一记猛踢
	 * （历史事故：GAIN=20 且无限幅时，筏子 8.4 秒飞了 1475 格）。
	 */
	public static final double MAX_DV_PER_TICK = 0.45;

	/** 这一 tick 对给定目标允许的最大速度增量。 */
	public static double dvCap(double target) {
		if (Double.isNaN(target) || target <= 0.0) {
			return 0.0;
		}
		return Math.min(MAX_DV_PER_TICK, target * DV_CAP_RATIO);
	}

	private SpeedGovernor() {
	}

	/** 目标速度是否越界（0 表示停桨/停帆，合法）。 */
	public static double clampSpeed(double speed) {
		if (Double.isNaN(speed) || speed <= 0.0) {
			return 0.0;
		}
		return Math.min(speed, MAX_TRAVEL_SPEED);
	}

	/** n 名划桨者的目标位移速度（格/tick），已夹到上限。 */
	public static double rowTarget(int rowers) {
		return clampSpeed(rowers * ROW_SPEED_PER_ROWER);
	}

	/** n 块帆在给定风强下的目标位移速度（格/tick），已夹到上限。 */
	public static double sailTarget(int sails, double windStrength) {
		return clampSpeed(sails * SAIL_SPEED_PER_SAIL_PER_WIND * windStrength);
	}

	/**
	 * 沿请求方向的实测位移速度（格/tick）。
	 *
	 * <p>用点积而不是取模长：反向漂移必须读成<b>负数</b>，否则"被水流顶着倒退"会被
	 * 当成"已经在前进"，控制器就不再出力，筏子永远追不上目标。
	 * 任何一分量是 NaN（筏刚被卸载、位姿取不到）都返回 0，当作静止处理而不是把 NaN 传进冲量。
	 */
	public static double actualAlong(double dx, double dz, double dirX, double dirZ) {
		if (Double.isNaN(dx) || Double.isNaN(dz) || Double.isNaN(dirX) || Double.isNaN(dirZ)) {
			return 0.0;
		}
		return dx * dirX + dz * dirZ;
	}

	/**
	 * 这一 tick 该施的冲量大小（单位与 {@code SableRafts.push} 一致：力×1 tick）。
	 *
	 * @param mass 筏的质量（{@code SableRafts.mass}）；冲量要乘质量才能换算成速度增量
	 * @param actualAlong 沿请求方向的实测位移速度
	 * @param target 目标位移速度（调用方已经夹过上限）
	 * @return 冲量；{@code <= 0} 表示这一 tick 什么都不施
	 */
	public static double impulse(double mass, double actualAlong, double target) {
		if (target <= 0.0 || Double.isNaN(target) || Double.isNaN(mass) || mass <= 0.0) {
			return 0.0;   // 质量读不出来（筏不在场）时绝不能施力：NaN 会一路污染到刚体速度
		}
		double error = target - actualAlong;
		if (error <= 0.0) {
			return 0.0;   // 已经追上或超过目标：不倒车刹车
		}
		// 限幅是必须的：启动那一 tick 误差等于整个目标值，纯比例会算出把筏子甩出海城的冲量
		// （GAIN=20 那次实测 8.4 秒飞了 1475 格）。现在限幅**按目标速度成比例**，
		// 低速档不会被推成高速，高速档也不会被压成低速（见 DV_CAP_RATIO 的注释）。
		return mass * Math.min(error * GAIN, dvCap(target));
	}

	/**
	 * 把多个动力源的<b>目标速度矢量</b>合成一个（矢量和，再夹上限）。
	 *
	 * <p>为什么要矢量合成而不是各推各的：两股方向相反的力各推各的会互相抵消到抖，
	 * 而"实测沿合方向"只有一个数，控制器只能对这一个数收敛。
	 * 抵消关系仍然保留 —— 方向相反的目标矢量先相加，合速度自然变小。
	 *
	 * @return {@code {dirX, dirZ, speed}} —— 已带上速率的目标速度矢量 + 合速率；
	 *         合速率为 0 时返回全零，调用方据此跳过本 tick
	 */
	public static double[] combine(double ax, double az, double aSpeed,
			double bx, double bz, double bSpeed) {
		double vx = ax * aSpeed + bx * bSpeed;
		double vz = az * aSpeed + bz * bSpeed;
		double mag = Math.hypot(vx, vz);
		if (Double.isNaN(mag) || mag <= 1e-9) {
			return new double[]{0.0, 0.0, 0.0};
		}
		double capped = clampSpeed(mag);
		return new double[]{vx / mag * capped, vz / mag * capped, capped};
	}
}
