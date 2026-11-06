package dev.nbw.domain.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 长按输入的接受判定。核心要守住的只有一件事：
 * <b>客户端只能表达"我在按着 / 我松手了"，其它什么都表达不了。</b>
 */
class HoldPolicyTest {

	@Test
	void holdingWithinReachRenews() {
		assertEquals(HoldPolicy.Decision.KEEPALIVE,
				HoldPolicy.decide(true, 4.5, HoldPolicy.REACH_BLOCKS));
		assertEquals(HoldPolicy.Decision.KEEPALIVE,
				HoldPolicy.decide(true, HoldPolicy.REACH_BLOCKS, HoldPolicy.REACH_BLOCKS), "边界值应当算可达");
	}

	@Test
	void holdingOutOfRangeIsDropped() {
		assertEquals(HoldPolicy.Decision.REJECT, HoldPolicy.decide(true, 8.1, HoldPolicy.REACH_BLOCKS));
		assertEquals(HoldPolicy.Decision.REJECT, HoldPolicy.decide(true, 1000.0, HoldPolicy.REACH_BLOCKS),
				"构造包就能隔半个地图续引导，这条机制等于没有");
	}

	@Test
	void nonsenseDistanceIsRejectedNotTrusted() {
		assertEquals(HoldPolicy.Decision.REJECT, HoldPolicy.decide(true, Double.NaN, HoldPolicy.REACH_BLOCKS));
		assertEquals(HoldPolicy.Decision.REJECT, HoldPolicy.decide(true, -1.0, HoldPolicy.REACH_BLOCKS),
				"负距离是上游算错了，不能当成『离得很近』");
	}

	@Test
	void releaseIgnoresDistance() {
		// 松手必须永远生效：人走远了以后解除会被距离闸门挡住，那"打断"就变成"卡住"
		assertEquals(HoldPolicy.Decision.RELEASE, HoldPolicy.decide(false, 0.0, HoldPolicy.REACH_BLOCKS));
		assertEquals(HoldPolicy.Decision.RELEASE, HoldPolicy.decide(false, 9999.0, HoldPolicy.REACH_BLOCKS));
		assertEquals(HoldPolicy.Decision.RELEASE, HoldPolicy.decide(false, Double.NaN, HoldPolicy.REACH_BLOCKS));
	}

	@Test
	void spectatorCannotHoldAnything() {
		assertEquals(0.0, HoldPolicy.reachFor(true), 1e-9);
		assertEquals(HoldPolicy.REACH_BLOCKS, HoldPolicy.reachFor(false), 1e-9);
		assertEquals(HoldPolicy.Decision.REJECT, HoldPolicy.decide(true, 0.5, HoldPolicy.reachFor(true)),
				"旁观者连零距离都过不去，就不该能续引导");
	}

	@Test
	void reachIsWideEnoughForARidingRaft() {
		// 4.5 是原版触及，但筏会随浪起伏、人也未必站在方块正中：卡在 4.5 会做出"看得见修不了"
		assertEquals(8.0, HoldPolicy.REACH_BLOCKS, 1e-9);
	}
}
