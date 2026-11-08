package dev.nbw.domain.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 营养/水分纯规则（M4-1）。纯函数，不需要起服。 */
class SurvivalRuleTest {

	@Test
	void decayConvertsPerMinuteToTicks() {
		// 1 分钟 = 1200 tick；1.0/min 跑满一分钟正好掉 1.0
		assertEquals(1.0, SurvivalRule.decay(1.0, 1200), 1e-9);
		assertEquals(1.6, SurvivalRule.decay(1.6, 1200), 1e-9);
		assertEquals(3.5, SurvivalRule.decay(3.5, 1200), 1e-9);
		// 每秒（20 tick）的份额
		assertEquals(3.5 / 60.0, SurvivalRule.decay(3.5, 20), 1e-9);
	}

	@Test
	void clampStaysInZeroToMax() {
		assertEquals(0.0, SurvivalRule.clamp(-5.0));
		assertEquals(SurvivalRule.MAX, SurvivalRule.clamp(150.0));
		assertEquals(42.0, SurvivalRule.clamp(42.0));
	}

	@Test
	void lowThresholdIsInclusiveBelow() {
		assertTrue(SurvivalRule.nutritionLow(19.99));
		assertFalse(SurvivalRule.nutritionLow(20.0), "等于阈值算正常");
		assertTrue(SurvivalRule.hydrationLow(0.0));
		assertFalse(SurvivalRule.hydrationLow(80.0));
	}

	@Test
	void designNumbersAreStable() {
		assertEquals(100.0, SurvivalRule.MAX);
		assertEquals(1.0, SurvivalRule.NUTRITION_PER_MIN);
		assertEquals(1.6, SurvivalRule.NUTRITION_PER_MIN_COMBAT);
		assertEquals(3.5, SurvivalRule.HYDRATION_PER_MIN);
		assertEquals(20.0, SurvivalRule.LOW_THRESHOLD);
	}
}
