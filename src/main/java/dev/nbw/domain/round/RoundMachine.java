package dev.nbw.domain.round;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 回合状态机（04 §3）。tick 驱动、单一所有权：阶段推进只经 {@link #tick()} 与显式事件，
 * 非法转移抛 {@link Transitions.IllegalTransition} 而不是静默忽略（04 §3）。
 *
 * 本类**不依赖 Minecraft**：阶段副作用（传送、游戏模式、发包）在 round/RoundManager 适配层。
 * 键一律用 UUID —— 复活会替换玩家实体对象（05 C21），UUID 才是稳定身份。
 */
public final class RoundMachine {

	/** 一次阶段转移（审计日志条目，也是适配层执行副作用的依据）。 */
	public record Transition(int matchIndex, int totalTick, Phase from, RoundEvent event, Phase to) {
	}

	/** 一局的结果（SETTLE 面板与 /cww state 的数据源）。 */
	public record Result(int matchIndex, int winnerTeamIndex, String winnerName, RoundEvent cause,
			int durationTicks, int participants, int survivors) {
		public boolean isDraw() {
			return winnerTeamIndex < 0;
		}

		public int durationSeconds() {
			return durationTicks / 20;
		}
	}

	private static final String[] TEAM_NAMES = {"赤队", "蓝队", "黄队", "绿队", "青队", "紫队", "橙队", "灰队"};

	private RoundConfig config;
	private boolean autoStart;
	private Phase phase = Phase.LOBBY;
	private int tickInPhase;
	private int totalTicks;
	private int matchIndex;
	private int matchStartTick;

	private final List<MatchTeam> teams = new ArrayList<>();
	private final Map<UUID, PlayerSession> sessions = new LinkedHashMap<>();
	private final List<UUID> queueOrder = new ArrayList<>();
	private final List<Transition> audit = new ArrayList<>();
	private final List<Transition> pendingEffects = new ArrayList<>();
	private final List<String> auditNotes = new ArrayList<>();

	/**
	 * 本局用的是哪张图、哪套赛制。状态机自己不去读配置文件，
	 * 这两个值由适配层在 {@code loadConfigs} 成功后写进来——
	 * 但它们是"这一局的身份"的一部分，所以必须随快照一起落盘，
	 * 否则重启时按默认规格恢复，队伍数对不上，恢复永远失败。
	 */
	private String arenaId = "";
	private String formatId = "";

	private int winnerTeamIndex = -1;
	private Result lastResult;
	private RoundEvent pendingCause;

	public RoundMachine(RoundConfig initialConfig) {
		this.config = initialConfig;
		// 构造时就建好队伍骨架。ensureTeams() 本来幂等，提前调用不改变任何行为
		// （空队不算 members），但它有两个必要效果：
		//   1) 快照里始终带队伍，restore() 的"队伍数 vs 规格"校验才不会把正常快照判成非法；
		//   2) /cww state 在 LOBBY 就能看到队伍骨架，而不是 teams=0 让人以为配置没载入。
		ensureTeams();
	}

	// ---------------------------------------------------------------- 查询

	public Phase phase() {
		return phase;
	}

	public int tickInPhase() {
		return tickInPhase;
	}

	public int totalTicks() {
		return totalTicks;
	}

	public int matchIndex() {
		return matchIndex;
	}

	public RoundConfig config() {
		return config;
	}

	/** 适配层载入 format/arena 后调用，把本局身份交给状态机。 */
	public void setConfigIds(String arena, String format) {
		this.arenaId = arena == null ? "" : arena;
		this.formatId = format == null ? "" : format;
	}

	public String arenaId() {
		return arenaId;
	}

	public String formatId() {
		return formatId;
	}

	public List<MatchTeam> teams() {
		return Collections.unmodifiableList(teams);
	}

	public Map<UUID, PlayerSession> sessions() {
		return Collections.unmodifiableMap(sessions);
	}

	public List<UUID> queue() {
		return Collections.unmodifiableList(queueOrder);
	}

	public List<Transition> audit() {
		return Collections.unmodifiableList(audit);
	}

	public List<String> auditNotes() {
		return Collections.unmodifiableList(auditNotes);
	}

	public Result lastResult() {
		return lastResult;
	}

	public int queuedCount() {
		return queueOrder.size();
	}

	public int aliveTeamCount() {
		int n = 0;
		for (MatchTeam t : teams) {
			if (aliveMembers(t) > 0) {
				n++;
			}
		}
		return n;
	}

	public int teamsWithMembers() {
		int n = 0;
		for (MatchTeam t : teams) {
			if (t.size() > 0) {
				n++;
			}
		}
		return n;
	}

	/** 适配层用来判断"这一局能不能真分出胜负"（只有 1 队有人的局不判胜，交给超时或管理员）。 */
	public boolean canDeclareWinner() {
		return teamsWithMembers() >= 2;
	}

	private int aliveMembers(MatchTeam team) {
		int n = 0;
		for (var s : team.membersOrdered()) {
			PlayerSession p = session(s);
			if (p != null && p.isAlive()) {
				n++;
			}
		}
		return n;
	}

	/** 取出并清空待执行的阶段副作用（适配层每 tick 调一次）。 */
	public List<Transition> drainPendingEffects() {
		if (pendingEffects.isEmpty()) {
			return Collections.emptyList();
		}
		List<Transition> out = new ArrayList<>(pendingEffects);
		pendingEffects.clear();
		return out;
	}

	// ---------------------------------------------------------------- 报名与分队

	/** 报名下一局；返回 null 表示成功，否则为可读的拒绝原因（04 §3：非法入口显式拒绝）。 */
	public String queue(UUID id, String name) {
		if (!phase.allowsEnrollment()) {
			return "本局进行中，禁止中途加入（回大厅等下一局）";
		}
		PlayerSession s = sessions.get(id);
		if (s != null && s.teamIndex() >= 0 && phase == Phase.ENROLL) {
			s.rename(name);
			return null;
		}
		if (s == null) {
			s = new PlayerSession(id, name, totalTicks);
			sessions.put(id, s);
		} else {
			s.rename(name);
			s.rejoinQueue(totalTicks);
		}
		// 上一局的存活/淘汰/掉线标记必须复位：否则 onDeath 的 eliminated 前置判断会让第二局起不再淘汰人
		s.setAlive(false);
		s.setEliminated(false);
		s.setEliminatedTick(-1);
		s.markReconnected();
		if (!queueOrder.contains(id)) {
			queueOrder.add(id);
		}
		return null;
	}

	/** 取消报名 / 离开队伍；PLAY 起不允许（01 §2 禁止中途退出影响balance）。 */
	public String unqueue(UUID id) {
		if (!phase.allowsEnrollment()) {
			return "已进入对局，无法退出报名";
		}
		PlayerSession s = sessions.get(id);
		if (s == null) {
			return "你没有报名";
		}
		if (s.teamIndex() >= 0 && phase == Phase.ENROLL) {
			MatchTeam t = teamAt(s.teamIndex());
			if (t != null) {
				t.remove(id);
			}
		}
		queueOrder.remove(id);
		sessions.remove(id);
		return null;
	}

	/** 自主分队（01 §2 [原方案]）：只在 LOBBY/ENROLL 生效。 */
	public String assignTeam(UUID id, int teamIndex) {
		if (!phase.allowsEnrollment()) {
			return "分队已锁定（阶段 " + phase + "）";
		}
		ensureTeams();
		MatchTeam target = teamAt(teamIndex);
		if (target == null) {
			return "队伍 " + teamIndex + " 不存在";
		}
		PlayerSession s = sessions.get(id);
		if (s == null) {
			return "先报名再选队：/cww join";
		}
		if (!queueOrder.contains(id)) {
			queueOrder.add(id);
		}
		if (s.teamIndex() == teamIndex) {
			return null;
		}
		if (target.isFull()) {
			return "队伍 " + target.name() + " 已满";
		}
		MatchTeam old = teamAt(s.teamIndex());
		if (old != null) {
			old.remove(id);
		}
		target.add(id);
		s.setTeam(teamIndex);
		return null;
	}

	/** 管理员调平/误操作救援（04 §10 /cww force）。 */
	public String forceTeam(UUID id, int teamIndex) {
		if (phase == Phase.PLAY || phase == Phase.SETTLE || phase == Phase.RETURN) {
			return "对局进行中不得改队（阶段 " + phase + "）";
		}
		return assignTeam(id, teamIndex);
	}

	public MatchTeam teamAt(int index) {
		if (index < 0 || index >= teams.size()) {
			return null;
		}
		return teams.get(index);
	}

	public PlayerSession session(UUID id) {
		return sessions.get(id);
	}

	// ---------------------------------------------------------------- 阶段推进

	public void setConfig(RoundConfig newConfig) {
		if (newConfig != null && phase == Phase.LOBBY) {
			this.config = newConfig;
		}
	}

	public void setAutoStart(boolean enabled) {
		this.autoStart = enabled;
	}

	public boolean autoStartEnabled() {
		return autoStart;
	}

	/** 报名达到下限（04 §3 的自动开局边）；未开自动开局时由管理员 /cww start 走同一条边。 */
	public boolean enrollmentReached() {
		return queueOrder.size() >= config.minPlayers();
	}

	public void tick() {
		totalTicks++;
		if (phase == Phase.LOBBY) {
			if (autoStart && enrollmentReached()) {
				apply(RoundEvent.START);
			}
			return;
		}
		tickInPhase++;
		switch (phase) {
			case ENROLL -> {
				if (tickInPhase >= config.enrollTicks()) {
					apply(RoundEvent.ENROLL_TIMEOUT);
				}
			}
			case READY -> {
				if (tickInPhase >= config.readyTicks()) {
					apply(RoundEvent.READY_TIMEOUT);
				}
			}
			case PLAY -> {
				expireAbandonedSeats();
				// 判胜放在 tick 末尾：同一 tick 内两队各自全灭时才是真平局，逐次死亡立即结算会误判赢家
				checkWin();
				if (tickInPhase >= config.playHardCapTicks()) {
					pendingCause = RoundEvent.MATCH_TIMEOUT;
					apply(RoundEvent.MATCH_TIMEOUT);
				}
			}
			case SETTLE -> {
				if (tickInPhase >= config.settleTicks()) {
					apply(RoundEvent.SETTLE_TIMEOUT);
				}
			}
			case RETURN -> {
				if (tickInPhase >= config.returnTicks()) {
					apply(RoundEvent.RETURN_TIMEOUT);
				}
			}
			default -> {
			}
		}
	}

	/** 管理员或测试驱动的显式入口；非法转移在这里被拦住，适配层不用 try/catch。 */
	public boolean fire(RoundEvent event) {
		if (!Transitions.can(phase, event)) {
			auditNotes.add("拒绝非法转移：" + phase + " + " + event);
			return false;
		}
		if (event == RoundEvent.WINNER_DECIDED) {
			pendingCause = event;
		}
		apply(event);
		return true;
	}

	/** 玩法判定入口：本局内玩家死亡（适配层在 AFTER_DEATH 里调用）。 */
	public void onDeath(UUID id) {
		PlayerSession s = sessions.get(id);
		if (s == null || phase != Phase.PLAY || s.isEliminated()) {
			return;
		}
		s.setAlive(false);
		s.setEliminated(true);
		s.setEliminatedTick(totalTicks);
	}

	/** 掉线席位到期（01 §2 A13：保席 90s，之后本局视为淘汰）。 */
	public void onSeatExpired(UUID id) {
		PlayerSession s = sessions.get(id);
		if (s == null || phase != Phase.PLAY) {
			return;
		}
		s.setAlive(false);
		s.setEliminated(true);
		s.setEliminatedTick(totalTicks);
		auditNotes.add("掉线席位到期视为淘汰：" + s.name());
	}

	public void onDisconnect(UUID id) {
		PlayerSession s = sessions.get(id);
		if (s != null && phase.matchActive()) {
			s.markDisconnected(totalTicks);
		}
	}

	public void onReconnect(UUID id, String name) {
		PlayerSession s = sessions.get(id);
		if (s != null) {
			s.markReconnected();
			s.rename(name);
		}
	}

	private void expireAbandonedSeats() {
		for (PlayerSession s : new ArrayList<>(sessions.values())) {
			if (s.seatExpired(totalTicks)) {
				s.markReconnected();
				onSeatExpired(s.id());
			}
		}
	}

	/** 最后存活队获胜（D5）；不足 2 队有人时不判胜（避免"1 队局开局即结束"）。 */
	public void checkWin() {
		if (phase != Phase.PLAY || !canDeclareWinner()) {
			return;
		}
		if (aliveTeamCount() <= 1) {
			pendingCause = RoundEvent.WINNER_DECIDED;
			apply(RoundEvent.WINNER_DECIDED);
		}
	}

	private void apply(RoundEvent event) {
		Phase next = Transitions.next(phase, event);
		Phase prev = phase;
		Transition t = new Transition(matchIndex, totalTicks, prev, event, next);
		audit.add(t);
		pendingEffects.add(t);
		phase = next;
		tickInPhase = 0;
		onEnter(next, prev, event);
	}

	private void onEnter(Phase next, Phase prev, RoundEvent event) {
		switch (next) {
			case ENROLL -> {
				matchIndex++;
				ensureTeams();
				distributeQueue();
				matchStartTick = totalTicks;
				winnerTeamIndex = -1;
				lastResult = null;
			}
			case READY -> {
				balanceAndLock();
				for (PlayerSession s : sessions.values()) {
					if (s.teamIndex() >= 0) {
						openSeat(s);
					}
				}
			}
			case PLAY -> {
				for (PlayerSession s : sessions.values()) {
					if (s.teamIndex() >= 0) {
						openSeat(s);
					}
				}
			}
			case SETTLE -> computeResult(event);
			case RETURN, LOBBY -> {
				if (prev != Phase.LOBBY) {
					clearMatchState();
				}
			}
		}
	}

	/** 幂等建队：规格没变就不动已有分队（管理员在 LOBBY 的预分队要能留到 ENROLL）。 */
	private void ensureTeams() {
		boolean shapeMatches = teams.size() == config.teamCount();
		if (shapeMatches) {
			for (MatchTeam t : teams) {
				if (t.maxSize() != config.teamSize()) {
					shapeMatches = false;
					break;
				}
			}
		}
		if (shapeMatches) {
			return;
		}
		List<List<UUID>> previous = new ArrayList<>();
		for (MatchTeam t : teams) {
			previous.add(t.membersOrdered());
		}
		teams.clear();
		for (int i = 0; i < config.teamCount(); i++) {
			String name = i < TEAM_NAMES.length ? TEAM_NAMES[i] : ("队伍" + (i + 1));
			teams.add(new MatchTeam(i, name, config.teamSize()));
		}
		for (int i = 0; i < previous.size(); i++) {
			MatchTeam target = teamAt(Math.min(i, teams.size() - 1));
			for (UUID member : previous.get(i)) {
				PlayerSession s = sessions.get(member);
				if (target == null || s == null || target.add(member) != null) {
					if (s != null) {
						s.setTeam(-1);
						auditNotes.add("赛制变更后需重新分队：" + s.name());
					}
					if (!queueOrder.contains(member)) {
						queueOrder.add(member);
					}
				} else {
					s.setTeam(target.index());
				}
			}
		}
	}

	/** 进入对局时把一个席位开到"存活"状态（上一局的淘汰/掉线标记一并复位）。 */
	private void openSeat(PlayerSession s) {
		s.setAlive(true);
		s.setEliminated(false);
		s.setEliminatedTick(-1);
		s.markReconnected();
	}

	/** 先申请先进队（01 §2）：按报名顺序轮流填到当前人最少的队，容量满则留在报名列。 */
	private void distributeQueue() {
		for (UUID id : new ArrayList<>(queueOrder)) {
			PlayerSession s = sessions.get(id);
			if (s == null || s.teamIndex() >= 0) {
				continue;
			}
			MatchTeam target = leastFilled();
			if (target == null) {
				auditNotes.add("容量不足，未能入队：" + s.name());
				continue;
			}
			target.add(id);
			s.setTeam(target.index());
		}
	}

	private MatchTeam leastFilled() {
		MatchTeam best = null;
		for (MatchTeam t : teams) {
			if (t.isFull()) {
				continue;
			}
			if (best == null || t.size() < best.size()) {
				best = t;
			}
		}
		return best;
	}

	/** READY 前的补位/调平：容量内填满，超出的转观战。 */
	private void balanceAndLock() {
		distributeQueue();
		for (UUID id : new ArrayList<>(queueOrder)) {
			PlayerSession s = sessions.get(id);
			if (s != null && s.teamIndex() < 0) {
				queueOrder.remove(id);
				sessions.remove(id);
				auditNotes.add("容量已满，转观战：" + (s.name()));
			}
		}
	}

	private void computeResult(RoundEvent event) {
		int winner = -1;
		// 只有"恰好 1 队还活着"才算胜者；超时/管理员终止时若多队并存，一律平局，不能任意挑一队当赢家
		if (canDeclareWinner() && aliveTeamCount() == 1) {
			for (MatchTeam t : teams) {
				if (aliveMembers(t) > 0) {
					winner = t.index();
				}
			}
		}
		winnerTeamIndex = winner;
		int participants = 0;
		int survivors = 0;
		for (PlayerSession s : sessions.values()) {
			if (s.teamIndex() >= 0) {
				participants++;
				if (s.isAlive()) {
					survivors++;
				}
			}
		}
		String winnerName = winner >= 0 && teamAt(winner) != null ? teamAt(winner).name() : "无（平局/未分出胜负）";
		lastResult = new Result(matchIndex, winner, winnerName, event, totalTicks - matchStartTick, participants, survivors);
	}

	/**
	 * 局后清理（01 §2 A13 / S10）。domain 侧的残留在这里归零；
	 * MC 侧（物品、效果、游戏模式）由适配层在 RETURN 阶段执行并复检。
	 */
	private void clearMatchState() {
		for (MatchTeam t : teams) {
			t.clear();
		}
		teams.clear();
		sessions.clear();
		queueOrder.clear();
		winnerTeamIndex = -1;
		pendingCause = null;
		tickInPhase = 0;
	}

	public RoundEvent lastCause() {
		return pendingCause;
	}

	/** S10 断言：局后阶段（RETURN 清理之后 / LOBBY）domain 侧必须 0 残留。 */
	public List<String> residueReport() {
		List<String> residue = new ArrayList<>();
		if (phase != Phase.LOBBY && phase != Phase.RETURN) {
			// SETTLE 期间合法持有结果与淘汰记录（结算面板要用），那时查残留会误报
			residue.add("残留断言只适用于 RETURN/LOBBY，当前阶段 " + phase);
			return residue;
		}
		for (PlayerSession s : sessions.values()) {
			if (s.isAlive()) {
				residue.add("席位仍标记存活（对局本应已结束）：" + s.name());
			}
			if (s.isEliminated()) {
				residue.add("淘汰标记未复位：" + s.name());
			}
			if (s.eliminatedTick() >= 0) {
				residue.add("淘汰时刻未复位：" + s.name());
			}
			if (s.isDisconnected()) {
				residue.add("掉线时刻未复位：" + s.name());
			}
			if (!queueOrder.contains(s.id())) {
				residue.add("有会话但不在报名列（幽灵席位）：" + s.name());
			}
		}
		for (UUID queued : queueOrder) {
			if (sessions.get(queued) == null) {
				residue.add("报名列里有无会话成员：" + queued);
			}
		}
		if (tickInPhase != 0) {
			residue.add("阶段计时未归零：" + tickInPhase);
		}
		if (winnerTeamIndex >= 0) {
			residue.add("胜负标记未清：" + winnerTeamIndex);
		}
		if (phase.allowsPvp() || phase.allowsBuild()) {
			residue.add("LOBBY 不应开放 PVP/建造权限");
		}
		return residue;
	}

	/** /cww state dump（运维定位用，04 §10）。 */
	public String dump() {
		StringBuilder sb = new StringBuilder();
		sb.append("phase=").append(phase).append(" tickInPhase=").append(tickInPhase)
				.append(" totalTicks=").append(totalTicks).append(" match=").append(matchIndex)
				.append(" spec=").append(config.teamCount()).append("x").append(config.teamSize())
				.append(" min=").append(config.minPlayers()).append(" hardCap=").append(config.playHardCapTicks())
				.append("t autoStart=").append(autoStart).append('\n');
		sb.append("queue=").append(queueOrder.size()).append(" teams=").append(teams.size()).append('\n');
		for (MatchTeam t : teams) {
			List<String> names = new ArrayList<>();
			for (UUID id : t.membersOrdered()) {
				PlayerSession s = sessions.get(id);
				if (s != null) {
					String mark = phase.matchActive() ? (s.isAlive() ? "" : (s.isEliminated() ? "[淘汰]" : "[失联]")) : "";
					names.add(s.name() + mark);
				}
			}
			sb.append("  T").append(t.index()).append(' ').append(t.name()).append(" ").append(names).append('\n');
		}
		if (lastResult != null) {
			sb.append("lastResult=match").append(lastResult.matchIndex()).append(" winner=")
					.append(lastResult.winnerName()).append(" cause=").append(lastResult.cause())
					.append(" dur=").append(lastResult.durationSeconds()).append("s")
					.append(" participants=").append(lastResult.participants())
					.append(" survivors=").append(lastResult.survivors()).append('\n');
		}
		if (!audit.isEmpty()) {
			Transition last = audit.get(audit.size() - 1);
			sb.append("lastTransition=").append(last.from()).append(" --").append(last.event())
					.append("--> ").append(last.to()).append(" @tick").append(last.totalTick()).append('\n');
		}
		return sb.toString();
	}


	// ---------------------------------------------------------------- 快照与恢复（03 §6.1）

	/**
	 * 导出当前状态的可序列化快照。
	 *
	 * <p>审计日志只保留尾部 {@code auditKeep} 条：全量落盘会让每次转移都重写整段历史，
	 * 一晚上几百局之后文件比世界数据还大，而排查问题时真正有用的只是最近那几十条。
	 */
	public RoundSnapshot snapshot(int auditKeep) {
		List<RoundSnapshot.Team> ts = new ArrayList<>(teams.size());
		for (MatchTeam t : teams) {
			ts.add(new RoundSnapshot.Team(t.index(), t.name(), t.maxSize(), t.membersOrdered()));
		}
		List<RoundSnapshot.Session> ss = new ArrayList<>(sessions.size());
		for (PlayerSession s : sessions.values()) {
			ss.add(new RoundSnapshot.Session(s.id(), s.name(), s.teamIndex(), s.isAlive(),
					s.isEliminated(), s.eliminatedTick(), s.disconnectTick(), s.joinedQueueTick()));
		}
		List<Transition> tail = audit.size() <= auditKeep
				? new ArrayList<>(audit)
				: new ArrayList<>(audit.subList(audit.size() - auditKeep, audit.size()));
		return new RoundSnapshot(phase, tickInPhase, totalTicks, matchIndex, matchStartTick,
				autoStart, winnerTeamIndex, ts, ss, new ArrayList<>(queueOrder),
				tail, new ArrayList<>(auditNotes()), arenaId, formatId);
	}

	/**
	 * 从快照恢复。调用方必须<b>先</b>把 config 设成与快照相配的规格，否则这里会抛。
	 *
	 * <p>为什么宁可抛也不"尽力恢复"：运维改了 format 的队伍数再重启，是重启恢复最常见的破坏源。
	 * 此时若默默按旧快照建 4 队、而新规格只有 2 队，胜负判定与圈层会一起按错误前提跑完整局，
	 * 而且表面上"恢复成功"。宁可起不来。
	 */
	public void restore(RoundSnapshot snap) {
		snap.validate();
		if (snap.teams().isEmpty()) {
			// 一局打完后 clearMatchState() 会把队伍清空，所以"0 支队伍"是 LOBBY 的正常形态，
			// 不是损坏。按当前规格重建即可——把它当非法快照拒绝，等于每次打完一局重启都恢复失败。
			ensureTeams();
		} else if (snap.teams().size() != config.teamCount()) {
			throw new RoundSnapshot.InvalidSnapshot("快照里有 " + snap.teams().size()
					+ " 支队伍，当前规格是 " + config.teamCount() + " 支（format 改过就先 /cww format 再重启）");
		}
		teams.clear();
		for (RoundSnapshot.Team t : snap.teams()) {
			MatchTeam team = new MatchTeam(t.index(), t.name(), t.maxSize());
			for (java.util.UUID id : t.members()) {
				team.add(id);
			}
			teams.add(team);
		}
		sessions.clear();
		for (RoundSnapshot.Session s : snap.sessions()) {
			PlayerSession ps = new PlayerSession(s.id(), s.name(), s.joinedQueueTick());
			ps.setTeam(s.teamIndex());
			ps.setAlive(s.alive());
			ps.setEliminated(s.eliminated());
			ps.setEliminatedTick(s.eliminatedTick());
			if (s.disconnectTick() >= 0) {
				ps.markDisconnected(s.disconnectTick());
			} else {
				ps.markReconnected();
			}
			sessions.put(s.id(), ps);
		}
		queueOrder.clear();
		queueOrder.addAll(snap.queue());
		audit.clear();
		audit.addAll(snap.audit());
		auditNotes.clear();
		auditNotes.addAll(snap.auditNotes());
		arenaId = snap.arenaId();
		formatId = snap.formatId();
		pendingEffects.clear();   // 见 RoundSnapshot 的类注释：旧副作用绝不回放

		phase = snap.phase();
		tickInPhase = snap.tickInPhase();
		totalTicks = snap.totalTicks();
		matchIndex = snap.matchIndex();
		matchStartTick = snap.matchStartTick();
		autoStart = snap.autoStart();
		winnerTeamIndex = snap.winnerTeamIndex();
		pendingCause = null;
	}
}
