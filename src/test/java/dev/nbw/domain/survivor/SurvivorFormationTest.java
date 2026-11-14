package dev.nbw.domain.survivor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 幸存者局部阵位（M5 跟随收口）：必须落在 4×4 甲板内，且不因总数为 0 除零。 */
class SurvivorFormationTest {

	@Test
	void everySlotStaysOnTheFourByFourDeck() {
		// 4×4 甲板局部范围 x/z ∈ [-2, 1]；半径 0.8 的圆必须完全落在里面。
		for (int total = 1; total <= 5; total++) {
			for (int i = 0; i < total; i++) {
				double[] p = SurvivorFormation.localOffset(i, total);
				assertEquals(SurvivorFormation.LOCAL_Y, p[1], 1e-9, "幸存者脚底必须在甲板面 y=1");
				assertTrue(p[0] >= -2.0 && p[0] <= 1.0, "x 必须落在 4×4 甲板内：" + p[0]);
				assertTrue(p[2] >= -2.0 && p[2] <= 1.0, "z 必须落在 4×4 甲板内：" + p[2]);
				assertTrue(Math.abs(p[0]) <= SurvivorFormation.RADIUS + 1e-9);
				assertTrue(Math.abs(p[2]) <= SurvivorFormation.RADIUS + 1e-9);
			}
		}
	}

	@Test
	void nonPositiveTotalDoesNotDivideByZero() {
		double[] p = SurvivorFormation.localOffset(0, 0);
		assertEquals(SurvivorFormation.LOCAL_Y, p[1], 1e-9);
		assertTrue(Double.isFinite(p[0]) && Double.isFinite(p[2]), "总数为 0 也必须返回有限点");
		assertTrue(Math.abs(p[0]) <= SurvivorFormation.RADIUS + 1e-9);
		assertTrue(Math.abs(p[2]) <= SurvivorFormation.RADIUS + 1e-9);
	}
}
