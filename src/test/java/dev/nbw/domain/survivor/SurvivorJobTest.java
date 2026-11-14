package dev.nbw.domain.survivor;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 幸存者职业分配（01 §7 / 待批 A6 第一片）。 */
class SurvivorJobTest {

	@Test
	void initialOrderPutsProductionJobsFirst() {
		assertEquals(SurvivorJob.SAILOR, SurvivorJob.initialByIndex(0), "1 人队先给水手");
		assertEquals(SurvivorJob.COOK, SurvivorJob.initialByIndex(1), "2 人队补水手+厨师");
		assertEquals(SurvivorJob.MEDIC, SurvivorJob.initialByIndex(2), "3 人队补水手+厨师+医护");
		assertEquals(SurvivorJob.CRAFTSMAN, SurvivorJob.initialByIndex(3));
		assertEquals(SurvivorJob.HELMSMAN, SurvivorJob.initialByIndex(4));
		assertEquals(SurvivorJob.GUARD, SurvivorJob.initialByIndex(5));
		assertEquals(SurvivorJob.SAILOR, SurvivorJob.initialByIndex(6), "超过 6 人循环");
	}

	@Test
	void leastRepresentedFillsTheMissingJob() {
		assertEquals(SurvivorJob.SAILOR, SurvivorJob.leastRepresented(Map.of()),
				"空队伍补第一个职业（水手）");
		assertEquals(SurvivorJob.COOK, SurvivorJob.leastRepresented(Map.of(SurvivorJob.SAILOR, 2)),
				"已有水手时补厨师");
		assertEquals(SurvivorJob.MEDIC,
				SurvivorJob.leastRepresented(Map.of(SurvivorJob.SAILOR, 1, SurvivorJob.COOK, 1)),
				"已有水手+厨师时补医护");
		assertEquals(SurvivorJob.GUARD,
				SurvivorJob.leastRepresented(Map.of(SurvivorJob.SAILOR, 2, SurvivorJob.COOK, 1,
						SurvivorJob.MEDIC, 1, SurvivorJob.CRAFTSMAN, 1, SurvivorJob.HELMSMAN, 1)),
				"只剩卫兵最少时补卫兵");
	}

	@Test
	void displayNamesAreTheDocumentedSix() {
		assertEquals(6, SurvivorJob.values().length, "01 §7 是 6 职业");
		assertEquals("水手", SurvivorJob.SAILOR.displayName());
		assertEquals("厨师", SurvivorJob.COOK.displayName());
		assertEquals("医护", SurvivorJob.MEDIC.displayName());
		assertEquals("工匠", SurvivorJob.CRAFTSMAN.displayName());
		assertEquals("舵手", SurvivorJob.HELMSMAN.displayName());
		assertEquals("卫兵", SurvivorJob.GUARD.displayName());
	}
}
