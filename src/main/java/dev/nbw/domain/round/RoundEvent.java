package dev.nbw.domain.round;

/** 阶段转移的触发原因（04 §3 转移表的边）。 */
public enum RoundEvent {
	/** 报名达到下限，或管理员 /cww start。 */
	START,
	ENROLL_TIMEOUT,
	READY_TIMEOUT,
	/** 最后存活队成立（D5）。 */
	WINNER_DECIDED,
	/** 对局超过硬上限（防拖局，05 R1）。 */
	MATCH_TIMEOUT,
	SETTLE_TIMEOUT,
	RETURN_TIMEOUT,
	/** 管理员正常终止：PLAY 下仍走结算并公布结果。 */
	ADMIN_STOP,
	/** 管理员紧急中止：不判胜负，直接清理回大厅。 */
	ADMIN_ABORT
}
