package dev.nbw.domain.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 风暴远程规则（02 §10 / 09 §2.2）。 */
class WeatherCombatRuleTest {

	@Test
	void musketOnlyFailsDuringStorm() {
		assertTrue(WeatherCombatRule.musketDisabledByStorm(true, "musket"), "风暴中火铳必须失效");
		assertFalse(WeatherCombatRule.musketDisabledByStorm(false, "musket"), "无风暴时火铳正常");
		assertFalse(WeatherCombatRule.musketDisabledByStorm(true, "hand_crossbow"), "风暴不禁止其他远程");
		assertFalse(WeatherCombatRule.musketDisabledByStorm(true, null), "空 id 不能误判成火铳");
	}

	@Test
	void stormGivesFiftyPercentMiss() {
		assertTrue(WeatherCombatRule.rangedMissesInStorm(true, 0.0), "roll=0 必须射偏");
		assertTrue(WeatherCombatRule.rangedMissesInStorm(true, 0.4999), "50% 以下射偏");
		assertFalse(WeatherCombatRule.rangedMissesInStorm(true, 0.5), "50% 及以上命中（半开区间）");
		assertFalse(WeatherCombatRule.rangedMissesInStorm(true, 0.9999));
	}

	@Test
	void noStormNeverMisses() {
		assertFalse(WeatherCombatRule.rangedMissesInStorm(false, 0.0), "无风暴时 roll=0 也命中");
		assertFalse(WeatherCombatRule.rangedMissesInStorm(false, 0.9999));
	}
}
