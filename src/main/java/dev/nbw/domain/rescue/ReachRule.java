package dev.nbw.domain.rescue;

/**
 * 队友伸手救援的规则（01 §8，动作在<b>救援方</b>那一侧）。
 *
 * <p><b>2026-11-06 用户裁决定了三条</b>，本类只是把它们翻译成可测的算术：
 * <ol>
 *   <li><b>占用的是救援方的动作</b>：伸手这 1 秒里救援方在按着长按，划桨/修筏同一条通道自然让位；</li>
 *   <li><b>被救者不需要任何输入</b>：只要"在水里 + 够得着自家船壳"就能被拉回来，
 *       挂机的人也有人救——这条是队友救援比自救强的地方（自救要自己按满 2 秒）；</li>
 *   <li><b>救援方不判落水、不判站位</b>：唯一的门槛是"到被救者的距离 ≤ {@link #HAND_REACH}"。</li>
 * </ol>
 *
 * <p>第 3 条的代价已经如实写进工程记录，不在这里偷偷加限制：两个都溺水的队友可以互救，
 * 而且互救（1 秒）比自救（2 秒）快；正在自己扒边爬的人也能同时去拉别人。要不要收紧，等玩法单独裁。
 *
 * <p><b>"双方都不能持械"这条没实现</b>（02 §8 原文带着它）：武器表还没做，
 * 现在去查"手上是不是武器"就是替玩法编定义。
 *
 * <p>与 {@link ClingRule} 的分工：时序与资格在这里，<b>落点几何与插值共用 ClingRule 那一套</b>
 * （{@code landingLocal} / {@code climbLocal}）。两处各写一遍"什么叫站进甲板里"迟早分叉。
 */
public final class ReachRule {

	/** 伸手到把人拉上筏的时间：02 §8 的"贴边 1s"。 */
	public static final int REACH_TICKS = 20;

	/**
	 * 伸手上限（格，甲板局部帧里的<b>水平</b>距离）。待批的数值。
	 *
	 * <p>取 3.5 = {@code ClingRule.HULL_REACH}(2.5，被救者还能贴着船壳) + 1.0（人站在甲板边上、
	 * 身体探出去的量）。刻意<b>不用</b> {@code HoldPolicy.REACH_BLOCKS}(8)：那个数是"手能不能碰到那个方块"，
	 * 而"手能不能碰到一个人"是另一回事，8 格伸手会让"游到船壳边"这条自救的分量归零。
	 * 更远的那一档是抛绳索（02 §8 写 12 格），那是道具，归武器/道具那一版。
	 */
	public static final double HAND_REACH = 3.5;

	/** 救援阶段。{@code PULLED} 与 {@code LANDED} 同一类：只出现一次，用于播报与取证，下一拍回 IDLE。 */
	public enum Stage { IDLE, REACH, PULLED }

	/** 某个救援方的伸手状态。不可变，靠 {@link #next} 推进；键是<b>救援方</b>，被救者由适配层另存。 */
	public record State(Stage stage, int tick) {

		public static State off() {
			return new State(Stage.IDLE, 0);
		}

		public boolean reaching() {
			return stage == Stage.REACH;
		}
	}

	private ReachRule() {
	}

	/**
	 * 推进一拍。
	 *
	 * @param holding  救援方这一拍还在按着（续包窗口内）
	 * @param eligible 资格仍然成立：由 {@link #denyReason} 算出来的那扇门，中途失效就从头再来
	 * @param damaged  救援方这一拍受到伤害——手一抖人就没了
	 * @return 新状态；{@code Stage.PULLED} 只在完成那一拍出现
	 */
	public static State next(State prev, boolean holding, boolean eligible, boolean damaged) {
		if (damaged && prev.reaching()) {
			return State.off();
		}
		if (!holding || !eligible) {
			return State.off();   // 不保留进度：留一半等于下一次白送
		}
		if (prev.stage() == Stage.PULLED) {
			return State.off();
		}
		if (prev.stage() == Stage.IDLE) {
			return new State(Stage.REACH, 0);
		}
		int tick = prev.tick() + 1;
		if (tick >= REACH_TICKS) {
			return new State(Stage.PULLED, tick);
		}
		return new State(Stage.REACH, tick);
	}

