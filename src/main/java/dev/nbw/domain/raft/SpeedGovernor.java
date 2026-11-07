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
	 * 筏级位移速度上限（格/tick）。0.35 = 7 格/秒，是<b>船体</b>的属性，帆与桨共用。
	 *
	 * <p><b>它的位置是算出来的，不是拍的（常态风 2.0）：</b>每块帆请求 {@code 0.025×2 = 0.05}
	 * 格/tick，所以 6 帆 = 0.30 仍在限内、<b>7 帆 = 0.35 正好贴着保险丝</b>；
	 * 划桨每人 0.0333，满编 5 人 0.167（3.3 格/秒）远在线内。
	 * 于是"帆多更快"有效到甲板排满，"人多更快"有效到人数上限 —— 没有任何一档被提前压平。
	 *
	 * <p>风暴（风强 4.0）时 5 帆的请求是 0.5，会被夹到 0.35：顺风满帆确实是天花板，
	 * 但天花板只在配满 + 大风时碰到，符合"顺风满帆"的设计意图。
	 *
	 * <p><b>成套约束：</b>稳态需要 {@code dv ≈ 2×位移速度}（实测阻尼率 k≈2 /tick），
	 * 所以 0.35 这一档追到 90% 时要 dv≈0.63，必须低于 {@link #MAX_DV_PER_TICK}。
	 * 抬上限不抬限幅，上限就成了摆设（之前取 0.02 的限幅就亲手造出过这个假象）。
	 */
	public static final double MAX_TRAVEL_SPEED = 0.35;

	/**
	 * 一名划桨者的目标位移速度：0.0333 格/tick ≈ 2/3 格/秒。
	 *
	 * <p><b>02 §6 原表折算值（1/3 格/秒）的 2 倍</b>，是用户"适当提高速度"的结果，
	 * 不再是原表的逐字翻译；原表之所以低到几乎看不出动，是因为离散范式下
	 * "2 格 / 6 秒"本来就是"划一次挪两格"，而不是持续巡航速度。
	 * 满编 4~5 人 ≈ 2.7~3.3 格/秒（玩家步行 4.3 格/秒），横穿 {@code default_sea}
	 * （280 格）约 1.5 分钟 —— 手划仍然是最慢最可靠的那条路，但看得见在走。
	 */
	public static final double ROW_SPEED_PER_ROWER = 0.0333;

	/**
	 * 一块帆在单位风强下的目标位移速度：0.025 格/tick。
	 *
	 * <p>1 块帆在常态风（2.0）下是 0.05 格/tick = 1 格/秒，与单桨的 2/3 格/秒恰好 3:2 ——
	 * 原表"木桨 2 格 : 帆 3 格"的比例放大后仍然成立。
	 * 风强线性乘上去：风暴（4.0）时一块帆就是 2 格/秒，静风仍是 0
	 * （帆不是发动机，这条从 M2 开始没变过）。
	 */
	public static final double SAIL_SPEED_PER_SAIL_PER_WIND = 0.025;

	/**
	 * 遥控器的马达目标位移速度：0.1665 格/tick ≈ 3.33 格/秒。
	 *
	 * <p>问题③（2026-11-07）：木浆操控改为每筏一个遥控器。用户把马达速度定为
	 * <b>对齐满编划桨档</b>（原来 5 人满编 = 5 × {@link #ROW_SPEED_PER_ROWER} = 0.1665），
	 * 所以从"多人划桨"换成"一人遥控"后航行手感不变。船帆的顺风推进在它之上叠加，
	 * 两者都被 {@link #MAX_TRAVEL_SPEED} 夹住。
	 */
	public static final double REMOTE_SPEED = 0.1665;

	/**
	 * 比例增益。取 <b>18</b>，依据是"持续施力"那一档实测出来的阻尼。
	 *
	 * <p><b>只认这条口径：</b>每 tick 施加的速度增量 {@code dv = GAIN×(目标 − 实测)}，
	 * 而稳态实测位移只有 dv 的一半左右 —— 由"4 人档 dv≈0.066、实测位移 0.0338 格/tick"
	 * 反推得等效阻尼率 {@code k≈2 /tick}。纯比例控制的稳态解因此是
	 * <pre>X/T = G/(G + k) = G/(G + 2)</pre>
	 * G=2 只能到目标的 50%，G=18 是 90% —— 现在闭环的残差就落在这 10% 上。
	 *
	 * <p><b>为什么现在敢用这么大的增益，而上一次 GAIN=20 把服务端搞崩了：</b>
	 * 那次<b>没有斜率限制</b>，启动一 tick 的误差等于整个目标值，dv 冲到 1.34 格/tick
	 * （27 格/秒），筏子被甩出海城、离水之后水阻消失，8.4 秒飞出 1475 格。
	 * 现在有 {@link #MAX_DV_PER_TICK} 把瞬态顶住，增益只决定"多快追上去"，
	 * 不再决定"最多能被推多快"。
	 *
	 * <p><b>一个我没解释清楚但必须记下的矛盾：</b>用 {@code /sable physics impulse} 给<b>单次</b>
	 * 冲量、再按位姿量衰减，拟合出的阻尼率只有 0.11~0.15 /tick（J=1 走 0.01 格、J=4 走 0.07 格），
	 * 与持续施力档的 ≈2 /tick 差一个数量级。本控制器每 tick 施力，所以按<b>持续档</b>配平；
	 * 单次冲量那条路为什么衰减那么慢我没查出来 —— 别拿那个数当 k 用（任务 #40）。
	 */
	public static final double GAIN = 18.0;

	/**
	 * 每 tick 允许施加的速度增量硬顶（格/tick）。0.8 ≈ 瞬时 16 格/秒的一拍。
	 *
	 * <p>存在的唯一理由就是那次 1475 格的甩飞：启动那一 tick 误差等于整个目标值，
	 * 纯比例会算出把筏子甩出海城的冲量。有了它，系统只能"逐步追上去"，
	 * 水阻与海域边界都还在原来的位置，行为可预期。
	 *
	 * <p>取值必须 {@code ≥ 2×最大目标速度}（上限 0.35 ⇒ 至少 0.7），否则真正的天花板是这个限幅、
	 * 不是 {@link #MAX_TRAVEL_SPEED}，档位会从某档起集体失效 —— 之前 0.02 的限幅就制造过
	 * "4 人满编只有 0.2 格/秒"这种看不出原因的失效。
	 *
	 * <p>它同时也是大筏的安全线：冲量 = 质量 × 这个增量，质量由 {@code SableRafts.mass} 实时读，
	 * 所以筏子多大都不会被一 tick 推飞。
	 */
	public static final double MAX_DV_PER_TICK = 0.8;

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
		// （GAIN=20 那次实测 8.4 秒飞了 1475 格）。限住斜率之后系统只能逐步追上去。
		return mass * Math.min(error * GAIN, MAX_DV_PER_TICK);
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
