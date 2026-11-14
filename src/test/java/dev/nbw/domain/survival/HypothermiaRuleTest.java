package dev.nbw.domain.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失温（01 §5 / 02 §8）：连续湿身 10s → 消耗 ×1.5；连续干燥 3s → 解除。
 */
class HypothermiaRuleTest {

	@Test
	void nineWetSecondsIsNotYetHypothermia() {
		HypothermiaRule.State s = HypothermiaRule.State.none();
		for (int i = 0; i < 9; i++) {
			s = HypothermiaRule.tick(s, true);
		}
		assertEquals(9, s.wetSeconds());
		assertFalse(HypothermiaRule.active(s), "第 9 秒还不该失温");
		assertEquals(1.0, HypothermiaRule.drainMultiplier(s), 1e-12, "未失温必须严格 1.0");
	}

	@Test
	void tenWetSecondsTriggersHypothermia() {
		HypothermiaRule.State s = HypothermiaRule.State.none();
		for (int i = 0; i < 10; i++) {
			s = HypothermiaRule.tick(s, true);
		}
		assertTrue(HypothermiaRule.active(s), "第 10 秒必须失温");
		assertEquals(1.5, HypothermiaRule.drainMultiplier(s), 1e-12, "消耗 ×1.5");
	}

	@Test
	void threeDrySecondsResetsButTwoDoesNot() {
		HypothermiaRule.State s = HypothermiaRule.State.none();
		for (int i = 0; i < 10; i++) {
			s = HypothermiaRule.tick(s, true);
		}
		s = HypothermiaRule.tick(s, false);
		s = HypothermiaRule.tick(s, false);
		assertTrue(HypothermiaRule.active(s), "只干了 2 秒，失温仍在（否则雨里上筏躲一下就能白嫖）");

		s = HypothermiaRule.tick(s, false);
		assertEquals(HypothermiaRule.State.none(), s, "连续干燥满 3 秒必须清零");
		assertFalse(HypothermiaRule.active(s));
		assertEquals(1.0, HypothermiaRule.drainMultiplier(s), 1e-12);
	}

	@Test
	void aShortDryGapDoesNotEraseWetProgress() {
		HypothermiaRule.State s = HypothermiaRule.State.none();
		for (int i = 0; i < 8; i++) {
			s = HypothermiaRule.tick(s, true);
		}
		s = HypothermiaRule.tick(s, false);   // 干了 1 秒
		s = HypothermiaRule.tick(s, true);
		assertEquals(9, s.wetSeconds(), "短干湿切换不清零：8 + 1 = 9");
		assertFalse(HypothermiaRule.active(s));
		s = HypothermiaRule.tick(s, true);
		assertTrue(HypothermiaRule.active(s), "再湿 1 秒就满 10 秒");
	}
}
