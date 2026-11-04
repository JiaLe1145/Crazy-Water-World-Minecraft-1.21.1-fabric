package dev.nbw.domain.raft;

import org.junit.jupiter.api.Test;

import dev.nbw.domain.raft.SinkRule.SinkTrigger;

import static org.junit.jupiter.api.Assertions.*;

/** 沉筏倒计时规则（01 §4.3.1 A3-v4）。纯函数，不需要起服。 */
class SinkRuleTest {

	private static final SinkTrigger NONE = new SinkTrigger(false, false);
	private static final SinkTrigger WIPED = new SinkTrigger(true, false);
	private static final SinkTrigger CORE_GONE = new SinkTrigger(false, true);

	@Test
	void countdownIsThirtySeconds() {
		assertEquals(600, SinkRule.COUNTDOWN_TICKS);
		assertEquals(30, SinkRule.COUNTDOWN_SECONDS);
	}

	@Test
	void idleStaysIdleWhileTeamHasAnythingAlive() {
		SinkState s = SinkState.fresh();
		assertEquals(SinkState.SinkPhase.IDLE, SinkRule.tick(s, NONE, false).phase(), "无事发生不该触发沉筏");
		assertSame(s, SinkRule.tick(s, NONE, false), "无触发时状态原样返回（同一个对象，不新建）");
	}

	@Test
	void wipeStartsCountdownAtFullThirtySeconds() {
		SinkState next = SinkRule.tick(SinkState.fresh(), WIPED, false);
		assertEquals(SinkState.SinkPhase.COUNTDOWN, next.phase());
		assertEquals(600, next.remainingTicks());
		assertEquals(30, next.remainingSeconds());
		assertEquals(0, next.resetsUsed());
	}

	@Test
	void countdownTicksDownOnePerTick() {
		SinkState s = SinkRule.tick(SinkState.fresh(), WIPED, false);
		s = SinkRule.tick(s, NONE, false);
		s = SinkRule.tick(s, NONE, false);
		assertEquals(598, s.remainingTicks());
	}

	@Test
	void countdownSurvivesTheConditionClearing() {
		// 设计选择 #1：条件消失不自动中止，否则复活会把沉筏反复抵消掉。
		SinkState s = SinkRule.tick(SinkState.fresh(), WIPED, false);
		for (int i = 0; i < 100; i++) {
			s = SinkRule.tick(s, NONE, false);
		}
		assertEquals(SinkState.SinkPhase.COUNTDOWN, s.phase());
		assertEquals(500, s.remainingTicks());
	}

	@Test
	void sinksExactlyAfterSixHundredTicks() {
		SinkState s = SinkRule.tick(SinkState.fresh(), WIPED, false);
		for (int i = 0; i < 599; i++) {
			s = SinkRule.tick(s, WIPED, false);
			assertEquals(SinkState.SinkPhase.COUNTDOWN, s.phase(), "第 " + (i + 2) + " tick 不该已经沉");
		}
		s = SinkRule.tick(s, WIPED, false);
		assertEquals(SinkState.SinkPhase.SUNK, s.phase());
		assertEquals(0, s.remainingTicks());
	}

	@Test
	void sunkIsTerminal() {
		SinkState sunk = new SinkState(SinkState.SinkPhase.SUNK, 0, 1);
		assertEquals(SinkState.SinkPhase.SUNK, SinkRule.tick(sunk, NONE, false).phase());
		assertEquals(sunk, SinkRule.tick(sunk, NONE, false));
	}

	@Test
	void repairRestoresFullCountdownAndConsumesTheOnlyReset() {
		SinkState s = SinkRule.tick(SinkState.fresh(), WIPED, false);
		for (int i = 0; i < 300; i++) {
			s = SinkRule.tick(s, WIPED, false);
		}
		assertEquals(300, s.remainingTicks());

		s = SinkRule.tick(s, WIPED, true);
		assertEquals(600, s.remainingTicks(), "重修后应拉回满 30s");
		assertEquals(1, s.resetsUsed());
	}

	@Test
	void secondRepairIsANoOpOnceTheQuotaIsUsed() {
		SinkState s = SinkRule.tick(SinkState.fresh(), WIPED, false);
		s = SinkRule.tick(s, WIPED, true);
		assertEquals(600, s.remainingTicks());
		s = SinkRule.tick(s, WIPED, false); // 599

		SinkState again = SinkRule.tick(s, WIPED, true);
		assertEquals(598, again.remainingTicks(), "额度用完，重修不生效，这一帧照常扣时");
		assertEquals(1, again.resetsUsed());
	}

