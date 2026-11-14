package dev.nbw.domain.raft;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.nbw.domain.raft.ChannelRule.Channel;
import dev.nbw.domain.raft.ChannelRule.Inputs;
import dev.nbw.domain.raft.ChannelRule.Outcome;
import dev.nbw.domain.raft.ChannelRule.Result;
import dev.nbw.domain.survivor.CraftsmanRule;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 残骸修复引导（01 §4.3.2 A2-v2）。
 *
 * <p>"持续 8 秒 / 受伤打断"是时序规则，最容易写错也最难人工复现，
 * 所以这里把它能覆盖的分支全部测穿：客户端长按那条输入路径要等真人，
 * 但判定本身不需要。
 */
class ChannelRuleTest {

	private static final UUID A = UUID.nameUUIDFromBytes("player-a".getBytes());
	private static final UUID B = UUID.nameUUIDFromBytes("player-b".getBytes());

	private static Inputs holding(UUID who) {
		return new Inputs(who, true, false);
	}

	/** 连续引导 n 个 tick，返回最后一个结果。 */
	private static Result run(Channel start, UUID who, int ticks) {
		return run(start, who, ticks, ChannelRule.CHANNEL_TICKS);
	}

	/** 同上，但带"本次引导的目标时长"（工匠 120）。 */
	private static Result run(Channel start, UUID who, int ticks, int startTicks) {
		Channel c = start;
		Result last = null;
		for (int i = 0; i < ticks; i++) {
			last = ChannelRule.tick(c, holding(who), startTicks);
			c = last.channel();
		}
		return last;
	}

	@Test
	void channelLengthIsEightSeconds() {
		assertEquals(160, ChannelRule.CHANNEL_TICKS);
	}

	@Test
	void firstTickStartsProgressAtOne() {
		Result r = ChannelRule.tick(Channel.none(), holding(A));
		assertEquals(Outcome.ADVANCED, r.outcome());
		assertEquals(A, r.channel().leader());
		assertEquals(1, r.channel().progressTicks());
	}

	@Test
	void completesOnExactlyTheHundredAndSixtiethTick() {
		Result r = run(Channel.none(), A, 159);
		assertEquals(Outcome.ADVANCED, r.outcome(), "第 159 tick 还不该完成");
		assertEquals(159, r.channel().progressTicks());

		r = ChannelRule.tick(r.channel(), holding(A));
		assertEquals(Outcome.COMPLETED, r.outcome(), "第 160 tick 必须完成");
		assertTrue(r.restored());
		assertFalse(r.channel().active(), "完成后现场清空");
	}

	@Test
	void damageInterruptsAndZeroesProgress() {
		Channel c = run(Channel.none(), A, 120).channel();
		assertEquals(120, c.progressTicks());

		Result r = ChannelRule.tick(c, new Inputs(A, true, true));
		assertEquals(Outcome.INTERRUPTED, r.outcome());
		assertFalse(r.channel().active(), "打断后不留任何进度");
	}

	@Test
	void damageOnTheVeryFirstTickAlsoInterrupts() {
		Result r = ChannelRule.tick(Channel.none(), new Inputs(A, true, true));
		assertEquals(Outcome.INTERRUPTED, r.outcome());
		assertFalse(r.channel().active());
	}

	@Test
	void releasingRightClickInterrupts() {
		Channel c = run(Channel.none(), A, 100).channel();
		Result r = ChannelRule.tick(c, new Inputs(A, false, false));
		assertEquals(Outcome.INTERRUPTED, r.outcome());
		assertFalse(r.channel().active());
	}

	@Test
	void leavingRangeOrDyingOrDisconnectingInterrupts() {
		// 适配层把这三种情况统一折算成 interacting=false，所以这里等价于"人没了"
		Channel c = run(Channel.none(), A, 100).channel();
		assertEquals(Outcome.INTERRUPTED, ChannelRule.tick(c, new Inputs(null, false, false)).outcome());
		assertFalse(ChannelRule.tick(c, new Inputs(null, false, false)).channel().active());
	}

	@Test
	void progressIsNotPartiallyRetainedAfterInterrupt() {
		Channel c = run(Channel.none(), A, 159).channel();
		c = ChannelRule.tick(c, new Inputs(A, true, true)).channel();   // 差一格被打断
		assertFalse(c.active());

		Result restarted = ChannelRule.tick(c, holding(A));
		assertEquals(1, restarted.channel().progressTicks(), "必须从零开始，否则蹭两秒再挨打是安全打法");
	}

