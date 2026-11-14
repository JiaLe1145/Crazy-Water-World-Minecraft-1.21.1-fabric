package dev.nbw.domain.loot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 物资排除区：半开区间与多矩形并集（01 §5 / 03 loot.exclude）。 */
class LootExclusionTest {

	@Test
	void halfOpenRectanglesExcludeMinAndAllowMax() {
		LootExclusion.Rect r = new LootExclusion.Rect(-20, -20, 20, 20);
		assertTrue(r.contains(-20, -20), "min 含");
		assertTrue(r.contains(19, 19), "max 前最后一格含");
		assertFalse(r.contains(20, 19), "maxX 不含（半开区间）");
		assertFalse(r.contains(19, 20), "maxZ 不含（半开区间）");
		assertFalse(r.contains(-21, 0), "minX 外不含");
	}

	@Test
	void anyRectangleInTheListExcludes() {
		List<LootExclusion.Rect> rects = List.of(
				new LootExclusion.Rect(-20, -20, 20, 20),
				new LootExclusion.Rect(40, -60, 70, -30));
		assertTrue(LootExclusion.contains(0, 0, rects), "第一个矩形内");
		assertTrue(LootExclusion.contains(50, -50, rects), "第二个矩形内");
		assertFalse(LootExclusion.contains(30, 0, rects), "两个矩形之间");
	}

	@Test
	void emptyListAllowsEverything() {
		assertFalse(LootExclusion.contains(0, 0, List.of()));
	}
}
