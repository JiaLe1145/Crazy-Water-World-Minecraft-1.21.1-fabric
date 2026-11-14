package dev.nbw.domain.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卫兵守筏规则（01 §7 A6 / 02 §9，2026-11-14 用户批准"文档锚点"）。
 *
 * <p>把射程改成 0、把间隔改成 1 tick、或把每发伤害改成 650，这组用例都会红——
 * 这三条正好是"0.6 名中等玩家"锚点的三个乘数。
 */
class GuardRuleTest {

	@Test
	void rangeIsTwelveBlocksInThreeDimensions() {
		assertEquals(12.0, GuardRule.RANGE, 1e-12, "02 §9 的守筏范围锚点");
		assertTrue(GuardRule.inRange(11.9, 0.0, 0.0), "水平 11.9 格在射程内");
		assertFalse(GuardRule.inRange(12.1, 0.0, 0.0), "水平 12.1 格已经出射程");
		assertTrue(GuardRule.inRange(0.0, 7.0, 9.0), "垂直分量计入（√130 ≈ 11.4）");
		assertFalse(GuardRule.inRange(0.0, 8.0, 9.0), "√145 ≈ 12.04，超出");
	}

	@Test
	void firesOncePerSecondForSixPointFiveDamage() {
		assertEquals(20, GuardRule.INTERVAL_TICKS, "1 发/秒");
		assertEquals(6.5, GuardRule.DAMAGE_PER_SHOT, 1e-12, "单发 6.5 HP");
		assertEquals(6.5, GuardRule.damagePerSecond(), 1e-12,
				"持续 DPS 必须仍是 6.5（0.6 × 近战平均 DPS 的取整锚点）");
	}

	@Test
	void anchorIsMeaningfullyWeakerThanAMediumPlayer() {
		// 近战表平均 DPS ≈ 11.2；0.6 倍 ≈ 6.7。6.5 落在锚点内，且明显低于一名玩家。
		double averageMeleeDps = (3.0 / 18 + 9.0 / 12 + 10.0 / 22 + 14.0 / 32 + 7.0 / 7) * 20 / 5;
		assertTrue(averageMeleeDps > GuardRule.damagePerSecond(),
				"卫兵必须弱于一名中等玩家（锚点，" + averageMeleeDps + " > " + GuardRule.damagePerSecond() + "）");
		assertTrue(GuardRule.damagePerSecond() >= averageMeleeDps * 0.5,
				"也不能弱到 0.5 个玩家以下，否则“守筏可”不成立");
	}
}
