package dev.nbw.domain.round;

import java.util.UUID;

/**
 * 玩家在本局内的会话（04 §2 PlayerSession 的 M1 子集：席位、存活、掉线）。
 * 键是 UUID 而不是实体对象，所以复活换实体不会丢数据；掉线席位保留时长见 A13（01 §2）。
 */
public final class PlayerSession {

	/** 掉线席位保留 90s（01 §2 A13），超时视为放弃本局。 */
	public static final int SEAT_HOLD_TICKS = 90 * 20;

	private final UUID id;
	private String name;
	private int teamIndex = -1;
	private boolean alive;
	private boolean eliminated;
	private int disconnectTick = -1;
	private int joinedQueueTick;
	private int eliminatedTick = -1;

	public PlayerSession(UUID id, String name, int joinedQueueTick) {
		this.id = id;
		this.name = name;
		this.joinedQueueTick = joinedQueueTick;
	}

	public UUID id() {
		return id;
	}

	public String name() {
		return name;
	}

	public void rename(String name) {
		this.name = name;
	}

	public int teamIndex() {
		return teamIndex;
	}

	void setTeam(int teamIndex) {
		this.teamIndex = teamIndex;
	}

	public boolean isAlive() {
		return alive;
	}

	void setAlive(boolean alive) {
		this.alive = alive;
	}

	public boolean isEliminated() {
		return eliminated;
	}

	void setEliminated(boolean eliminated) {
		this.eliminated = eliminated;
	}

	public int eliminatedTick() {
		return eliminatedTick;
	}

	void setEliminatedTick(int tick) {
		this.eliminatedTick = tick;
	}

	public int disconnectTick() {
		return disconnectTick;
	}

	public boolean isDisconnected() {
		return disconnectTick >= 0;
	}

	void markDisconnected(int tick) {
		this.disconnectTick = tick;
	}

	void markReconnected() {
		this.disconnectTick = -1;
	}

	public int joinedQueueTick() {
		return joinedQueueTick;
	}

	void rejoinQueue(int tick) {
		this.joinedQueueTick = tick;
	}

	public boolean seatExpired(int tick) {
		return isDisconnected() && tick - disconnectTick > SEAT_HOLD_TICKS;
	}
}
