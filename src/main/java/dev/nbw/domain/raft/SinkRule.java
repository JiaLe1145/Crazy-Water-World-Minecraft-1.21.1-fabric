package dev.nbw.domain.raft;

/**
 * 沉筏判定（01 §4.3.1 已批准 A3-v4）：某队既无幸存者存活、也无玩家存活 → 30s 倒计时 → 沉。
 *
 * <p>三条刻意的设计选择，都写在这里以免适配层各自理解：
 * <ol>
 *   <li><b>倒计时一旦启动就不因"条件消失"而自动中止</b>。已被批准的中止手段只有一种：
 *       残骸修复（A2-v2，{@link #clear}）<b>解除</b>倒计时——那是"筏心恢复"的动作。
 *       若允许"死人复活就自动取消"，沉筏会被复活机制反复抵消，"淘汰"就不再是淘汰。</li>
 *   <li><b>SUNK 是终态</b>。本局内不再回退，靠岸/打捞都不复活筏子。</li>
 *   <li><b>非法状态转换抛异常而不是静默返回</b>，与 {@code Transitions} 同一纪律：
 *       长期服最贵的是"不该发生的事被悄悄吞掉"。</li>
 * </ol>
 *
 * <p>2026-11-07：删除 A3「建材重修重置倒计时一次」（原 {@code reset}/{@code MAX_RESETS}/{@code resetsUsed}）。
 * 它比 A2-v2 的"残骸修复解除倒计时"更弱且与之重叠，用户裁定整条删掉；
 * 倒计时从此只有"解除"一种中止手段，不再有"重置"。
 */
public final class SinkRule {

	public static final int TICKS_PER_SECOND = 20;
	public static final int COUNTDOWN_SECONDS = 30;
	public static final int COUNTDOWN_TICKS = COUNTDOWN_SECONDS * TICKS_PER_SECOND;

	/**
	 * 本 tick 的沉筏触发事实。
	 *
	 * <p>两条已批准的路径（01 §4.3.1 / §4.3.2）：
	 * <ul>
	 *   <li>{@code teamWiped}：既无幸存者存活也无玩家存活（A3-v4）；</li>
	 *   <li>{@code coreDestroyed}：敌方把本队筏心打成了残骸（A2）。</li>
	 * </ul>
	 * 两者任一成立即开始 30s 倒计时。
	 */
	public record SinkTrigger(boolean teamWiped, boolean coreDestroyed) {

		public boolean shouldStart() {
			return teamWiped || coreDestroyed;
		}
	}

	/** 非法的沉筏状态组合：说明适配层传错了东西，必须炸出来而不是继续算。 */
	public static final class IllegalSinkState extends RuntimeException {
		private static final long serialVersionUID = 1L;

		IllegalSinkState(SinkState state, String why) {
			super("非法沉筏状态 " + state.phase() + "(remaining=" + state.remainingTicks() + ")：" + why);
		}
	}

	private SinkRule() {
	}

	/**
	 * 每 tick 对每个队调一次。
	 *
	 * @param state   该队当前沉筏状态
	 * @param trigger 本 tick 的两条触发事实（全队打光 / 筏心成残骸）
	 * @return 新状态；SUNK 之后原样返回
	 */
	public static SinkState tick(SinkState state, SinkTrigger trigger) {
		validate(state);

		switch (state.phase()) {
			case SUNK:
				return state;

			case IDLE:
				if (!trigger.shouldStart()) {
					return state;
				}
				return new SinkState(SinkState.SinkPhase.COUNTDOWN, COUNTDOWN_TICKS);

			case COUNTDOWN: {
				int remaining = state.remainingTicks() - 1;
				if (remaining <= 0) {
					return new SinkState(SinkState.SinkPhase.SUNK, 0);
				}
				return new SinkState(SinkState.SinkPhase.COUNTDOWN, remaining);
			}

			default:
				throw new IllegalSinkState(state, "未知阶段");
		}
	}

	/**
	 * 筏心从残骸恢复成功 → <b>解除</b>倒计时（01 §4.3.2 A2-v2）。
	 *
	 * <p>修好了筏心却照样沉，这条机制就废了，所以恢复成功的判定必须走这里——让这一队彻底退出沉筏流程，
	 * 回到 IDLE。与"重置倒计时"的区别是实打实的：重置只是把 30s 拉回满、稍后照样沉（那条 A3 已删）。
	 *
	 * <p>SUNK 是不可逆的：筏子已经沉了，再修筏心也捞不回来。
	 */
	public static SinkState clear(SinkState state) {
		validate(state);
		if (state.phase() == SinkState.SinkPhase.SUNK) {
			return state;
		}
		return new SinkState(SinkState.SinkPhase.IDLE, 0);
	}

	private static void validate(SinkState state) {
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
