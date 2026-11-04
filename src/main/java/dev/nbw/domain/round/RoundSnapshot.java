package dev.nbw.domain.round;

import java.util.List;
import java.util.UUID;

/**
 * 回合状态机的可序列化快照（03 §6.1 要求的"每次转移落盘"的数据形态）。
 *
 * <p>本类<b>不含任何 JSON 与 Minecraft 依赖</b>：翻译格式是适配层的事，
 * domain 只负责"什么算一个自洽的状态"。这样重启恢复的正确性可以不起服就测完。
 *
 * <p>{@code arenaId} 与 {@code formatId} <b>必须</b>一起落盘：一局的身份包含"用的是哪张图、
 * 哪套赛制"。少了它们，重启时加载的是默认 format，队伍数对不上，恢复就永远失败
 * （真机踩过：落盘时 2x2、重启后默认 4x3，守卫正确地拒绝了恢复并退回 LOBBY——但那等于没恢复）。
 *
 * <p>刻意不包含的东西：
 * <ul>
 *   <li>{@code pendingEffects} —— 它是"这一 tick 要执行的副作用"，跨重启执行会重复传送；
 *       恢复后由适配层按当前阶段重新做一次入场副作用，而不是回放旧副作用。</li>
 *   <li>{@code lastResult} —— 结果面板的数据源，能从 winnerTeamIndex 与时长推出，不必存。</li>
 * </ul>
 */
public record RoundSnapshot(
		Phase phase,
		int tickInPhase,
		int totalTicks,
		int matchIndex,
		int matchStartTick,
		boolean autoStart,
		int winnerTeamIndex,
		List<Team> teams,
		List<Session> sessions,
		List<UUID> queue,
		List<RoundMachine.Transition> audit,
		List<String> auditNotes,
		String arenaId,
		String formatId) {

	/** 队伍。成员顺序有意义（先申请先进队，01 §2），所以用 List 不用 Set。 */
	public record Team(int index, String name, int maxSize, List<UUID> members) {
	}

	/**
	 * 玩家会话。
	 *
	 * <p>{@code disconnectTick} 与 {@code eliminatedTick} 用 -1 表示"没有发生"，
	 * 与 {@link PlayerSession} 的字段语义严格一致，不要在这里改成 boolean。
	 */
	public record Session(UUID id, String name, int teamIndex, boolean alive, boolean eliminated,
			int eliminatedTick, int disconnectTick, int joinedQueueTick) {
	}

	/** 快照自洽性错误：说明落盘文件被改坏、或者代码写错了。必须炸出来，不能静默起一个怪状态。 */
	public static final class InvalidSnapshot extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public InvalidSnapshot(String why) {
			super("非法回合快照：" + why);
		}
	}

	/**
	 * 校验并返回自身，便于 {@code RoundMachine.restore(store.read().validate())} 这种写法。
	 *
	 * <p>校验是刻意的：重启时最坏的情况不是"文件读不出来"（那会走异常分支），
	 * 而是"文件读出来了但状态自相矛盾"——比如 PLAY 阶段却没有参战者，
	 * 那会让倒计时、胜负判定、圈层全部按错误前提跑下去。
	 */
	public RoundSnapshot validate() {
		if (phase == null) {
			throw new InvalidSnapshot("phase 为空");
		}
		if (formatId == null || arenaId == null) {
			throw new InvalidSnapshot("arenaId/formatId 不可为 null（未知也要写成空串，由存储层判定能否恢复）");
		}
		if (tickInPhase < 0) {
			throw new InvalidSnapshot("tickInPhase=" + tickInPhase + " 为负");
		}
		if (totalTicks < 0) {
			throw new InvalidSnapshot("totalTicks=" + totalTicks + " 为负");
		}
		if (matchIndex < 0) {
			throw new InvalidSnapshot("matchIndex=" + matchIndex + " 为负");
		}
		if (tickInPhase > totalTicks) {
			throw new InvalidSnapshot("阶段内 tick " + tickInPhase + " 超过总 tick " + totalTicks);
		}
		if (teams == null || sessions == null || queue == null) {
			throw new InvalidSnapshot("teams/sessions/queue 不可为 null（空也要写成 []）");
		}

		for (Team t : teams) {
			if (t.index() < 0) {
				throw new InvalidSnapshot("队伍 index=" + t.index() + " 为负");
			}
			if (t.maxSize() <= 0) {
				throw new InvalidSnapshot("队伍 " + t.index() + " maxSize=" + t.maxSize() + " 必须为正");
			}
			if (t.name() == null || t.name().isEmpty()) {
				throw new InvalidSnapshot("队伍 " + t.index() + " 没有名字");
			}
			if (t.members() != null && t.members().size() > t.maxSize()) {
				throw new InvalidSnapshot("队伍 " + t.index() + " 成员 " + t.members().size()
						+ " 超过上限 " + t.maxSize());
			}
		}
		for (Session s : sessions) {
			if (s.id() == null) {
				throw new InvalidSnapshot("存在没有 UUID 的会话");
			}
			if (s.teamIndex() >= 0 && s.teamIndex() >= teams.size()) {
				throw new InvalidSnapshot("会话 " + s.name() + " 指向队伍 " + s.teamIndex()
						+ "，但只有 " + teams.size() + " 支队伍");
			}
			if (s.eliminated() && s.alive()) {
				throw new InvalidSnapshot("会话 " + s.name() + " 同时 alive 与 eliminated");
			}
		}
		if (phase.matchActive() && winnerTeamIndex >= teams.size()) {
			throw new InvalidSnapshot("胜者队伍 " + winnerTeamIndex + " 超出队伍数 " + teams.size());
		}
		return this;
	}

	/** 参战者 UUID 去重后的集合大小，给落盘日志与残留复检用。 */
	public int participantCount() {
		return (int) sessions.stream().filter(s -> s.teamIndex() >= 0).count();
	}
}