	/**
	 * 这一趟成不成立（<b>开始</b>那一拍用）；{@code null} 表示可以拉。
	 *
	 * <p>每一条都给独立的原因串：探针要能分辨"救援方离得太远"和"被救者根本没在船壳边"，
	 * 合成一句"救不了"的话，玩法调数值时就完全是猜。
	 *
	 * @param victimGap  被救者到自家船壳的间隙（格），{@link ClingRule#hullGap} 算出来的那个
	 * @param distance   救援方到被救者的水平距离（甲板局部帧），{@link #handDistance} 算出来的那个
	 */
	public static String denyReason(boolean phaseAllows, boolean raftPresent, boolean sameTeam,
			boolean notSelf, boolean victimInWater, double victimGap, double distance) {
		return deny(phaseAllows, raftPresent, sameTeam, notSelf, Boolean.valueOf(victimInWater),
				victimGap, distance);
	}

	/**
	 * 已经握住人之后的闸门：<b>不再要求在水里，也不再复查伸手上限</b>，其余条件一条不减。
	 *
	 * <p><b>放开"在水里"</b>是 2026-11-06 实测逼出来的：把人往上抬，他必然会离开水面，
	 * 一路查"在水里"就会在抬到一半时自己把这次救援判成作废
	 * （探针读到"拉回计数不涨、伸手作废一直涨、被救者 Y 只爬到 62.79，而甲板面是 64.02"）。
	 * {@link ClingRule} 当年在同一处栽过一次（"在水里只是起爬的门槛"），这里不重复第二次。
	 *
	 * <p><b>放开"伸手上限"是同一次实测里另一条一样的自相矛盾</b>：把人往甲板方向拉，
	 * 他就会离<b>泡在筏外</b>的救援方越来越远——探针那一趟的距离从 2.30 涨到 3.56，
	 * 第 13 拍被自己的上限判废。也就是说"够得着才抓得住"只能是<b>开始</b>的条件：
	 * 一旦抓住，1 秒之内不许因为距离变化松手，否则任何不在筏上的救援方都永远救不成任何人。
	 * 拉回的目标点每拍按筏的当前位姿换算（见 {@code RescueService.liftTo}），
	 * 所以筏子在这 1 秒里开走了，人也是被拉到<b>那时</b>的落点上，不存在"拉向一艘已经不存在的筏"。
	 *
	 * <p>间隙那一条保留：它是"这人还扒不扒得住自家船壳"的凭据，
	 * 而拉回过程中人只会离壳更近，真出事（筏沉、换阶段、换队）另有各条门负责。
	 */
	public static String denyOngoing(boolean phaseAllows, boolean raftPresent, boolean sameTeam,
			boolean notSelf, double victimGap) {
		return deny(phaseAllows, raftPresent, sameTeam, notSelf, null, victimGap, null);
	}

	private static String deny(boolean phaseAllows, boolean raftPresent, boolean sameTeam, boolean notSelf,
			Boolean victimInWater, double victimGap, Double distance) {
		if (!phaseAllows) {
			return "当前阶段不开放救援（只在 PLAY，见 04 §2 不变式 4）";
		}
		if (!raftPresent) {
			return "这一队没有筏（还没生成或已经沉了），往哪儿拉人";
		}
		if (!sameTeam) {
			return "伸手只能拉自己队的队友：敌队落水的人归钩竿管（02 §8）";
		}
		if (!notSelf) {
			return "不能把自己当被救者：自救走扒边那条路（2 秒）";
		}
		// null = 这一趟已经在拉人，水面状态不再参与判定；Boolean.TRUE/FALSE 才是起爬那一拍
		if (victimInWater != null && !victimInWater) {
			return "那人不在水里，不需要救";
		}
		if (victimGap > ClingRule.HULL_REACH) {
			return String.format(java.util.Locale.ROOT, "被救者离船壳 %.2f 格，超过他自己够得着的 %.2f 格：先开筏过去",
					victimGap, ClingRule.HULL_REACH);
		}
		// 距离同样只在起拉那一拍查（理由见 denyOngoing）
		if (distance != null && distance > HAND_REACH) {
			return String.format(java.util.Locale.ROOT, "离那人 %.2f 格，伸手够不着（上限 %.2f 格）",
					distance, HAND_REACH);
		}
		return null;
	}

	/**
	 * 救援方与被救者在甲板局部帧里的水平距离。
	 *
	 * <p>用局部坐标而不是世界坐标：筏子正在开的时候，世界坐标算出来的"这一刻的距离"
	 * 与判定所依据的那一帧对不上。垂直分量刻意不计——"贴边伸手"比的是肩膀够不够得着，
	 * 不是眼睛对眼睛，把 Y 算进去会让筏子起伏时救援忽成忽败。
	 */
	public static double handDistance(double rescuerLx, double rescuerLz, double victimLx, double victimLz) {
		double dx = rescuerLx - victimLx;
		double dz = rescuerLz - victimLz;
		return Math.hypot(dx, dz);
	}
}
