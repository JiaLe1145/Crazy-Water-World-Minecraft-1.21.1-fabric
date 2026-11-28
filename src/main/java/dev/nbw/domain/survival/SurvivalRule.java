package dev.nbw.domain.survival;

/**
 * 营养/水分的纯规则（M4-1，02 §2）。零依赖、可单测。
 *
 * <p>数值来自 02 §2 的表；2026-11-28 用户要求"降低太慢、加快一点"，
 * 在文档值基础上整体 ×2：营养 100、2.0/min（战斗 3.2/min）；水分 100、7.0/min。
 * 两条都<b>不致命</b>——到 0 只是挂 debuff（Gate G4：状态不得单独致死满装备玩家）。
 */
public final class SurvivalRule {

	/** 满值。 */
	public static final double MAX = 100.0;

	/** 营养：平静 2.0/min，战斗/划桨 3.2/min（2026-11-28 从 1.0/1.6 加倍）。 */
	public static final double NUTRITION_PER_MIN = 2.0;
	public static final double NUTRITION_PER_MIN_COMBAT = 3.2;

	/** 水分：7.0/min（2026-11-28 从 3.5 加倍）。 */
	public static final double HYDRATION_PER_MIN = 7.0;

	/** 低于这个值开始挂 debuff（02 §2 没给精确阈值，按"低营养/低水分"取 20）。 */
	public static final double LOW_THRESHOLD = 20.0;

	private SurvivalRule() {
	}

	/** 每分钟速率换算成"这么多 tick 掉多少"。1 分钟 = 1200 tick。 */
	public static double decay(double perMinute, int ticks) {
		return perMinute * ticks / 1200.0;
	}

	/** 夹到 [0, MAX]。 */
	public static double clamp(double value) {
		return Math.max(0.0, Math.min(MAX, value));
	}

	/** 营养是否见底（挂"攻击力/移速"debuff）。 */
	public static boolean nutritionLow(double nutrition) {
		return nutrition < LOW_THRESHOLD;
	}

	/** 水分是否见底（禁冲刺 + 视野轻微模糊）。 */
	public static boolean hydrationLow(double hydration) {
		return hydration < LOW_THRESHOLD;
	}
}
