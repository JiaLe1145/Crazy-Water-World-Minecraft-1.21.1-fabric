package dev.nbw.domain.round;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.nbw.domain.round.RoundMachine.Transition;

import static org.junit.jupiter.api.Assertions.*;

/** 回合状态快照与重启恢复（03 §6.1、Gate G1）。 */
class RoundSnapshotTest {

	private static final UUID P1 = UUID.nameUUIDFromBytes("p1".getBytes());
	private static final UUID P2 = UUID.nameUUIDFromBytes("p2".getBytes());
	private static final UUID P3 = UUID.nameUUIDFromBytes("p3".getBytes());

	private static RoundConfig spec2x2() {
		return new RoundConfig(2, 2, 2, 40, 20, 600, 40, 10);
	}

	/** 造一台跑到 PLAY 的机器：4 人报名、分两队、走完 ENROLL 与 READY。 */
	private static RoundMachine inPlay() {
		RoundMachine m = new RoundMachine(spec2x2());
		m.setConfigIds("flat_test", "test_3min");   // 与 RoundManager.loadConfigs 成功后的动作一致
		m.queue(P1, "p1");
		m.queue(P2, "p2");
		m.queue(P3, "p3");
		m.forceTeam(P1, 0);
		m.forceTeam(P2, 0);
		m.forceTeam(P3, 1);
		m.fire(RoundEvent.START);
		for (int i = 0; i < 41; i++) {
			m.tick();
		}
		m.fire(RoundEvent.ENROLL_TIMEOUT);
		for (int i = 0; i < 21; i++) {
			m.tick();
		}
		m.fire(RoundEvent.READY_TIMEOUT);
		return m;
	}

	@Test
	void freshMachineSnapshotsToLobbyWithFullTeamSkeleton() {
		RoundSnapshot s = new RoundMachine(spec2x2()).snapshot(60);
		assertEquals(Phase.LOBBY, s.phase());
		assertEquals(2, s.teams().size(), "队伍骨架要一起落盘，不然恢复后 /cww state 里没有队");
		assertEquals(0, s.participantCount());
		assertEquals(2, s.teams().get(0).maxSize());
		assertEquals("赤队", s.teams().get(0).name());
	}

	@Test
	void roundTripPreservesPhaseClockAndRoster() {
		RoundMachine live = inPlay();
		RoundSnapshot snap = live.snapshot(60);
		assertEquals(Phase.PLAY, snap.phase());

		RoundMachine restored = new RoundMachine(spec2x2());
		restored.restore(snap);

		assertEquals(live.phase(), restored.phase());
		assertEquals(live.tickInPhase(), restored.tickInPhase());
		assertEquals(live.totalTicks(), restored.totalTicks());
		assertEquals(live.matchIndex(), restored.matchIndex());
		assertEquals(live.queuedCount(), restored.queuedCount());
		assertEquals(live.teamsWithMembers(), restored.teamsWithMembers());
		assertEquals(live.audit(), restored.audit(), "审计尾部要逐条一致，否则排查断档");
		for (UUID id : List.of(P1, P2, P3)) {
			assertEquals(live.session(id).teamIndex(), restored.session(id).teamIndex());
			assertEquals(live.session(id).isAlive(), restored.session(id).isAlive());
			assertEquals(live.session(id).joinedQueueTick(), restored.session(id).joinedQueueTick());
		}
	}

	@Test
	void restoredMachineKeepsRunningFromWhereItStopped() {
		RoundMachine live = inPlay();
		RoundMachine restored = new RoundMachine(spec2x2());
		restored.restore(live.snapshot(60));

		// 关键不是"读得出"，而是恢复之后倒计时继续走、能正常判胜
		for (int i = 0; i < 10; i++) {
			restored.tick();
		}
		assertEquals(Phase.PLAY, restored.phase());
		assertTrue(restored.tickInPhase() >= 10);

		// 淘汰 1 队 → 应当能判胜，说明恢复没把胜负判定搞坏
		restored.fire(RoundEvent.WINNER_DECIDED);
		assertEquals(Phase.SETTLE, restored.phase());
	}

	@Test
	void restoreDoesNotReplayPendingEffects() {
		RoundMachine live = inPlay();
		live.drainPendingEffects();   // 清掉旧的，看 restore 会不会凭空造出副作用
		RoundMachine restored = new RoundMachine(spec2x2());
		restored.restore(live.snapshot(60));
		assertTrue(restored.drainPendingEffects().isEmpty(),
				"跨重启回放副作用会把玩家重复传送；恢复后必须由适配层按当前阶段重做一次");
	}

	@Test
	void auditTailIsTruncatedToRequestedSize() {
		RoundMachine m = inPlay();
		assertTrue(m.audit().size() >= 3, "前置：至少要有 START/ENROLL_TIMEOUT/READY_TIMEOUT 三条");
		RoundSnapshot two = m.snapshot(2);
		assertEquals(2, two.audit().size());
		assertEquals(m.audit().get(m.audit().size() - 1), two.audit().get(1), "保留的必须是尾部");
	}

