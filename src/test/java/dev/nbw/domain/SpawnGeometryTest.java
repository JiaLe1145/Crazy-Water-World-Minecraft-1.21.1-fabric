package dev.nbw.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 等角出生点回归（03 §3.3 A15c、03 §6.1 规则 4）。 */
class SpawnGeometryTest {

	@Test
	void fourTeamsReproduceTheHandWrittenDefaultMap() {
		// default_sea.json 的 spawns.teams 是手写的 (0,-112)(112,0)(0,112)(-112,0)；auto 必须算出同一组
		List<SpawnGeometry.Spot> spots = SpawnGeometry.equidistant(0, 0, 140, 4, SpawnGeometry.DEFAULT_RADIUS_FACTOR);
		assertEquals(4, spots.size());
		assertEquals(List.of(0, -112), List.of(spots.get(0).x(), spots.get(0).z()));
		assertEquals(List.of(112, 0), List.of(spots.get(1).x(), spots.get(1).z()));
		assertEquals(List.of(0, 112), List.of(spots.get(2).x(), spots.get(2).z()));
		assertEquals(List.of(-112, 0), List.of(spots.get(3).x(), spots.get(3).z()));
	}

	@Test
	void yawLooksAtTheCenter() {
		List<SpawnGeometry.Spot> spots = SpawnGeometry.equidistant(0, 0, 140, 4, 0.8);
		// 正北点朝圈心 = 朝南 = yaw 0；东侧点朝西 = yaw 90
		assertEquals(0.0f, spots.get(0).yaw(), 0.5f);
		assertEquals(90.0f, spots.get(1).yaw(), 0.5f);
		assertEquals(180.0f, Math.abs(spots.get(2).yaw()), 0.5f);
		assertEquals(-90.0f, spots.get(3).yaw(), 0.5f);
	}

	@Test
	void everySpotSitsInsideTheSeaAndFarEnoughFromCenter() {
		for (int n = 2; n <= 6; n++) {
			List<SpawnGeometry.Spot> spots = SpawnGeometry.equidistant(0, 0, 140, n, 0.8);
			for (SpawnGeometry.Spot s : spots) {
				assertTrue(s.distanceFromCenter() <= 140, n + " 队时点跑出圆外：" + s);
				assertTrue(s.distanceFromCenter() >= 140 * 0.5, n + " 队时距圈心过近：" + s);
			}
			assertTrue(SpawnGeometry.validate(spots, 0, 0, 140, 60).isEmpty(), n + " 队应通过校验");
		}
	}

	@Test
	void separationIsSymmetricAcrossTeams() {
		List<SpawnGeometry.Spot> spots = SpawnGeometry.equidistant(0, 0, 140, 4, 0.8);
		// 正四边形的相邻间距 = r*sqrt(2)
		assertEquals(158, SpawnGeometry.minSeparation(spots), 1);
	}

	@Test
	void twoTeamsLandOnOppositeSides() {
		List<SpawnGeometry.Spot> spots = SpawnGeometry.equidistant(0, 0, 90, 2, 0.8);
		assertEquals(List.of(0, -72), List.of(spots.get(0).x(), spots.get(0).z()));
		assertEquals(List.of(0, 72), List.of(spots.get(1).x(), spots.get(1).z()));
		assertEquals(144, SpawnGeometry.minSeparation(spots));
	}

	@Test
	void validateReportsBothOutOfCircleAndTooClose() {
		List<SpawnGeometry.Spot> bad = List.of(
				new SpawnGeometry.Spot(0, 0, 200, 0f, 200),        // 圆外
				new SpawnGeometry.Spot(1, 20, 0, 0f, 20),          // 距心过近（< 140*0.5）
				new SpawnGeometry.Spot(2, 112, 0, 0f, 112));
		List<String> errors = SpawnGeometry.validate(bad, 0, 0, 140, 60);
		assertTrue(errors.stream().anyMatch(e -> e.contains("圆外")), errors.toString());
		assertTrue(errors.stream().anyMatch(e -> e.contains("过近")), errors.toString());
	}

	@Test
	void maxTeamsForMatchesTheChordFormula() {
		// r=112、要求间距 ≥90：7 队的弦长 97 合格，8 队 86 不合格
		assertEquals(7, SpawnGeometry.maxTeamsFor(140, 90, 0.8));
		assertTrue(SpawnGeometry.validate(SpawnGeometry.equidistant(0, 0, 140, 7, 0.8), 0, 0, 140, 90).isEmpty());
		assertTrue(SpawnGeometry.validate(SpawnGeometry.equidistant(0, 0, 140, 8, 0.8), 0, 0, 140, 90).size() > 0,
				"8 队挤在 radius 140 里必须被校验拒绝（自动降档的依据）");
	}

	@Test
	void rejectsImpossibleArguments() {
		assertThrows(IllegalArgumentException.class, () -> SpawnGeometry.equidistant(0, 0, 140, 0, 0.8));
		assertThrows(IllegalArgumentException.class, () -> SpawnGeometry.equidistant(0, 0, 0, 4, 0.8));
		assertThrows(IllegalArgumentException.class, () -> SpawnGeometry.equidistant(0, 0, 140, 4, 1.5));
	}
}