	@Test
	void repairCanSaveTheRaftRightBeforeItSinks() {
		SinkState s = new SinkState(SinkState.SinkPhase.COUNTDOWN, 1, 0);
		s = SinkRule.tick(s, WIPED, true);
		assertEquals(SinkState.SinkPhase.COUNTDOWN, s.phase());
		assertEquals(600, s.remainingTicks());
	}

	@Test
	void repairOnTheLastTickStillSinksAfterRestoring() {
		SinkState s = new SinkState(SinkState.SinkPhase.COUNTDOWN, 1, 1); // 额度已用完
		s = SinkRule.tick(s, WIPED, true);
		assertEquals(SinkState.SinkPhase.SUNK, s.phase(), "额度用尽时最后一 tick 重修无效");
	}

	@Test
	void repairInIdlePhaseIsAnError() {
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.tick(SinkState.fresh(), WIPED, true));
	}

	@Test
	void resetOutsideCountdownThrows() {
		assertThrows(SinkRule.IllegalSinkState.class, () -> SinkRule.reset(SinkState.fresh()));
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.reset(new SinkState(SinkState.SinkPhase.SUNK, 0, 0)));
	}

	@Test
	void malformedStatesAreRejected() {
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.tick(new SinkState(SinkState.SinkPhase.IDLE, 100, 0), WIPED, false));
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.tick(new SinkState(SinkState.SinkPhase.COUNTDOWN, 0, 0), WIPED, false));
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.tick(new SinkState(SinkState.SinkPhase.COUNTDOWN, 601, 0), WIPED, false));
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.tick(new SinkState(SinkState.SinkPhase.SUNK, 5, 0), WIPED, false));
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.tick(new SinkState(SinkState.SinkPhase.COUNTDOWN, 10, 2), WIPED, false));
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.tick(new SinkState(SinkState.SinkPhase.COUNTDOWN, 10, -1), WIPED, false));
	}

	@Test
	void coreDestructionAlsoStartsCountdown() {
		// 01 §4.3.2 [已批准 A2]：摧毁敌方筏心是第二条取胜路径，触发条件与全队打光等价
		SinkState next = SinkRule.tick(SinkState.fresh(), CORE_GONE, false);
		assertEquals(SinkState.SinkPhase.COUNTDOWN, next.phase());
		assertEquals(600, next.remainingTicks());
	}

	@Test
	void restoringCoreClearsCountdownEntirely() {
		// A2-v2 的"恢复"是解除，不是重置：修好了筏心却照样沉，这条机制就废了
		SinkState s = SinkRule.tick(SinkState.fresh(), WIPED, false);
		for (int i = 0; i < 400; i++) {
			s = SinkRule.tick(s, NONE, false);
		}
		assertEquals(200, s.remainingTicks());

		SinkState cleared = SinkRule.clear(s);
		assertEquals(SinkState.SinkPhase.IDLE, cleared.phase());
		assertEquals(0, cleared.remainingTicks());
		assertEquals(0, cleared.resetsUsed(), "解除不消耗重置额度");
	}

	@Test
	void clearingFromIdleIsANoOpAndKeepsQuota() {
		SinkState s = SinkRule.clear(SinkState.fresh());
		assertEquals(SinkState.SinkPhase.IDLE, s.phase());
		assertEquals(0, s.remainingTicks());
	}

	@Test
	void sunkCannotBeUndoneByRestoringCore() {
		// SUNK 不可逆：筏子已经沉了，再修筏心也捞不回来
		SinkState sunk = new SinkState(SinkState.SinkPhase.SUNK, 0, 1);
		assertEquals(SinkState.SinkPhase.SUNK, SinkRule.clear(sunk).phase());
	}

	@Test
	void quotaSurvivesClearAndAllowsOneMoreReset() {
		SinkState s = SinkRule.tick(SinkState.fresh(), CORE_GONE, false);
		s = SinkRule.clear(s);
		assertEquals(SinkState.SinkPhase.IDLE, s.phase());
		s = SinkRule.tick(s, CORE_GONE, false);
		s = SinkRule.tick(s, NONE, true);
		assertEquals(1, s.resetsUsed(), "解除过一次的队伍仍然享有那一次重置额度");
	}

	@Test
	void remainingSecondsRoundsUp() {
		assertEquals(1, new SinkState(SinkState.SinkPhase.COUNTDOWN, 1, 0).remainingSeconds());
		assertEquals(1, new SinkState(SinkState.SinkPhase.COUNTDOWN, 20, 0).remainingSeconds());
		assertEquals(2, new SinkState(SinkState.SinkPhase.COUNTDOWN, 21, 0).remainingSeconds());
	}
}
