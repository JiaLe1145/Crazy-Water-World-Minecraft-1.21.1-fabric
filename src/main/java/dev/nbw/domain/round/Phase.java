package dev.nbw.domain.round;

/**
 * 回合阶段（04 §3 状态机）。
 * 阶段是权限的唯一来源：适配层只能查询，不能自行放宽（04 §2 不变式 4、00 §二.4 判定在服务端）。
 */
public enum Phase {
	/** 常驻大厅：报名、等下一局。 */
	LOBBY,
	/** 报名/分队窗口（默认 30s）：这是唯一允许改队伍归属的阶段。 */
	ENROLL,
	/** 锁定队伍、传送进图（默认 10s）。 */
	READY,
	/** 对局主阶段（8–15min）。 */
	PLAY,
	/** 结算面板（默认 20s）。 */
	SETTLE,
	/** 清理并回大厅（默认 5s）。 */
	RETURN;

	public boolean allowsPvp() {
		return this == PLAY;
	}

	public boolean allowsBuild() {
		return this == PLAY;
	}

	public boolean allowsInventoryEdit() {
		return this == PLAY;
	}

	/** 已进入本局的玩家：受对局规则约束，掉线要留席位。 */
	public boolean matchActive() {
		return this == READY || this == PLAY || this == SETTLE;
	}

	/** 是否允许报名/改队（01 §2 禁止中途加入 = PLAY 起一律拒绝）。 */
	public boolean allowsEnrollment() {
		return this == LOBBY || this == ENROLL;
	}
}
