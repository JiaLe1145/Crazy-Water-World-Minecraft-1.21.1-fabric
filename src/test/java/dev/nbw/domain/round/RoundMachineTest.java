package dev.nbw.domain.round;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 回合状态机回归（07 §6.1 第 1 层：纯逻辑、不起服务端）。
 * 阶段时长压到 2 tick，让"超时链"在毫秒级跑完；圈层数值另见 ZoneModelTest。
 */
class RoundMachineTest {

	private static RoundConfig cfg(int teams, int teamSize, int minPlayers, int playCapTicks) {
		return new RoundConfig(teams, teamSize, minPlayers, 2, 2, playCapTicks, 2, 2);
	}

	private static UUID id(int seed) {
		return UUID.nameUUIDFromBytes(new byte[]{(byte) seed});
	}

	/** 报名 n 人并强制开局（管理员路径），返回玩家 id 列表。 */
	private static List<UUID> startMatch(RoundMachine m, int n) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			UUID p = id(100 + i + n * 1000);
			ids.add(p);
			assertNull(m.queue(p, "p" + i), "报名不应被拒");
		}
		assertTrue(m.fire(RoundEvent.START), "LOBBY --START--> ENROLL 必须合法");
		return ids;
	}

	private static void tickUntil(RoundMachine m, Phase expected, int maxTicks) {
		for (int i = 0; i < maxTicks; i++) {
			if (m.phase() == expected) {
				return;
			}
			m.tick();
		}
		fail("超过 " + maxTicks + " tick 仍未进入 " + expected + "，当前阶段 " + m.phase());
	}

	private static void runToPlay(RoundMachine m) {
		tickUntil(m, Phase.PLAY, 40);
	}

	// ---------------------------------------------------------------- 转移表与超时链

	@Test
	void happyPathWalksEveryPhaseInOrder() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		startMatch(m, 4);
		tickUntil(m, Phase.READY, 10);
		tickUntil(m, Phase.PLAY, 10);
		assertTrue(m.fire(RoundEvent.WINNER_DECIDED));
		tickUntil(m, Phase.RETURN, 10);
		tickUntil(m, Phase.LOBBY, 10);
		assertEquals(List.of(Phase.ENROLL, Phase.READY, Phase.PLAY, Phase.SETTLE, Phase.RETURN, Phase.LOBBY),
				m.audit().stream().map(RoundMachine.Transition::to).toList());
		assertEquals(0, m.tickInPhase(), "回到 LOBBY 后阶段计时必须归零");
	}

	@Test
	void everyPhaseTransitionIsExactlyOneEdge() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		startMatch(m, 4);
		runToPlay(m);
		// PLAY 里 START/ENROLL_TIMEOUT 这些"别的阶段的边"都必须非法
		assertFalse(Transitions.can(Phase.PLAY, RoundEvent.START));
		assertFalse(Transitions.can(Phase.PLAY, RoundEvent.ENROLL_TIMEOUT));
		assertFalse(Transitions.can(Phase.READY, RoundEvent.WINNER_DECIDED), "READY 就判胜要拒绝（队伍还没进图）");
		assertFalse(Transitions.can(Phase.LOBBY, RoundEvent.RETURN_TIMEOUT));
		assertTrue(Transitions.can(Phase.LOBBY, RoundEvent.START));
	}

	@Test
	void illegalTransitionThrowsInsteadOfBeingSwallowed() {
		// 04 §3：不该发生的转换要显式失败，不能静默"保持原阶段"
		assertThrows(Transitions.IllegalTransition.class, () -> Transitions.next(Phase.LOBBY, RoundEvent.WINNER_DECIDED));
		assertThrows(Transitions.IllegalTransition.class, () -> Transitions.next(Phase.PLAY, RoundEvent.START));
		assertThrows(Transitions.IllegalTransition.class, () -> Transitions.next(Phase.RETURN, RoundEvent.ENROLL_TIMEOUT));
		assertTrue(Transitions.can(Phase.PLAY, RoundEvent.ADMIN_STOP));
		assertFalse(Transitions.can(Phase.LOBBY, RoundEvent.ADMIN_STOP));
	}

	@Test
	void fireFromWrongPhaseIsRejectedAndRecorded() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		assertFalse(m.fire(RoundEvent.SETTLE_TIMEOUT), "LOBBY 里结算超时必须被拒");
		assertEquals(Phase.LOBBY, m.phase());
		assertTrue(m.auditNotes().stream().anyMatch(s -> s.contains("拒绝非法转移")));
		assertTrue(m.audit().isEmpty(), "被拒的转移不得写入审计");
	}

	@Test
	void abortFromEnrollAndReadyBothReturnToLobby() {
		for (int ticksBeforeAbort : new int[]{0, 2}) {
			RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
			startMatch(m, 4);
			for (int i = 0; i < ticksBeforeAbort; i++) {
				m.tick();
			}
			assertTrue(m.fire(RoundEvent.ADMIN_ABORT), "ABORT 在 ENROLL/READY 都合法（回大厅不判胜负）");
			assertEquals(Phase.LOBBY, m.phase());
			assertTrue(m.residueReport().isEmpty());
		}
	}

	// ---------------------------------------------------------------- 报名与分队

	@Test
	void autoStartFiresOnlyWhenEnrollmentReachesMin() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		m.setAutoStart(true);
		m.queue(id(1), "a");
		m.queue(id(2), "b");
		m.queue(id(3), "c");
		assertFalse(m.enrollmentReached(), "3 人 < min 4，不满足下限");
		m.tick();
		assertEquals(Phase.LOBBY, m.phase(), "未达下限不能自动开局");
		m.queue(id(4), "d");
		assertTrue(m.enrollmentReached());
		m.tick();
		assertEquals(Phase.ENROLL, m.phase(), "达下限后下一 tick 自动开局（04 §3 的 enroll≥min 边）");
	}

	@Test
	void autoStartOffKeepsLobbyUntilAdminStarts() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		for (int i = 0; i < 8; i++) {
			m.queue(id(20 + i), "p" + i);
		}
		for (int i = 0; i < 20; i++) {
			m.tick();
		}
		assertEquals(Phase.LOBBY, m.phase(), "默认不自动开局：管理员要先选赛制（见 DECISIONS D-R1）");
		assertTrue(m.fire(RoundEvent.START));
		assertEquals(Phase.ENROLL, m.phase());
	}

	@Test
	void enrollmentFillsLeastFilledTeamFirstComeFirstServed() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		List<UUID> ids = startMatch(m, 4);
		assertEquals(2, m.teams().get(0).size(), "人数不均要自动调平（01 §2）");
		assertEquals(2, m.teams().get(1).size());
		assertTrue(m.teams().get(0).contains(ids.get(0)), "先申请先进队：第 1 人进 T0");
		assertTrue(m.teams().get(1).contains(ids.get(1)), "第 2 人补最空的 T1");
		assertTrue(m.teams().get(0).contains(ids.get(2)));
		assertTrue(m.teams().get(1).contains(ids.get(3)));
	}

	@Test
	void selfSelectTeamRequiresQueueAndRejectsFullTeam() {
		RoundMachine m = new RoundMachine(cfg(2, 1, 2, 200));
		UUID a = id(301);
		UUID b = id(302);
		UUID c = id(303);
		m.queue(a, "a");
		m.queue(b, "b");
		assertTrue(m.fire(RoundEvent.START));
		assertNotNull(m.assignTeam(c, 1), "未报名直接选队要拒绝");
		assertNull(m.session(c));
		m.queue(c, "c");
		assertNotNull(m.assignTeam(c, 0), "T0 只有 1 个名额且已被占，必须拒绝");
		assertTrue(m.assignTeam(c, 0).contains("已满"));
		assertEquals(-1, m.session(c).teamIndex(), "被拒的人不能凭空进队");
		assertNull(m.assignTeam(a, 0), "已经在自己队里是幂等操作");
	}

	@Test
	void teamChangesLockAtReady() {
		RoundMachine m = new RoundMachine(cfg(2, 3, 4, 200));
		List<UUID> ids = startMatch(m, 4);
		UUID mover = ids.get(0);
		int original = m.session(mover).teamIndex();
		assertNull(m.assignTeam(mover, 1 - original), "ENROLL 里允许自主改队");
		runToPlay(m);
		assertNotNull(m.assignTeam(mover, original), "PLAY 中禁止改队（04 §2 不变式 4）");
		assertNotNull(m.forceTeam(mover, original), "管理员 force 也不能在 PLAY 改队");
		assertEquals(1 - original, m.session(mover).teamIndex());
	}

	@Test
	void midMatchJoinIsRejected() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		startMatch(m, 4);
		runToPlay(m);
		assertNotNull(m.queue(id(999), "latecomer"), "禁止中途加入（01 §2 A13）");
		assertEquals(4, m.sessions().size());
		assertNotNull(m.unqueue(id(999)));
	}

	@Test
	void overflowApplicantsBecomeSpectatorsNotCrash() {
		RoundMachine m = new RoundMachine(cfg(2, 1, 2, 200));
		List<UUID> ids = startMatch(m, 4);
		runToPlay(m);
		assertEquals(2, m.sessions().size(), "超出容量的 2 人转观战，不能被算进对局（01 §2）");
		assertNull(m.session(ids.get(2)), "观战者不持有会话");
		assertTrue(m.auditNotes().stream().anyMatch(s -> s.contains("转观战")));
	}

	@Test
	void unqueueBeforeStartRemovesPlayerFromQueue() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		UUID a = id(401);
		m.queue(a, "a");
		m.queue(id(402), "b");
		assertNull(m.unqueue(a));
		assertEquals(1, m.queuedCount());
		assertNull(m.session(a), "取消报名要连会话一起清，否则残留");
	}

	// ---------------------------------------------------------------- 胜负判定

	@Test
	void lastTeamStandingWins() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		startMatch(m, 4);
		runToPlay(m);
		int t0 = m.session(m.teams().get(0).membersOrdered().get(0)).teamIndex();
		List<UUID> losers = m.teams().get(t0).membersOrdered();
		assertTrue(m.canDeclareWinner(), "两队都有人才能判胜");
		m.onDeath(losers.get(0));
		m.tick();
		assertEquals(Phase.PLAY, m.phase(), "只淘汰 1 人还不能判胜");
		m.onDeath(losers.get(1));
		assertEquals(Phase.PLAY, m.phase(), "判胜在 tick 末尾结算，同 tick 内不立即结束");
		m.tick();
		assertEquals(Phase.SETTLE, m.phase(), "全队淘汰即成立最后存活队（D5）");
		RoundMachine.Result r = m.lastResult();
		assertEquals(1 - t0, r.winnerTeamIndex(), "胜者必须是唯一还剩存活的那队");
		assertEquals(RoundEvent.WINNER_DECIDED, r.cause());
		assertFalse(r.isDraw());
		assertEquals(4, r.participants());
		assertEquals(2, r.survivors());
	}

	@Test
	void singleTeamMatchNeverEndsByWinCheck() {
		RoundMachine m = new RoundMachine(cfg(3, 2, 1, 200));
		UUID a = id(501);
		m.queue(a, "a");
		m.fire(RoundEvent.START);
		runToPlay(m);
		assertEquals(1, m.teamsWithMembers(), "只有 T0 有人");
		assertFalse(m.canDeclareWinner(), "只有 1 队有人的局不能判胜");
		m.onDeath(a);
		tickUntil(m, Phase.LOBBY, 400);
		assertEquals(Phase.LOBBY, m.phase(), "独苗局全灭只能靠硬上限结束，不能凭空判一个赢家");
		assertTrue(m.lastResult().isDraw());
	}

	@Test
	void drawWhenEveryTeamWipedSameTick() {
		RoundMachine m = new RoundMachine(cfg(2, 1, 2, 200));
		List<UUID> ids = startMatch(m, 2);
		runToPlay(m);
		m.onDeath(ids.get(0));
		m.onDeath(ids.get(1));
		assertEquals(Phase.PLAY, m.phase(), "还没结算就不能先判一队赢");
		m.tick();
		assertEquals(Phase.SETTLE, m.phase());
		assertTrue(m.lastResult().isDraw(), "同归于尽要写成平局，不能随机给一个赢家");
		assertEquals(0, m.lastResult().survivors());
	}

	@Test
	void matchTimeoutEndsMatch() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 6));
		startMatch(m, 4);
		runToPlay(m);
		tickUntil(m, Phase.SETTLE, 40);
		assertEquals(RoundEvent.MATCH_TIMEOUT, m.lastResult().cause(), "超过硬上限必须结束（05 R1 拖局兜底）");
	}

	@Test
	void winnerSurvivesAcrossTwoMatches() {
		RoundMachine m = new RoundMachine(cfg(2, 1, 2, 200));
		for (int round = 0; round < 2; round++) {
			List<UUID> ids = startMatch(m, 2);
			runToPlay(m);
			m.onDeath(ids.get(0));
			tickUntil(m, Phase.SETTLE, 10);
			assertEquals(Phase.SETTLE, m.phase(), "第 " + round + " 局应判胜");
			assertFalse(m.lastResult().isDraw());
			assertEquals(m.session(ids.get(1)).teamIndex(), m.lastResult().winnerTeamIndex(),
					"胜者必须是唯一存活那名玩家所在的队");
			tickUntil(m, Phase.LOBBY, 40);
			assertTrue(m.residueReport().isEmpty(), "第 " + round + " 局结束后必须 0 残留");
		}
		assertEquals(2, m.matchIndex());
	}

	// ---------------------------------------------------------------- 掉线席位

	@Test
	void disconnectKeepsSeatThenExpiresIntoElimination() {
		RoundMachine m = new RoundMachine(cfg(2, 1, 2, 10_000));
		List<UUID> ids = startMatch(m, 2);
		runToPlay(m);
		m.onDisconnect(ids.get(0));
		assertEquals(Phase.PLAY, m.phase(), "掉线当 tick 不能立刻算淘汰");
		tickUntil(m, Phase.SETTLE, PlayerSession.SEAT_HOLD_TICKS + 20);
		assertTrue(m.session(ids.get(0)).isEliminated(), "90s 席位到期后本局视为淘汰（01 §2 A13）");
		assertTrue(m.auditNotes().stream().anyMatch(s -> s.contains("掉线席位到期")));
	}

	@Test
	void reconnectWithinSeatWindowRestoresPlayer() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 10_000));
		List<UUID> ids = startMatch(m, 4);
		runToPlay(m);
		m.onDisconnect(ids.get(0));
		for (int i = 0; i < 100; i++) {
			m.tick();
		}
		m.onReconnect(ids.get(0), "p0");
		assertFalse(m.session(ids.get(0)).isEliminated(), "重连回来不能被误判淘汰");
		assertFalse(m.session(ids.get(0)).isDisconnected());
		assertEquals(Phase.PLAY, m.phase());
	}

	/**
	 * 05 C21 的 domain 侧防线：会话以 UUID 为键，实体对象被替换不影响身份。
	 * oldPlayer→newPlayer 的真实迁移在适配层，用服务端用例覆盖。
	 */
	@Test
	void sessionIdentityIsUuidNotEntityObject() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		List<UUID> ids = startMatch(m, 4);
		UUID a = ids.get(0);
		runToPlay(m);
		m.onDeath(a);
		assertNotNull(m.session(a), "淘汰也不能丢身份，结算面板要能列出谁死在哪一步");
		assertTrue(m.session(a).isEliminated());
		assertTrue(m.audit().size() >= 1);
	}

	// ---------------------------------------------------------------- 清理与残留（S10）

	@Test
	void residueReportIsCleanOnlyAfterFullCycle() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		startMatch(m, 4);
		assertFalse(m.residueReport().isEmpty(), "对局中调用必须报警（证明断言不是恒真好）");
		runToPlay(m);
		tickUntil(m, Phase.LOBBY, 300);
		assertTrue(m.residueReport().isEmpty(), "S10 断言：一局走完必须 0 残留，实际：" + m.residueReport());
		assertEquals(0, m.teams().size());
		assertEquals(0, m.sessions().size());
		assertEquals(0, m.queuedCount());
	}

	@Test
	void abortFromPlayClearsEverythingWithoutLeavingState() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 10_000));
		startMatch(m, 4);
		runToPlay(m);
		assertTrue(m.fire(RoundEvent.ADMIN_ABORT));
		assertEquals(Phase.LOBBY, m.phase(), "紧急中止不判胜负，直接回大厅（04 §10 abort）");
		assertTrue(m.residueReport().isEmpty(), "中止同样要 0 残留");
		assertTrue(m.audit().stream().noneMatch(t -> t.to() == Phase.SETTLE), "中止不经过结算，不留胜者记录");
	}

	@Test
	void adminStopInPlayGoesThroughSettle() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 10_000));
		startMatch(m, 4);
		runToPlay(m);
		assertTrue(m.fire(RoundEvent.ADMIN_STOP));
		assertEquals(Phase.SETTLE, m.phase());
		assertEquals(RoundEvent.ADMIN_STOP, m.lastResult().cause(), "正常终止仍要出结算面板");
	}

	@Test
	void fiveMatchesInARowStayClean() {
		// Gate G1：连开 5 局无报错、无状态残留
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		for (int round = 1; round <= 5; round++) {
			startMatch(m, 4);
			runToPlay(m);
			tickUntil(m, Phase.LOBBY, 400);
			assertTrue(m.residueReport().isEmpty(), "第 " + round + " 局残留：" + m.residueReport());
			assertEquals(round, m.matchIndex());
		}
	}

	@Test
	void configCannotBeChangedMidMatch() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		m.setConfig(new RoundConfig(6, 3, 4, 20, 20, 20, 20, 20));
		assertEquals(6, m.config().teamCount(), "LOBBY 里允许改赛制");
		startMatch(m, 4);
		m.setConfig(new RoundConfig(2, 2, 4, 20, 20, 20, 20, 20));
		assertEquals(6, m.config().teamCount(), "对局中禁止改规格（对局中不许替换在用的 arena/format）");
	}

	// ---------------------------------------------------------------- 阶段权限

	@Test
	void phasePermissionMatrix() {
		// 04 §3：PVP / 建造 / 物品栏只在 PLAY 生效，其余阶段强制关闭
		for (Phase p : Phase.values()) {
			assertEquals(p == Phase.PLAY, p.allowsPvp(), p.name());
			assertEquals(p == Phase.PLAY, p.allowsBuild(), p.name());
			assertEquals(p == Phase.PLAY, p.allowsInventoryEdit(), p.name());
			assertEquals(p == Phase.READY || p == Phase.PLAY || p == Phase.SETTLE, p.matchActive(), p.name());
			assertEquals(p == Phase.LOBBY || p == Phase.ENROLL, p.allowsEnrollment(), p.name());
		}
	}

	@Test
	void drainPendingEffectsHandsOneTransitionPerPhaseChange() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		m.queue(id(601), "a");
		m.queue(id(602), "b");
		assertTrue(m.drainPendingEffects().isEmpty());
		m.fire(RoundEvent.START);
		List<RoundMachine.Transition> effects = m.drainPendingEffects();
		assertEquals(1, effects.size());
		assertEquals(Phase.LOBBY, effects.get(0).from());
		assertEquals(Phase.ENROLL, effects.get(0).to());
		assertTrue(m.drainPendingEffects().isEmpty(), "副作用必须一次性消费，否则适配层会重复传送");
	}

	@Test
	void dumpIsReadableForOps() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		m.queue(id(701), "alice");
		m.fire(RoundEvent.START);
		String d = m.dump();
		assertTrue(d.contains("phase=ENROLL"), d);
		assertTrue(d.contains("赤队"), d);
		assertTrue(d.contains("alice"), d);
	}

	@Test
	void adminCanPreAssignTeamsWhileStillInLobby() {
		// 实测踩过的坑：队伍只在进 ENROLL 时创建，导致 LOBBY 里 /cww force 一律"队伍不存在"
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		UUID a = id(811);
		UUID b = id(812);
		UUID c = id(813);
		m.queue(a, "a");
		m.queue(b, "b");
		assertNull(m.forceTeam(a, 1), "LOBBY 里就该能预分队");
		assertNull(m.assignTeam(b, 1));
		assertEquals(1, m.session(a).teamIndex());
		assertTrue(m.teams().get(1).contains(a));
		assertTrue(m.teams().get(1).contains(b));
		m.queue(c, "c");
		assertTrue(m.fire(RoundEvent.START));
		assertTrue(m.teams().get(1).contains(a), "预分好的队伍不能被开局重建冲掉");
		assertTrue(m.teams().get(1).contains(b));
		assertEquals(0, m.session(c).teamIndex(), "没预分的人按补位规则进最空的队");
	}

	@Test
	void formatChangeKeepsFittableMembersAndNotesTheRest() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 2, 200));
		UUID a = id(821);
		UUID b = id(822);
		UUID c = id(823);
		m.queue(a, "a");
		m.queue(b, "b");
		m.queue(c, "c");
		m.forceTeam(a, 0);
		m.forceTeam(b, 0);
		m.forceTeam(c, 1);
		m.setConfig(new RoundConfig(2, 1, 2, 2, 2, 200, 2, 2)); // 改成每队 1 人
		assertTrue(m.fire(RoundEvent.START));
		assertTrue(m.teams().get(0).contains(a), "放得下的成员要保留");
		assertFalse(m.teams().get(0).contains(b), "超出新容量的不能挤进去");
		assertTrue(m.auditNotes().stream().anyMatch(s -> s.contains("赛制变更")), b + " 应被提示需重新分队");
	}

	@Test
	void preAssignedTeamsAreNotReportedAsResidue() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		UUID a = id(831);
		m.queue(a, "a");
		assertNull(m.forceTeam(a, 1));
		assertTrue(m.residueReport().isEmpty(), "LOBBY 里的预分队是正常状态，不是残留：" + m.residueReport());
	}

	@Test
	void eliminationFlagsResetBetweenMatches() {
		// 回归：eliminated 标记若不跨局复位，第二局里第一局死过的人再也"死不掉"，判胜静默失效
		RoundMachine m = new RoundMachine(cfg(2, 1, 2, 200));
		for (int round = 1; round <= 2; round++) {
			List<UUID> ids = startMatch(m, 2);
			runToPlay(m);
			UUID victim = m.teams().get(0).membersOrdered().get(0);
			m.onDeath(victim);
			tickUntil(m, Phase.SETTLE, 10);
			assertEquals(Phase.SETTLE, m.phase(), "第 " + round + " 局应正常判胜（说明淘汰标记已复位）");
			assertFalse(m.lastResult().isDraw());
			tickUntil(m, Phase.LOBBY, 40);
		}
		assertEquals(2, m.matchIndex());
	}

	// ---------------------------------------------------------------- 报名期的幽灵席位（#41 根因）

	@Test
	void releaseAbsentFreesSeatSoLaterAssignmentSucceeds() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 1, 20));
		UUID ghost = id(1);
		UUID keeper = id(2);
		UUID latecomer = id(3);
		assertNull(m.queue(ghost, "ghost"), "报名不应被拒");
		assertNull(m.queue(keeper, "keeper"), "报名不应被拒");
		assertNull(m.queue(latecomer, "latecomer"), "报名不应被拒");
		assertNull(m.assignTeam(ghost, 0), "预分队不应被拒");
		assertNull(m.assignTeam(keeper, 0), "预分队不应被拒");
		// teamSize=2，队 0 此刻是满的 —— 这就是"上一局 kill 掉的假玩家还占着席位"的现场
		assertNotNull(m.assignTeam(latecomer, 0), "队 0 满时必须拒绝（这条守住『已满』确实会发生）");

		List<String> released = m.releaseAbsent(Set.of(keeper, latecomer));
		assertEquals(1, released.size(), "只该放掉不在场的那一个：" + released);
		assertTrue(released.get(0).contains("ghost"), "释放条目要带名字，运维才知道是谁：" + released);
		assertNull(m.session(ghost), "幽灵会话必须被清掉");
		assertTrue(m.teams().get(0).membersOrdered().size() == 1,
				"队 0 应该只剩 keeper：" + m.teams().get(0).membersOrdered());

		// 这一条才是根因证明：席位释放之后，同样的人事指派必须成功
		assertNull(m.assignTeam(latecomer, 0), "腾出席位后指派必须成功");
		assertEquals(0, m.session(latecomer).teamIndex(), "迟到者应进到刚腾出来的队 0");
	}

	@Test
	void releaseAbsentIsNoOpOnceMatchStarted() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 2, 20));
		startMatch(m, 2);
		runToPlay(m);
		assertEquals(2, m.sessions().size(), "PLAY 期间两个报名都该在");
		assertTrue(m.releaseAbsent(Set.of()).isEmpty(),
				"PLAY 里不在场的人不能走这条路径清会话：掉线要开席位倒计时（05 C18）");
		assertEquals(2, m.sessions().size(), "对局进行中一个会话都不许被 releaseAbsent 放掉");
	}

	@Test
	void releaseAbsentAlsoDropsQueueOnlyGhost() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 1, 20));
		UUID ghost = id(7);
		assertNull(m.queue(ghost, "queued-ghost"), "报名不应被拒");
		assertEquals(1, m.releaseAbsent(Set.of()).size(), "只在队列里的幽灵也该被放掉");
		assertTrue(m.sessions().isEmpty(), "会话表必须清空");
	}

	@Test
	void aliveTeammatesExcludesDeadAndSelf() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		startMatch(m, 4);
		runToPlay(m);
		UUID a = m.teams().get(0).membersOrdered().get(0);
		UUID b = m.teams().get(0).membersOrdered().get(1);

		assertEquals(List.of(b), m.aliveTeammates(a), "同队另一人活着时应被列出");
		assertEquals(List.of(a), m.aliveTeammates(b), "从另一侧看也应列出对方、且不含自己");

		m.onDeath(a);
		assertEquals(List.of(b), m.aliveTeammates(a), "自己死亡后仍应列出活着的队友，但不含自己");
		assertTrue(m.aliveTeammates(b).isEmpty(), "唯一队友死亡后观战提示应显示本队已全灭");

		m.onDeath(b);
		assertTrue(m.aliveTeammates(a).isEmpty(), "两名队友都死亡后不再列出任何人");
		assertTrue(m.aliveTeammates(b).isEmpty(), "两名队友都死亡后不再列出任何人");

		UUID noSeat = id(777);
		assertTrue(m.aliveTeammates(noSeat).isEmpty(), "没有会话/没有分队时返回空列表");
	}

	@Test
	void sameTeamOnlyWhenBothAreSeatedOnTheSameTeam() {
		RoundMachine m = new RoundMachine(cfg(2, 2, 4, 200));
		startMatch(m, 4);
		UUID t0a = m.teams().get(0).membersOrdered().get(0);
		UUID t0b = m.teams().get(0).membersOrdered().get(1);
		UUID t1 = m.teams().get(1).membersOrdered().get(0);

		assertTrue(m.sameTeam(t0a, t0b), "同队两人必须判同队（friendly fire 要拦）");
		assertFalse(m.sameTeam(t0a, t1), "不同队不能判同队");
		assertFalse(m.sameTeam(t0a, id(999)), "未知玩家不能判同队");

		RoundMachine lobby = new RoundMachine(cfg(2, 2, 4, 200));
		UUID queued = id(42);
		assertNull(lobby.queue(queued, "queued"), "报名不应被拒");
		assertFalse(lobby.sameTeam(queued, queued), "未分队的人不构成同队");
		assertFalse(lobby.sameTeam(queued, t0a), "未分队 vs 已分队也不构成同队");
	}

	@Test
	void configRejectsImpossibleSpecs() {
		assertThrows(IllegalArgumentException.class, () -> new RoundConfig(0, 3, 4, 20, 20, 20, 20, 20));
		assertThrows(IllegalArgumentException.class, () -> new RoundConfig(4, 3, 4, 0, 20, 20, 20, 20));
		assertEquals(12, RoundConfig.standard().capacity());
		assertEquals(900 * 20, RoundConfig.fromFormat(4, 3, 4, 720).playHardCapTicks(), "局长 + 3 分钟硬上限");
	}
}
