package dev.nbw.domain.raft;

import dev.nbw.domain.raft.HelmModel.Intent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 遥控器按键 → 移动意图（问题③）。纯函数，不需要起服。 */
class HelmModelTest {

	@Test
	void wasdMapsToOneLinearAndOneTurn() {
		// 单一按键：W 前进、S 后退、A 左转、D 右转
		assertEquals(new Intent(1, 0), HelmModel.fromKeys(true, false, false, false));
		assertEquals(new Intent(-1, 0), HelmModel.fromKeys(false, true, false, false));
		assertEquals(new Intent(0, 1), HelmModel.fromKeys(false, false, true, false));
		assertEquals(new Intent(0, -1), HelmModel.fromKeys(false, false, false, true));
	}

	@Test
	void oppositeKeysCancelInsteadOfDoubleThrust() {
		// W+S 同按、A+D 同按都记 0：互斥键不能让玩家白得双倍动力或原地抖。
		assertEquals(new Intent(0, 0), HelmModel.fromKeys(true, true, false, false));
		assertEquals(new Intent(0, 0), HelmModel.fromKeys(false, false, true, true));
	}

	@Test
	void diagonalInputCombinesLinearAndTurn() {
		// W+A = 前进 + 左转：两维互不干扰。
		assertEquals(new Intent(1, 1), HelmModel.fromKeys(true, false, true, false));
		assertEquals(new Intent(-1, -1), HelmModel.fromKeys(false, true, false, true));
	}

	@Test
	void noInputIsIdle() {
		assertEquals(Intent.NONE, HelmModel.fromKeys(false, false, false, false));
		assertTrue(Intent.NONE.idle());
		assertFalse(new Intent(1, 0).idle());
		assertFalse(new Intent(0, 1).idle());
	}

	@Test
	void allKeysPressedIsIdle() {
		// 四个键全按 = 两对互斥各自抵消 → 全 0。这不是"四倍动力"。
		assertEquals(Intent.NONE, HelmModel.fromKeys(true, true, true, true));
	}
}
