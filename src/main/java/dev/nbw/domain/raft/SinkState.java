package dev.nbw.domain.raft;

/**
 * 沉筏倒计时（01 §4.3.1 已批准 A3-v4）。
 *
 * <p>纯数据，不含任何 Minecraft 依赖：可以直接进回合审计日志、可以序列化、可以单测。
 * 真正"把筏压下水"的动作在适配层，本类只决定"该不该压、压之前还剩几 tick"。
 */
public record SinkState(SinkPhase phase, int remainingTicks, int resetsUsed) {

	public enum SinkPhase {
		/** 队还活着（或还没打光），无事发生。 */
		IDLE,
		/** 已触发，正在数 30s。 */
		COUNTDOWN,
		/** 已沉。终态，本局内不再回退。 */
		SUNK
	}

	public static SinkState fresh() {
		return new SinkState(SinkPhase.IDLE, 0, 0);
	}

	public boolean countingDown() {
		return phase == SinkPhase.COUNTDOWN;
	}

	public boolean sunk() {
		return phase == SinkPhase.SUNK;
	}

	/** 倒计时剩余秒数（向上取整），供 HUD 与播报用。 */
	public int remainingSeconds() {
		return (remainingTicks + SinkRule.TICKS_PER_SECOND - 1) / SinkRule.TICKS_PER_SECOND;
	}
}
