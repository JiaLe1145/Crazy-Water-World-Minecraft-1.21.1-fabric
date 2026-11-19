package dev.nbw.domain.cosmetic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 外观解锁（01 §A11）：称号门槛与选择校验。纯函数，不依赖 MC。 */
class CosmeticRuleTest {

	@Test
	void defaultTitleIsAlwaysUnlocked() {
		assertTrue(CosmeticRule.isUnlocked(CosmeticRule.DEFAULT_ID, 0, 0));
		assertNotNull(CosmeticRule.pick(CosmeticRule.DEFAULT_ID, 0, 0));
	}

	@Test
	void thresholdsAreMonotonic() {
		int lastWins = -1;
		int lastKills = -1;
		for (CosmeticRule.Title t : CosmeticRule.TITLES) {
			assertTrue(t.minWins() >= lastWins, "胜场门槛必须单调不减：" + t.id());
			assertTrue(t.minKills() >= lastKills, "击杀门槛必须单调不减：" + t.id());
			lastWins = t.minWins();
			lastKills = t.minKills();
		}
	}

	@Test
	void unlockRequiresBothWinsAndKills() {
		// 水手 = 3 胜 + 20 杀；只满足一边不算解锁
		assertFalse(CosmeticRule.isUnlocked("sailor", 3, 19));
		assertFalse(CosmeticRule.isUnlocked("sailor", 2, 20));
		assertTrue(CosmeticRule.isUnlocked("sailor", 3, 20));
	}

	@Test
	void unlockedListGrowsWithCareer() {
		assertEquals(1, CosmeticRule.unlocked(0, 0).size());
		assertEquals(2, CosmeticRule.unlocked(3, 20).size());
		assertEquals(CosmeticRule.TITLES.size(), CosmeticRule.unlocked(999, 9999).size());
	}

	@Test
	void unknownIdIsRejected() {
		assertNull(CosmeticRule.byId("nope"));
		assertNull(CosmeticRule.pick("nope", 999, 9999));
		assertFalse(CosmeticRule.isUnlocked("nope", 999, 9999));
	}

	@Test
	void idLookupIsCaseAndSpaceInsensitive() {
		assertNotNull(CosmeticRule.byId("  SAILOR "));
		assertEquals("sailor", CosmeticRule.byId("Sailor").id());
	}

	@Test
	void titlesAreAllDistinct() {
		List<String> ids = CosmeticRule.TITLES.stream().map(CosmeticRule.Title::id).toList();
		assertEquals(ids.size(), ids.stream().distinct().count());
	}
}
