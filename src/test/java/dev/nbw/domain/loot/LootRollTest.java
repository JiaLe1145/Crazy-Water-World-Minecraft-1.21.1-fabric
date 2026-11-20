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

	// ------------------------------------------------------------------
	// 设施池（2026-11-21 补，修复 M6-Q §6.1 的「设计有来源、实际拿不到」缺口）
	// ------------------------------------------------------------------

	/** 每个 0..1 的 roll01 都必须落在池内的合法条目上（不能越界、不能 null）。 */
	@Test
	void facilityPoolIsTotalAndWellFormed() {
		for (int tier : new int[]{1, 2, 3}) {
			int size = LootRoll.facilityPoolSize(tier);
			assertTrue(size > 0, "tier" + tier + " 的设施池不能为空");
			for (int i = 0; i <= 1000; i++) {
				double r = i / 1000.0;
				LootRoll.Item item = LootRoll.rollFacility(tier, r);
				assertNotNull(item, "tier" + tier + " roll=" + r + " 抽到 null");
				assertTrue(item.id().startsWith("nbw:"), "设施池只放自定义物品：" + item.id());
				assertTrue(item.min() >= 1 && item.min() <= item.max(), "数量区间非法：" + item);
				assertTrue(item.max() <= 64, "数量超过单格上限：" + item);
			}
		}
	}

	/**
	 * 缺口清单必须<b>全部</b>能从设施池抽到，且高档件只在 tier>=2 出现。
	 *
	 * <p>这 10 件就是 M6-Q-RESOURCES §6.1 里"局内拿不到"的那批：
	 * pike 走稀有度池，其余 9 件走设施池。这里按 id 逐件确认，删掉任何一件都会红。
	 */
	@Test
	void everyPreviouslyMissingItemIsReachable() {
		java.util.Set<String> basic = new java.util.HashSet<>();
		java.util.Set<String> advanced = new java.util.HashSet<>();
		for (int i = 0; i <= 2000; i++) {
			double r = i / 2000.0;
			basic.add(LootRoll.rollFacility(1, r).id());
			advanced.add(LootRoll.rollFacility(2, r).id());
		}
		// tier1 只出基础件
		assertTrue(basic.containsAll(java.util.List.of(
				"nbw:rope", "nbw:grapnel", "nbw:repair_table", "nbw:compost_farm", "nbw:kitchen")),
				"tier1 设施池缺件：" + basic);
		assertFalse(basic.contains("nbw:ballista") || basic.contains("nbw:catapult")
				|| basic.contains("nbw:sail") || basic.contains("nbw:generator"),
				"进阶件不该在 tier1 出现：" + basic);
		// tier2+ 出进阶件
		assertTrue(advanced.containsAll(java.util.List.of(
				"nbw:sail", "nbw:generator", "nbw:ram", "nbw:ballista", "nbw:catapult")),
				"tier2 设施池缺件：" + advanced);

		// pike 在 Rare 稀有度池
		java.util.Set<String> rare = new java.util.HashSet<>();
		for (int i = 0; i <= 2000; i++) {
			rare.add(LootRoll.rollItem(LootRoll.Rarity.RARE, i / 2000.0).id());
		}
		assertTrue(rare.contains("nbw:pike"), "稀有度池缺长矛：" + rare);
	}

	/** 设施概率口子必须与常量一致（探针按这个常量算期望值）。 */
	@Test
	void facilityChanceBoundary() {
		assertTrue(LootRoll.facilityRoll(0.0), "0 一定落在设施区间");
		assertTrue(LootRoll.facilityRoll(LootRoll.FACILITY_CHANCE - 1e-9), "略小于阈值的必须算设施");
		assertFalse(LootRoll.facilityRoll(LootRoll.FACILITY_CHANCE), "等于阈值就不算设施（左闭右开）");
		assertFalse(LootRoll.facilityRoll(0.999999), "接近 1 的必不是设施");
		assertTrue(LootRoll.FACILITY_CHANCE > 0.0 && LootRoll.FACILITY_CHANCE < 1.0,
				"概率必须落在 (0,1)：" + LootRoll.FACILITY_CHANCE);
	}
}