	@Test
	void restoringIntoMismatchedConfigIsRejectedNotForgiven() {
		RoundSnapshot snap = inPlay().snapshot(60);   // 2 队
		RoundMachine wrong = new RoundMachine(new RoundConfig(4, 3, 4, 40, 20, 600, 40, 10));
		RoundSnapshot.InvalidSnapshot e =
				assertThrows(RoundSnapshot.InvalidSnapshot.class, () -> wrong.restore(snap));
		assertTrue(e.getMessage().contains("format"), "报错要指向运维真正要做的事：" + e.getMessage());
	}

	@Test
	void validateRejectsSelfContradictorySnapshots() {
		RoundSnapshot base = inPlay().snapshot(60);

		assertThrows(RoundSnapshot.InvalidSnapshot.class,
				new RoundSnapshot(null, 0, 0, 0, 0, false, -1, base.teams(), base.sessions(),
						base.queue(), base.audit(), base.auditNotes(), "flat_test", "test_3min")::validate);
		assertThrows(RoundSnapshot.InvalidSnapshot.class,
				new RoundSnapshot(Phase.PLAY, -1, 10, 0, 0, false, -1, base.teams(), base.sessions(),
						base.queue(), base.audit(), base.auditNotes(), "flat_test", "test_3min")::validate);
		assertThrows(RoundSnapshot.InvalidSnapshot.class,
				new RoundSnapshot(Phase.PLAY, 90, 10, 0, 0, false, -1, base.teams(), base.sessions(),
						base.queue(), base.audit(), base.auditNotes(), "flat_test", "test_3min")::validate, "阶段内 tick 超过总 tick");
		assertThrows(RoundSnapshot.InvalidSnapshot.class,
				new RoundSnapshot(Phase.PLAY, 5, 10, -1, 0, false, -1, base.teams(), base.sessions(),
						base.queue(), base.audit(), base.auditNotes(), "flat_test", "test_3min")::validate);
		assertThrows(RoundSnapshot.InvalidSnapshot.class,
				new RoundSnapshot(Phase.PLAY, 5, 10, 0, 0, false, 9, base.teams(), base.sessions(),
						base.queue(), base.audit(), base.auditNotes(), "flat_test", "test_3min")::validate, "胜者队伍号超出队伍数");
	}

	@Test
	void validateCatchesBadTeamAndSessionShapes() {
		RoundSnapshot base = inPlay().snapshot(60);
		List<RoundSnapshot.Team> teams = base.teams();
		List<RoundSnapshot.Session> sessions = base.sessions();

		assertThrows(RoundSnapshot.InvalidSnapshot.class,
				new RoundSnapshot(Phase.LOBBY, 0, 0, 0, 0, false, -1,
						List.of(new RoundSnapshot.Team(0, "赤队", 2, List.of(P1, P2, P3))),
						sessions, base.queue(), List.of(), List.of(), "flat_test", "test_3min")::validate,
				"成员数超过队伍上限");
		assertThrows(RoundSnapshot.InvalidSnapshot.class,
				new RoundSnapshot(Phase.LOBBY, 0, 0, 0, 0, false, -1,
						List.of(new RoundSnapshot.Team(0, "", 2, List.of())),
						sessions, base.queue(), List.of(), List.of(), "flat_test", "test_3min")::validate);
		assertThrows(RoundSnapshot.InvalidSnapshot.class,
				new RoundSnapshot(Phase.LOBBY, 0, 0, 0, 0, false, -1, teams,
						List.of(new RoundSnapshot.Session(P1, "p1", 7, true, false, -1, -1, 0)),
						base.queue(), List.of(), List.of(), "flat_test", "test_3min")::validate, "会话指向不存在的队伍");
		assertThrows(RoundSnapshot.InvalidSnapshot.class,
				new RoundSnapshot(Phase.LOBBY, 0, 0, 0, 0, false, -1, teams,
						List.of(new RoundSnapshot.Session(null, "p1", 0, true, false, -1, -1, 0)),
						base.queue(), List.of(), List.of(), "flat_test", "test_3min")::validate);
	}

	@Test
	void eliminatedAndAliveCannotBothBeTrue() {
		RoundSnapshot base = inPlay().snapshot(60);
		assertThrows(RoundSnapshot.InvalidSnapshot.class,
				new RoundSnapshot(Phase.PLAY, 1, 10, 0, 0, false, -1, base.teams(),
						List.of(new RoundSnapshot.Session(P1, "p1", 0, true, true, 5, -1, 0)),
						base.queue(), List.of(), List.of(), "flat_test", "test_3min")::validate);
	}

	@Test
	void disconnectStateSurvivesRoundTrip() {
		RoundMachine m = inPlay();
		m.onDisconnect(P3);
		RoundMachine restored = new RoundMachine(spec2x2());
		restored.restore(m.snapshot(60));
		assertTrue(restored.session(P3).isDisconnected(), "掉线席位必须跨重启保留，否则玩家重连就掉队");
		assertEquals(m.session(P3).disconnectTick(), restored.session(P3).disconnectTick());
	}

	@Test
	void transitionRecordsAreEqualByValue() {
		Transition a = new Transition(1, 100, Phase.READY, RoundEvent.READY_TIMEOUT, Phase.PLAY);
		Transition b = new Transition(1, 100, Phase.READY, RoundEvent.READY_TIMEOUT, Phase.PLAY);
		assertEquals(a, b);
		assertEquals(List.of(a), List.of(b));
	}
}
