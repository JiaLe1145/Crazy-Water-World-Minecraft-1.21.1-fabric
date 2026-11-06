package dev.nbw.domain.raft;

/**
 * 航速控制器（#39 的修法）：把"航速"定义在<b>真实位移</b>上，而不是每 tick 施力后的峰值读数上。
 *
 * <p><b>为什么必须换口径。</b>我们每 tick 调一次 {@code applyLinearImpulse}，
 * {@code SableRafts.linearVelocity} 读回来的是施力那一瞬的峰值，而 Sable 的水阻
 * （{@code buoyancy.rs} 的 {@code do_drag}）在同一个 tick 内就把绝大部分速度吃掉 ——
 * 实测满员划桨峰值 6.41 格/秒而实际只走 0.29 格/秒，差约 22 倍
 * （{@code docs/dev/M2-RESULTS.md} §3.10）。02 §6 与 P-01 的档位写的都是"几格/秒"，
 * 那是玩家能看见的位移速度；拿峰值去对档位，等于所有航行数值都在自说自话。
 *
 * <p><b>控制律刻意保持笨。</b>只有一个比例项：目标位移速度减去<b>沿请求方向的实测位移速度</b>，
 * 乘质量与增益得到冲量；实测已经超过目标就一句力都不施，<b>不倒车刹车</b>
 * （要减速是玩法的事，不该由航行控制器偷偷做）。这样做的好处是数值可控、
 * 同规格筏的航速方差能压到 P-01 要求的 5% 以内，代价是每 tick 要读一次位姿 —— 只是读，不写。
 *
 * <p><b>档位数值口径全部来自 02 §6 原表，只是把"位移 N 格 / 冷却 6 秒"翻译成持续速度：</b>
 * 木桨 2÷6 ≈ 0.333 格/秒，帆 3÷6 = 0.5 格/秒（那是风强 2.0 档的表现），单档比仍是原表的 2:3。
 * 上限 5 格/秒刻意高于任何正常配置（7 帆 + 风暴也只要 0.35 格/tick 的请求），
 * 所以它是<b>保险丝</b>而不是天花板：不会把"帆多更快、人多更快"压平。
 * 按这套数，7 帆常态风横穿 {@code default_sea}（280 格）约 2.7 分钟，12 分钟的局能跑好几趟；
 * 满编 4 人划桨约 3.5 分钟一趟——手划本来就慢，这正是"全靠手"的设计意图。
 */
public final class SpeedGovernor {

	/**
	 * 筏级位移速度上限（格/tick）。0.25 = 5 格/秒，是<b>船体</b>的属性，帆与桨共用。
	 *
	 * <p>刻意留得比任何正常配置都高，让它真的只是保险丝：7 块帆 + 风暴风强 4.0 也才
	 * {@code 0.0125×7×4 = 0.35} 格/tick 的<b>请求</b>，被夹到 0.25；划桨要 15 个人才碰得到。
	 * 于是"帆多更快""人多更快"在整个可装配范围内都成立，不会被配平压平。
	 */
	public static final double MAX_TRAVEL_SPEED = 0.25;

	/**
	 * 一名划桨者的目标位移速度：0.0167 格/tick = 1/3 格/秒。
	 *
	 * <p><b>这是 02 §6 原表逐字翻译过来的</b>："木桨 位移 2 格 / 冷却 6s" ⇒ 持续速度
	 * 2 ÷ 6 = 0.333 格/秒。离散范式下那句话只能这么解释，才不引入新数值。
	 * 4 人满编划桨 = 0.0667 格/tick ≈ 1.33 格/秒，横穿 {@code default_sea}（280 格）约 3.5 分钟。
	 */
	public static final double ROW_SPEED_PER_ROWER = 0.0167;

	/**
	 * 一块帆在单位风强下的目标位移速度：0.0125 格/tick = 0.25 格/秒。
	 *
	 * <p>同样直接来自 02 §6："帆 位移 3 格 / 冷却 6s" ⇒ 0.5 格/秒，而那是风强 2.0 档的表现，
	 * 所以每单位风强是 0.25 格/秒。风强线性乘上去：风暴（4.0）时一块帆就是 1 格/秒，
	 * 静风仍是 0 —— 帆不是发动机，这条从 M2 开始没变过。
	 *
	 * <p>桨与帆的单档比是 {@code 0.0167 : 0.025 = 2:3}，正是原表"木桨 2 格 : 帆 3 格"。
	 */
	public static final double SAIL_SPEED_PER_SAIL_PER_WIND = 0.0125;

	/**
	 * 比例增益。取 <b>18</b>，依据是"持续施力"那一档实测出来的阻尼。
	 *
	 * <p><b>只认这条口径：</b>每 tick 施加的速度增量 {@code dv = GAIN×(目标 − 实测)}，
	 * 而稳态实测位移只有 dv 的一半左右 —— 由"4 人档 dv≈0.066、实测位移 0.0338 格/tick"
	 * 反推得等效阻尼率 {@code k≈2 /tick}。纯比例控制的稳态解因此是
	 * <pre>X/T = G/(G + k) = G/(G + 2)</pre>
	 * G=2 只能到目标的 50%，G=18 是 90% —— 现在闭环的残差就落在这 10% 上。
	 *
	 * <p><b>为什么现在敢用这么大的增益，而上次 GAIN=20 把服务端搞崩了：</b>
	 * 那次<b>没有斜率限制</b>，启动一 tick 的误差等于整个目标值，dv 冲到 1.34 格/tick
	 * （27 格/秒），筏子被甩出海城、离水后水阻消失，8.4 秒飞出 1475 格。
	 * 现在有 {@link #MAX_DV_PER_TICK} 把瞬态顶住，增益只决定"多快追上去"，
	 * 不再决定"最多能被推多快"。
	 *
	 * <p><b>一个我没解释清楚但必须记下的矛盾：</b>用 {@code /sable physics impulse} 给<b>单次</b>
	 * 冲量、再按位姿量衰减，拟合出的阻尼率只有 0.11~0.15 /tick（J=1 走 0.01 格、J=4 走 0.07 格），
	 * 与持续施力档的 ≈2 /tick 差一个数量级。本控制器每 tick 施力，所以按<b>持续档</b>配平；
	 * 单次冲量那条路为什么衰减那么慢我没查出来 —— 别拿那个数当 k 用。
	 */
	public static final double GAIN = 18.0;

	/**
	 * 每 tick 允许施加的速度增量硬顶（格/tick）。0.5 ≈ 瞬时 10 格/秒。
	 *
	 * <p>存在的唯一理由就是那次 1475 格的甩飞：误差在启动瞬间等于整个目标值，
	 * 纯比例项会把冲量顶到不可收拾的量级。限住斜率之后系统只能"逐步追上去"，
	 * 水阻与海域边界都还在原来的位置，行为可预期。
	 *
	 * <p>它同时也是大筏的安全线：冲量 = 质量 × 这个增量，而质量由 {@code SableRafts.mass}
	 * 实时读，所以筏子多大都不会被一 tick 推飞。取值必须 ≥ 2×最大目标速度
	 * （上限 0.25 ⇒ 至少要 0.5 才不影响满速档），否则这里会成为真正的天花板、
	 * 让档位形同虚设 —— 上一版取 0.02 就是这么把 4 人的 1.33 格/秒压成 0.2 格/秒的。
	 */
	public static final double MAX_DV_PER_TICK = 0.5;

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
	 * @return {@code {dirX, dirZ, speed}}；合速度为 0 时方向给 (0,0)，调用方据此跳过
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
