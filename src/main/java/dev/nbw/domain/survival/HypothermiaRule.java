package dev.nbw.domain.survival;

/**
 * 失温（01 §5 / 02 §8 的可选机制，2026-11-14 用户批准实现）。
 *
 * <p><b>口径（起始配平值）：</b>连续"湿身"（泡水或淋雨）<b>10 秒</b>后进入失温，
 * 营养与水分消耗 ×<b>1.5</b>；离开水/雨<b>3 秒</b>后解除。短时间干湿切换不清零，
 * 只有连续干燥满 3 秒才把湿身计时归零——否则在雨里"上筏躲一下"就能白嫖。
 *
 * <p><b>没做</b>：篝火/干衣这类主动恢复物（02 §8 的对应生产物）；本轮只落地"湿身 → 加速消耗"
 * 与"变干自动解除"。失温本身<b>不直接掉血</b>（Gate G4：状态不得单独致死满装备玩家）。
 *
 * <p>纯函数：适配层 {@code survival/SurvivalService} 每秒喂一次 {@code wet} 事实。
 */
public final class HypothermiaRule {

	/** 连续湿身多少秒后进入失温。 */
	public static final int WET_TRIGGER_SECONDS = 10;

	/** 连续干燥多少秒后解除并清零。 */
	public static final int DRY_RESET_SECONDS = 3;

	/** 失温期间营养/水分的消耗倍率。 */
	public static final double DRAIN_MULTIPLIER = 1.5;

	/** 湿身计时上限：只用于防止长时间泡水把 int 撑爆，不影响阈值。 */
	private static final int WET_CAP_SECONDS = WET_TRIGGER_SECONDS * 10;

	/**
	 * 失温计时现场。{@code wetSeconds} 是连续湿身累计（上限 100），
	 * {@code drySeconds} 是当前这段干燥已持续几秒（到 3 秒清零）。
	 */
	public record State(int wetSeconds, int drySeconds) {

		public static State none() {
			return new State(0, 0);
		}

		public boolean active() {
			return wetSeconds >= WET_TRIGGER_SECONDS;
		}
	}

	private HypothermiaRule() {
	}

	/**
	 * 推进一秒。
	 *
	 * @param wet 这一秒玩家是否湿身（泡水或淋雨）
	 */
	public static State tick(State state, boolean wet) {
		State s = state == null ? State.none() : state;
		if (wet) {
			return new State(Math.min(WET_CAP_SECONDS, s.wetSeconds() + 1), 0);
		}
		int dry = s.drySeconds() + 1;
		if (dry >= DRY_RESET_SECONDS) {
			return State.none();
		}
		return new State(s.wetSeconds(), dry);
	}

	/** 当前这一秒是否处于失温。 */
	public static boolean active(State state) {
		return state != null && state.active();
	}

	/** 失温期间营养/水分消耗倍率；未失温必须严格 1.0。 */
	public static double drainMultiplier(State state) {
		return active(state) ? DRAIN_MULTIPLIER : 1.0;
	}
}
