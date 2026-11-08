package dev.nbw.domain.loot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 物资稀有度抽卡（M3 补，02 §4）。纯函数，不需要起服。 */
class LootRollTest {

	@Test
	void tier1NeverDropsLegendary() {
		for (int i = 0; i < 1000; i++) {
			double r = i / 1000.0;
			assertNotEquals(LootRoll.Rarity.LEGENDARY, LootRoll.rollRarity(1, true, r),
					"tier1 的 Legendary 权重是 0，无论是否解锁都不该出现");
		}
	}

	@Test
	void tier1BoundariesMatchDoc() {
		// 78/20/2/0
		assertEquals(LootRoll.Rarity.COMMON, LootRoll.rollRarity(1, false, 0.5));
		assertEquals(LootRoll.Rarity.RARE, LootRoll.rollRarity(1, false, 0.85));
		assertEquals(LootRoll.Rarity.EPIC, LootRoll.rollRarity(1, false, 0.99));
	}

	@Test
	void tier3LegendaryOnlyWhenUnlocked() {
		assertEquals(LootRoll.Rarity.LEGENDARY, LootRoll.rollRarity(3, true, 0.99));
		assertNotEquals(LootRoll.Rarity.LEGENDARY, LootRoll.rollRarity(3, false, 0.99),
				"未过 P3 时 Legendary 权重归零");
	}

	@Test
	void tier3UnlockedBoundaries() {
		// 40/35/20/5 -> 0.4 / 0.75 / 0.95
		assertEquals(LootRoll.Rarity.COMMON, LootRoll.rollRarity(3, true, 0.2));
		assertEquals(LootRoll.Rarity.RARE, LootRoll.rollRarity(3, true, 0.5));
		assertEquals(LootRoll.Rarity.EPIC, LootRoll.rollRarity(3, true, 0.8));
		assertEquals(LootRoll.Rarity.LEGENDARY, LootRoll.rollRarity(3, true, 0.97));
	}

	@Test
	void poolLookupCoversEveryRarity() {
		for (LootRoll.Rarity r : LootRoll.Rarity.values()) {
			assertTrue(LootRoll.poolSize(r) > 0, r + " 的池不能为空");
			LootRoll.Item item = LootRoll.rollItem(r, 0.5);
			assertNotNull(item);
			assertTrue(item.min() <= item.max());
		}
	}
}
