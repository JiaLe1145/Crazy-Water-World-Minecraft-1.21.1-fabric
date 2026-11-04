package dev.nbw.domain.round;

/**
 * 一局的时间与人数规格（01 §2 阶段时长、02 §1 三档赛制）。
 * 时长一律用 tick 计数驱动，不用系统时间（04 §3：防止改系统时间影响对局）。
 */
public record RoundConfig(int teamCount,
		int teamSize,
		int minPlayers,
		int enrollTicks,
		int readyTicks,
		int playHardCapTicks,
		int settleTicks,
		int returnTicks) {

	public static int toTicks(int seconds) {
		return Math.max(1, seconds * 20);
	}

	public RoundConfig {
		if (teamCount < 1 || teamSize < 1) {
			throw new IllegalArgumentException("teamCount / teamSize 必须 > 0");
		}
		if (minPlayers < 1) {
			throw new IllegalArgumentException("minPlayers 必须 > 0");
		}
		if (enrollTicks < 1 || readyTicks < 1 || settleTicks < 1 || returnTicks < 1 || playHardCapTicks < 1) {
			throw new IllegalArgumentException("阶段时长必须 > 0");
		}
	}

	/** 02 §1 标准档：4×3、最少 4 人、12 分钟局（阶段时长取 01 §2 默认值）。 */
	public static RoundConfig standard() {
		return new RoundConfig(4, 3, 4, toTicks(30), toTicks(10), toTicks(900), toTicks(20), toTicks(5));
	}

	/** 由 format 的 targetTotal 派生：硬上限 = 目标局长 + 3 分钟余量（05 R1 的兜底，不是玩法数值）。 */
	public static RoundConfig fromFormat(int teams, int teamSize, int minPlayers, int targetTotalSeconds) {
		int hardCap = toTicks(Math.max(60, targetTotalSeconds + 180));
		return new RoundConfig(Math.max(1, teams), Math.max(1, teamSize), Math.max(1, minPlayers),
				toTicks(30), toTicks(10), hardCap, toTicks(20), toTicks(5));
	}

	public int ticksIn(Phase phase) {
		return switch (phase) {
			case ENROLL -> enrollTicks;
			case READY -> readyTicks;
			case PLAY -> playHardCapTicks;
			case SETTLE -> settleTicks;
			case RETURN -> returnTicks;
			case LOBBY -> 0;
		};
	}

	public int capacity() {
		return teamCount * teamSize;
	}
}
