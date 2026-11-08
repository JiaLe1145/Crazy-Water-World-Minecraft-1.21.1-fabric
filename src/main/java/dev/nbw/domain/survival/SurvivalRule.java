package dev.nbw.domain.survival;

/**
 * 营养/水分的纯规则（M4-1，02 §2）。零依赖、可单测。
 *
 * <p>数值来自 02 §2 的表：营养 100、1.0/min（战斗 1.6/min）；水分 100、3.5/min。
 * 两条都<b>不致命</b>——到 0 只是挂 debuff（Gate G4：状态不得单独致死满装备玩家）。
 */
public final class SurvivalRule {

	/** 满值。 */
	public static final double MAX = 100.0;

	/** 营养：平静 1.0/min，战斗/划桨 1.6/min。 */
	public static final double NUTRITION_PER_MIN = 1.0;
	public static final double NUTRITION_PER_MIN_COMBAT = 1.6;

	/** 水分：3.5/min（缺水环境 6/min，本轮先只落基础档）。 */
	public static final double HYDRATION_PER_MIN = 3.5;

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
