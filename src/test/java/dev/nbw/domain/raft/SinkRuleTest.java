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
		assertEquals(SinkState.SinkPhase.IDLE, SinkRule.tick(s, NONE).phase(), "无事发生不该触发沉筏");
		assertSame(s, SinkRule.tick(s, NONE), "无触发时状态原样返回（同一个对象，不新建）");
	}

	@Test
	void wipeStartsCountdownAtFullThirtySeconds() {
		SinkState next = SinkRule.tick(SinkState.fresh(), WIPED);
		assertEquals(SinkState.SinkPhase.COUNTDOWN, next.phase());
		assertEquals(600, next.remainingTicks());
		assertEquals(30, next.remainingSeconds());
	}

	@Test
	void countdownTicksDownOnePerTick() {
		SinkState s = SinkRule.tick(SinkState.fresh(), WIPED);
		s = SinkRule.tick(s, NONE);
		s = SinkRule.tick(s, NONE);
		assertEquals(598, s.remainingTicks());
	}

	@Test
	void countdownSurvivesTheConditionClearing() {
		// 设计选择 #1：条件消失不自动中止，否则复活会把沉筏反复抵消掉。
		SinkState s = SinkRule.tick(SinkState.fresh(), WIPED);
		for (int i = 0; i < 100; i++) {
			s = SinkRule.tick(s, NONE);
		}
		assertEquals(SinkState.SinkPhase.COUNTDOWN, s.phase());
		assertEquals(500, s.remainingTicks());
	}

	@Test
	void sinksExactlyAfterSixHundredTicks() {
		SinkState s = SinkRule.tick(SinkState.fresh(), WIPED);
		for (int i = 0; i < 599; i++) {
			s = SinkRule.tick(s, WIPED);
			assertEquals(SinkState.SinkPhase.COUNTDOWN, s.phase(), "第 " + (i + 2) + " tick 不该已经沉");
		}
		s = SinkRule.tick(s, WIPED);
		assertEquals(SinkState.SinkPhase.SUNK, s.phase());
		assertEquals(0, s.remainingTicks());
	}

	@Test
	void sunkIsTerminal() {
		SinkState sunk = new SinkState(SinkState.SinkPhase.SUNK, 0);
		assertEquals(SinkState.SinkPhase.SUNK, SinkRule.tick(sunk, NONE).phase());
		assertEquals(sunk, SinkRule.tick(sunk, NONE));
	}

	@Test
	void malformedStatesAreRejected() {
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.tick(new SinkState(SinkState.SinkPhase.IDLE, 100), WIPED));
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.tick(new SinkState(SinkState.SinkPhase.COUNTDOWN, 0), WIPED));
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.tick(new SinkState(SinkState.SinkPhase.COUNTDOWN, 601), WIPED));
		assertThrows(SinkRule.IllegalSinkState.class,
				() -> SinkRule.tick(new SinkState(SinkState.SinkPhase.SUNK, 5), WIPED));
	}

	@Test
	void coreDestructionAlsoStartsCountdown() {
		// 01 §4.3.2 [已批准 A2]：摧毁敌方筏心是第二条取胜路径，触发条件与全队打光等价
		SinkState next = SinkRule.tick(SinkState.fresh(), CORE_GONE);
		assertEquals(SinkState.SinkPhase.COUNTDOWN, next.phase());
		assertEquals(600, next.remainingTicks());
	}

	@Test
	void restoringCoreClearsCountdownEntirely() {
		// A2-v2 的"恢复"是解除：修好了筏心却照样沉，这条机制就废了。
		// 解除与"重置"的区别在删除 A3 之后尤其要钉死：只有解除这一种中止手段，没有"拉回满 30s 稍后再沉"。
		SinkState s = SinkRule.tick(SinkState.fresh(), WIPED);
		for (int i = 0; i < 400; i++) {
			s = SinkRule.tick(s, NONE);
		}
		assertEquals(200, s.remainingTicks());

		SinkState cleared = SinkRule.clear(s);
		assertEquals(SinkState.SinkPhase.IDLE, cleared.phase());
		assertEquals(0, cleared.remainingTicks());
	}

	@Test
	void clearingFromIdleIsANoOp() {
		SinkState s = SinkRule.clear(SinkState.fresh());
		assertEquals(SinkState.SinkPhase.IDLE, s.phase());
		assertEquals(0, s.remainingTicks());
	}

	@Test
	void sunkCannotBeUndoneByRestoringCore() {
		// SUNK 不可逆：筏子已经沉了，再修筏心也捞不回来
		SinkState sunk = new SinkState(SinkState.SinkPhase.SUNK, 0);
		assertEquals(SinkState.SinkPhase.SUNK, SinkRule.clear(sunk).phase());
	}

	@Test
	void remainingSecondsRoundsUp() {
		assertEquals(1, new SinkState(SinkState.SinkPhase.COUNTDOWN, 1).remainingSeconds());
		assertEquals(1, new SinkState(SinkState.SinkPhase.COUNTDOWN, 20).remainingSeconds());
		assertEquals(2, new SinkState(SinkState.SinkPhase.COUNTDOWN, 21).remainingSeconds());
	}
}
