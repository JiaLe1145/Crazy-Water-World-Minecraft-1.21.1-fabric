package dev.nbw.arena;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 地图随机池（2026-11-08，参照 RicordRailExpress 的地图注册表 + 随机抽取）。纯函数，不需要起服。 */
class ArenaPoolTest {

	private static ArenaPool.Entry entry(String id, int weight, int minP, int maxP, boolean canSelect,
			List<String> formats) {
		return new ArenaPool.Entry(id, id, "", weight, minP, maxP, canSelect, formats);
	}

	@Test
	void supportsFormatTreatsEmptyListAsAll() {
		ArenaPool.Entry e = entry("a", 1, 0, -1, true, List.of());
		assertTrue(e.supportsFormat("standard_12min"));
		assertTrue(e.supportsFormat("test_3min"));
		ArenaPool.Entry restricted = entry("b", 1, 0, -1, true, List.of("test_3min"));
		assertTrue(restricted.supportsFormat("test_3min"));
		assertFalse(restricted.supportsFormat("standard_12min"));
	}

	@Test
	void pickSkipsUnselectableZeroWeightAndWrongFormat() {
		List<ArenaPool.Entry> entries = List.of(
				entry("good", 1, 0, -1, true, List.of()),
				entry("hidden", 1, 0, -1, false, List.of()),
				entry("zero", 0, 0, -1, true, List.of()),
				entry("wrongfmt", 1, 0, -1, true, List.of("other")));
		for (int i = 0; i < 50; i++) {
			assertEquals("good", ArenaPool.pick(entries, "standard_12min", 4, new Random(i)));
		}
	}

	@Test
	void pickRespectsPlayerCountRange() {
		List<ArenaPool.Entry> entries = List.of(
				entry("small", 1, 2, 4, true, List.of()),
				entry("big", 1, 8, -1, true, List.of()));
		assertEquals("small", ArenaPool.pick(entries, "f", 4, new Random(1)));
		assertEquals("big", ArenaPool.pick(entries, "f", 8, new Random(1)));
		assertNull(ArenaPool.pick(entries, "f", 6, new Random(1)), "6 人两张图都不满足，应返回 null");
	}

	@Test
	void pickReturnsNullWhenPoolEmpty() {
		assertNull(ArenaPool.pick(List.of(), "f", 4, new Random(1)));
		assertNull(ArenaPool.pick(List.of(entry("hidden", 1, 0, -1, false, List.of())), "f", 4, new Random(1)));
	}

	@Test
	void pickIsWeighted() {
		List<ArenaPool.Entry> entries = List.of(
				entry("a", 100, 0, -1, true, List.of()),
				entry("b", 1, 0, -1, true, List.of()));
		Random r = new Random(42);
		int a = 0;
		for (int i = 0; i < 1000; i++) {
			if ("a".equals(ArenaPool.pick(entries, "f", 4, r))) {
				a++;
			}
		}
		assertTrue(a > 900, "权重 100:1 时 a 应占绝大多数（实测 " + a + "/1000）");
	}
}
