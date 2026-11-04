package dev.nbw.domain.round;

import java.util.HashMap;
import java.util.Map;

/**
 * 显式转移表（04 §3）。长期服最贵的是"不该发生的状态转换被静默吞掉"，
 * 所以非法转移不返回"保持不变"，而是抛出让调用方记日志。
 */
public final class Transitions {

	/** 非法转移：适配层必须捕获、记审计、并保持原阶段，绝不静默。 */
	public static final class IllegalTransition extends RuntimeException {
		private static final long serialVersionUID = 1L;

		IllegalTransition(Phase from, RoundEvent event) {
			super("非法阶段转移：" + from + " + " + event);
		}
	}

	private record Key(Phase phase, RoundEvent event) {
	}

	private static final Map<Key, Phase> TABLE = new HashMap<>();

	static {
		put(Phase.LOBBY, RoundEvent.START, Phase.ENROLL);
		put(Phase.ENROLL, RoundEvent.ENROLL_TIMEOUT, Phase.READY);
		put(Phase.ENROLL, RoundEvent.ADMIN_ABORT, Phase.LOBBY);
		put(Phase.READY, RoundEvent.READY_TIMEOUT, Phase.PLAY);
		put(Phase.READY, RoundEvent.ADMIN_ABORT, Phase.LOBBY);
		put(Phase.PLAY, RoundEvent.WINNER_DECIDED, Phase.SETTLE);
		put(Phase.PLAY, RoundEvent.MATCH_TIMEOUT, Phase.SETTLE);
		put(Phase.PLAY, RoundEvent.ADMIN_STOP, Phase.SETTLE);
		put(Phase.PLAY, RoundEvent.ADMIN_ABORT, Phase.LOBBY);
		put(Phase.SETTLE, RoundEvent.SETTLE_TIMEOUT, Phase.RETURN);
		put(Phase.SETTLE, RoundEvent.ADMIN_STOP, Phase.RETURN);
		put(Phase.RETURN, RoundEvent.RETURN_TIMEOUT, Phase.LOBBY);
	}

	private Transitions() {
	}

	private static void put(Phase from, RoundEvent event, Phase to) {
		if (TABLE.put(new Key(from, event), to) != null) {
			throw new IllegalStateException("转移表重复定义：" + from + " + " + event);
		}
	}

	public static Phase next(Phase from, RoundEvent event) {
		Phase to = TABLE.get(new Key(from, event));
		if (to == null) {
			throw new IllegalTransition(from, event);
		}
		return to;
	}

	public static boolean can(Phase from, RoundEvent event) {
		return TABLE.containsKey(new Key(from, event));
	}

	/** 供 /cww state 与文档核对：当前表覆盖的边数。 */
	public static int edgeCount() {
		return TABLE.size();
	}
}
