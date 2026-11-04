package dev.nbw.domain.raft;

/**
 * 沉筏判定（01 §4.3.1 已批准 A3-v4）：某队既无幸存者存活、也无玩家存活 → 30s 倒计时 → 沉。
 *
 * <p>三条刻意的设计选择，都写在这里以免适配层各自理解：
 * <ol>
 *   <li><b>倒计时一旦启动就不因"条件消失"而自动中止</b>。已被批准的中止手段只有一种：
 *       有人回筏重修筏心，且每局限 1 次。若允许"死人复活就自动取消"，
 *       沉筏会被复活机制反复抵消，"淘汰"就不再是淘汰。</li>
 *   <li><b>SUNK 是终态</b>。本局内不再回退，靠岸/打捞都不复活筏子。</li>
 *   <li><b>非法状态转换抛异常而不是静默返回</b>，与 {@code Transitions} 同一纪律：
 *       长期服最贵的是"不该发生的事被悄悄吞掉"。</li>
 * </ol>
 */
public final class SinkRule {

	public static final int TICKS_PER_SECOND = 20;
	public static final int COUNTDOWN_SECONDS = 30;
	public static final int COUNTDOWN_TICKS = COUNTDOWN_SECONDS * TICKS_PER_SECOND;
	/** 01 §4.3.1：重修筏心每局重置倒计时的次数上限。 */
	public static final int MAX_RESETS = 1;

	/** 一个队的"还剩什么"。幸存者与玩家任一个活着，就不算打光。 */
	public record TeamVitality(boolean anyPlayerAlive, boolean anySurvivorAlive) {

		public boolean wiped() {
			return !anyPlayerAlive && !anySurvivorAlive;
		}
	}

	/** 非法的沉筏状态组合：说明适配层传错了东西，必须炸出来而不是继续算。 */
	public static final class IllegalSinkState extends RuntimeException {
		private static final long serialVersionUID = 1L;

		IllegalSinkState(SinkState state, String why) {
			super("非法沉筏状态 " + state.phase() + "(remaining=" + state.remainingTicks()
					+ ", resets=" + state.resetsUsed() + ")：" + why);
		}
	}

	private SinkRule() {
	}

	/**
	 * 每 tick 对每个队调一次。
	 *
	 * @param state          该队当前沉筏状态
	 * @param vitality       该队此刻的玩家/幸存者存活情况
	 * @param repairedThisTick 本 tick 是否有人重修了筏心
	 * @return 新状态；SUNK 之后原样返回
	 */
	public static SinkState tick(SinkState state, TeamVitality vitality, boolean repairedThisTick) {
		validate(state);

		switch (state.phase()) {
			case SUNK:
				return state;

			case IDLE:
				if (!vitality.wiped()) {
					return state;
				}
				if (repairedThisTick) {
					throw new IllegalSinkState(state, "IDLE 阶段没有倒计时可重修");
				}
				return new SinkState(SinkState.SinkPhase.COUNTDOWN, COUNTDOWN_TICKS, state.resetsUsed());

			case COUNTDOWN: {
				if (repairedThisTick) {
					SinkState repaired = reset(state);
					if (repaired != state) {
						// 重修成功当帧不再扣时：对玩家的承诺是"倒计时回到 30s"，
						// 再偷偷扣 1 tick 会让 HUD 显示 29s，属于自找的口舌。
						return repaired;
					}
					// 额度已用尽，重修不生效，倒计时照常走
				}
				int remaining = state.remainingTicks() - 1;
				if (remaining <= 0) {
					return new SinkState(SinkState.SinkPhase.SUNK, 0, state.resetsUsed());
				}
				return new SinkState(SinkState.SinkPhase.COUNTDOWN, remaining, state.resetsUsed());
			}

			default:
				throw new IllegalSinkState(state, "未知阶段");
		}
	}

	/**
	 * 重修筏心：把倒计时拉回满 30s，用掉一次重置额度。
	 * 额度用完后再重修不再有任何效果（返回原状态），但也不会报错——
	 * 玩家侧的"重修失败"提示由适配层根据返回值与入参是否相同自行判断。
	 */
	public static SinkState reset(SinkState state) {
		validate(state);
		if (!state.countingDown()) {
			throw new IllegalSinkState(state, "只有倒计时中才谈得上重置");
		}
		if (state.resetsUsed() >= MAX_RESETS) {
			return state;
		}
		return new SinkState(SinkState.SinkPhase.COUNTDOWN, COUNTDOWN_TICKS, state.resetsUsed() + 1);
	}

	private static void validate(SinkState state) {
		if (state.resetsUsed() < 0) {
			throw new IllegalSinkState(state, "重置次数为负");
		}
		if (state.resetsUsed() > MAX_RESETS) {
			throw new IllegalSinkState(state, "重置次数超过上限 " + MAX_RESETS);
		}
		switch (state.phase()) {
			case IDLE -> {
				if (state.remainingTicks() != 0) {
					throw new IllegalSinkState(state, "IDLE 阶段不应有剩余倒计时");
				}
			}
			case COUNTDOWN -> {
				if (state.remainingTicks() <= 0) {
					throw new IllegalSinkState(state, "倒计时中但剩余 tick <= 0，本该已经沉了");
				}
				if (state.remainingTicks() > COUNTDOWN_TICKS) {
					throw new IllegalSinkState(state, "剩余倒计时超过 " + COUNTDOWN_TICKS + " tick");
				}
			}
			case SUNK -> {
				if (state.remainingTicks() != 0) {
					throw new IllegalSinkState(state, "已沉没但还有剩余倒计时");
				}
			}
		}
	}
}
