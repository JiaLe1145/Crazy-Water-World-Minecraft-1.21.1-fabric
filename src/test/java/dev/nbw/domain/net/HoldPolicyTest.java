package dev.nbw.domain.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Test
	void eyeAndTargetAreComparedInsideTheDeckFrame() {
		// 眼睛在 (0,1,0) 那一格方块中心的正上方 1.6 格：同一个帧里就该是伸手可及。
		// 方块交给我们的数是<b>角</b>，中心偏移由 localDistance 自己加——两处各加一次会凭空多半个方块
		double[] eye = {0.5, 2.6, 0.5};
		double[] corner = {0.0, 1.0, 0.0};   // 中心 = (0.5, 1.5, 0.5)
		assertEquals(1.1, HoldPolicy.localDistance(eye, corner), 1e-9,
				"中心偏移只许在 localDistance 里加一次，实测 " + HoldPolicy.localDistance(eye, corner));
		assertEquals(HoldPolicy.Decision.KEEPALIVE,
				HoldPolicy.decide(true, HoldPolicy.localDistance(eye, corner), HoldPolicy.REACH_BLOCKS));
		assertEquals(5.0, HoldPolicy.localDistance(new double[]{3.5, 5.5, 0.5}, corner), 1e-9,
				"三轴都要算：只看水平会得到 3.0，把斜上方 4 格的人判成贴着脸");
		// 读不到位姿 / 筏不在场时一律给 -1，让 decide 当非法值拒掉，而不是当成"距离 0"放行
		assertEquals(-1.0, HoldPolicy.localDistance(new double[]{Double.NaN, 0, 0}, corner), 1e-9,
				"NaN 一侧必须给负数：宁可拒绝也不放行");
		assertEquals(-1.0, HoldPolicy.localDistance(eye, null), 1e-9, "缺数组也算非法");
		assertEquals(HoldPolicy.Decision.REJECT, HoldPolicy.decide(true,
				HoldPolicy.localDistance(new double[]{0, Double.NaN, 0}, corner), HoldPolicy.REACH_BLOCKS),
				"非法距离必须落到 REJECT 这一支");
	}

	@Test
	void mixingStorageCoordsWithDeckFrameRejectsEverything() {
		// 这条钉的是 2026-11-06 查出来的缺陷：长按包里的 pos 是 sub-level 的存储坐标（量级 2×10^7），
		// 而玩家换算出来的局部坐标是围绕 0 的小数。两个空间直算，每个筏上长按都会被判越界——
		// 症状不是报错，而是"长按桨台、长按筏心、长按船壳全都没反应"
		double[] eye = {0.5, 2.6, 0.5};
		double[] storageCorner = {20_481_032.0, 128.0, 20_481_032.0};
		double dist = HoldPolicy.localDistance(eye, storageCorner);
		assertTrue(dist > 1_000_000.0, "口径错位必须得到天文数字，这才叫错位：" + dist);
		assertEquals(HoldPolicy.Decision.REJECT, HoldPolicy.decide(true, dist, HoldPolicy.REACH_BLOCKS),
				"错位的距离把一切判成越界，所以两侧必须都换成甲板局部帧");
	}
}