	@Test
	void aDifferentPlayerRestartingFromZeroCannotInheritProgress() {
		Channel c = run(Channel.none(), A, 158).channel();

		Result byB = ChannelRule.tick(c, holding(B));
		assertEquals(Outcome.ADVANCED, byB.outcome());
		assertEquals(B, byB.channel().leader());
		assertEquals(1, byB.channel().progressTicks(), "换人不继承进度");
	}

	@Test
	void twoPlayersAlternatingNeverFinishIt() {
		// 这条是"同一残骸同时只认一个引导者"存在的理由：交替的话可以绕过打断惩罚
		Channel c = Channel.none();
		for (int i = 0; i < 400; i++) {
			UUID who = (i % 2 == 0) ? A : B;
			c = ChannelRule.tick(c, holding(who)).channel();
			assertNotEquals(160, c.progressTicks(), "交替引导不该在第 " + i + " tick 凑满");
			assertTrue(c.progressTicks() <= 1, "交替引导进度最多 1，实际 " + c.progressTicks());
		}
	}

	@Test
	void idleWhenNobodyIsInteracting() {
		Result r = ChannelRule.tick(Channel.none(), new Inputs(null, false, false));
		assertEquals(Outcome.IDLE, r.outcome());
		assertFalse(r.interrupted(), "本来就没在引导，不该报成打断");
	}

	@Test
	void damageWithoutAnyoneChannelingIsIgnored() {
		Result r = ChannelRule.tick(Channel.none(), new Inputs(null, false, true));
		assertEquals(Outcome.IDLE, r.outcome());
	}

	@Test
	void remainingSecondsAndRatio() {
		assertEquals(8, Channel.none().remainingSeconds());
		assertEquals(8, new Channel(A, 1).remainingSeconds());
		assertEquals(1, new Channel(A, 141).remainingSeconds());
		assertEquals(1, new Channel(A, 159).remainingSeconds());
		assertEquals(0.5, new Channel(A, 80).progressRatio(), 1e-9);
	}

	@Test
	void isCompleteMatchesChannelTicks() {
		assertFalse(ChannelRule.isComplete(159));
		assertTrue(ChannelRule.isComplete(160));
	}

	@Test
	void uninterruptedEightSecondsAlwaysRestoresTheCore() {
		Result r = run(Channel.none(), A, ChannelRule.CHANNEL_TICKS);
		assertEquals(Outcome.COMPLETED, r.outcome());
		assertTrue(r.restored());
	}

	@Test
	void craftsmanFinishesOnExactlyTheHundredAndTwentiethTick() {
		int need = CraftsmanRule.repairChannelTicks(ChannelRule.CHANNEL_TICKS, true);
		assertEquals(120, need, "工匠：8s → 6s（120 tick）");

		Result r = run(Channel.none(), A, need - 1, need);
		assertEquals(Outcome.ADVANCED, r.outcome(), "第 119 tick 还不该完成");
		assertEquals(need - 1, r.channel().progressTicks());

		r = ChannelRule.tick(r.channel(), holding(A), need);
		assertEquals(Outcome.COMPLETED, r.outcome(), "第 120 tick 必须完成");
		assertTrue(r.restored());
	}

	@Test
	void craftsmanDurationIsCapturedWhenTheChannelStarts() {
		int need = 120;
		Channel c = run(Channel.none(), A, 80, need).channel();
		assertEquals(need, c.requiredTicks(), "开始时的 120 被记在引导现场里");

		// 中途即使适配层再报 160，也必须沿用开始时的 120（防止最后一秒塞工匠）。
		Result r = run(c, A, 39, ChannelRule.CHANNEL_TICKS);
		assertEquals(Outcome.ADVANCED, r.outcome(), "80+39=119，还差一格");
		r = ChannelRule.tick(r.channel(), holding(A), ChannelRule.CHANNEL_TICKS);
		assertEquals(Outcome.COMPLETED, r.outcome(), "按开始时的 120 完成，不被拉长到 160");
	}

	@Test
	void craftsmanRemainingSecondsUsesItsOwnDuration() {
		assertEquals(6, new Channel(A, 1, 120).remainingSeconds(), "工匠引导开局显示 6 秒");
		assertEquals(1, new Channel(A, 101, 120).remainingSeconds());
		assertEquals(0.5, new Channel(A, 60, 120).progressRatio(), 1e-9);
	}
}
