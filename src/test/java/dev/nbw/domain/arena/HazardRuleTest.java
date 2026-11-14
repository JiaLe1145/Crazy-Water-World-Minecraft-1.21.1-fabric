package dev.nbw.domain.arena;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 海域障碍减速（03 §3.2 hazards）。 */
class HazardRuleTest {

	private static final HazardRule.Zone REEF =
			new HazardRule.Zone("reef", 40, -60, 70, -30);

	@Test
	void insideHazardSlowsToHalf() {
		assertEquals(HazardRule.SLOW_MULTIPLIER, HazardRule.speedMultiplier(List.of(REEF), 50, -45));
		assertEquals(0.5, HazardRule.SLOW_MULTIPLIER, "起始配平值就是 0.5");
	}

	@Test
	void outsideOrEmptyDoesNotSlow() {
		assertEquals(1.0, HazardRule.speedMultiplier(List.of(REEF), 0, 0), "障碍外不减速");
		assertEquals(1.0, HazardRule.speedMultiplier(List.of(), 50, -45), "没有障碍时全速");
	}

	@Test
	void halfOpenBoundariesMatchArenaConvention() {
		assertTrue(REEF.contains(40, -60), "min 含");
		assertTrue(REEF.contains(69.999, -30.001), "max 前最后一格含");
		assertTrue(!REEF.contains(70, -45), "maxX 不含");
		assertTrue(!REEF.contains(50, -30), "maxZ 不含");
	}

	@Test
	void anyHazardInTheListSlows() {
		HazardRule.Zone ruins = new HazardRule.Zone("ruins", -20, -20, 20, 20);
		List<HazardRule.Zone> zones = List.of(REEF, ruins);
		assertEquals(HazardRule.SLOW_MULTIPLIER, HazardRule.speedMultiplier(zones, 0, 0), "第二个障碍也生效");
		assertEquals(1.0, HazardRule.speedMultiplier(zones, 30, 30), "两个障碍之间仍全速");
	}
}
