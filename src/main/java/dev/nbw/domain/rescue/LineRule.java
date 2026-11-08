package dev.nbw.domain.rescue;

/**
 * 抛绳索 / 钩竿 的纯规则（M2 尾巴，02 §8 + 09 §2.3）。零依赖、可单测。
 *
 * <p>与 {@link ReachRule}（伸手）的区别：伸手是"贴边 1 秒、够 3.5 格"的近距动作；
 * 这里两个是<b>远程道具</b>，距离按<b>世界坐标</b>量（目标可能在离筏很远的水里），
 * 一次使用（不是长按）就生效。
 *
 * <p>本轮只落"拉"这个核心，数值见各常量；钩竿的"0.5s 挣脱窗口"与"拉漂浮物"
 * 依赖更多系统，属后续分批（{@code docs/dev/M2-RESULTS.md} 记）。
 */
public final class LineRule {

	/** 绳索射程（世界水平距离，格）。02 §8：射程 12 格，命中即把落水者拉回。 */
	public static final double ROPE_RANGE = 12.0;

	/** 钩竿射程（世界水平距离，格）。09 §2.3：射程 16 格。 */
	public static final double GRAPNEL_RANGE = 16.0;

	/** 钩竿把目标拉到"离救援方这么近"的位置（格），拉近即停，不是拉到身上。 */
	public static final double GRAPNEL_PULL_GAP = 2.0;

	private LineRule() {
	}

	/**
	 * 绳索目标判定：同队 + 在水里 + 不是自己 + 世界水平距离在射程内。
	 *
	 * @return 拒绝原因；允许则返回 null
	 */
	public static String ropeDeny(boolean play, boolean sameTeam, boolean inWater, boolean notSelf, double dist) {
		if (!play) {
			return "不在对局中";
		}
		if (!notSelf) {
			return "不能抛给自己";
		}
		if (!sameTeam) {
			return "只能抛给队友";
		}
		if (!inWater) {
			return "目标不在水里";
		}
		if (dist > ROPE_RANGE) {
			return "超出射程 " + ROPE_RANGE + " 格（实际 %.1f 格）".formatted(dist);
		}
		return null;
	}

	/**
	 * 钩竿目标判定：敌队 + 不是自己 + 世界水平距离在射程内。
	 *
	 * <p>钩竿目标不要求在水里：它拉的是"站在自家筏上的敌方玩家"，把人拉近 = 拉下水。
	 */
	public static String grapnelDeny(boolean play, boolean enemy, boolean notSelf, double dist) {
		if (!play) {
			return "不在对局中";
		}
		if (!notSelf) {
			return "不能钩自己";
		}
		if (!enemy) {
			return "只能钩敌方玩家";
		}
		if (dist > GRAPNEL_RANGE) {
			return "超出射程 " + GRAPNEL_RANGE + " 格（实际 %.1f 格）".formatted(dist);
		}
		return null;
	}
}
