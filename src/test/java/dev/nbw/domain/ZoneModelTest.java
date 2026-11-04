package dev.nbw.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 圈层数学是玩法与配平的地基，必须有表驱动回归（02 §2、03 §6.1）。 */
class ZoneModelTest {

	private static ZoneModel standard() {
		// 对应 02 §2 的 standard_12min 圈表
		List<ZoneModel.Stage> stages = List.of(
				new ZoneModel.Stage(0, 0.65, 150, 60, 0.5, 15),
				new ZoneModel.Stage(1, 0.40, 210, 45, 1.0, 15),
				new ZoneModel.Stage(2, 0.20, 390, 45, 2.0, 15),
				new ZoneModel.Stage(3, 0.05, 570, 90, 4.0, 10));
		List<ZoneModel.Ring> rings = List.of(
				new ZoneModel.Ring(0, 28, 2.5, 3),
				new ZoneModel.Ring(28, 56, 2.0, 2),
				new ZoneModel.Ring(56, 91, 1.5, 2),
				new ZoneModel.Ring(91, 140, 1.0, 1));
		return ZoneModel.of(stages, rings, 140);
	}

	@Test
	void radiusBeforeFirstStageIsFull() {
		assertEquals(140.0, standard().radiusAt(0), 1e-6);
	}

	@Test
	void radiusInterpolatesLinearlyDuringStage() {
		ZoneModel z = standard();
		// P1: 150s 起、60s 收到 65% -> 中点 180s 应在 1.0 与 0.65 的中间
		assertEquals(140 * (1.0 + (0.65 - 1.0) * 0.5), z.radiusAt(180), 1e-6);
		assertEquals(140 * 0.65, z.radiusAt(210), 1e-3);
	}

	@Test
	void endgameKeepsShrinkingSoMatchesCannotStall() {
		ZoneModel z = standard();
		double at10min = z.radiusAt(660);
		double at20min = z.radiusAt(1260);
		assertTrue(at20min < at10min, "终局必须持续压缩，否则两队可以各自躲到超时");
		assertTrue(at20min > 0, "但不得压到 0（否则无处可站）");
	}

	@Test
	void damageIsMonotonicAndAppliesAfterStageStart() {
		ZoneModel z = standard();
		assertEquals(0.0, z.damageAt(100), 1e-9);
		assertEquals(0.5, z.damageAt(151), 1e-9);
		assertEquals(4.0, z.damageAt(600), 1e-9);
	}

	@Test
	void warnWindowOnlyBeforeNextStage() {
		ZoneModel z = standard();
		assertEquals(10, z.warnCountdownAt(200), "P2 在 210s，提前 15s 预告 -> 200s 时剩 10s");
		assertEquals(-1, z.warnCountdownAt(300), "不在任何预告窗口内");
	}

	@Test
	void ringLookupIsHalfOpen() {
		ZoneModel z = standard();
		assertNotNull(z.ringOf(0));
		assertEquals(3, z.ringOf(27.9).tier());
		assertEquals(2, z.ringOf(28).tier(), "边界归外侧环带，避免同一点被两个 tier 命中");
		assertEquals(1, z.ringOf(139).tier());
		assertNull(z.ringOf(141), "圈外不该有物资 tier");
	}

	@Test
	void outsideCheckUsesCurrentRadius() {
		ZoneModel z = standard();
		assertFalse(z.isOutside(100, 0));
		assertTrue(z.isOutside(100, 600));
	}

	@Test
	void validationRejectsHoleBetweenRings() {
		ZoneModel bad = ZoneModel.of(List.of(new ZoneModel.Stage(0, 0.5, 100, 30, 1.0, 10)),
				List.of(new ZoneModel.Ring(0, 100, 1.0, 1), new ZoneModel.Ring(101, 140, 2.0, 2)), 140);
		List<String> errors = bad.validate();
		assertTrue(errors.stream().anyMatch(e -> e.contains("空洞")), "必须报出字段级空洞错误，而不是自动补齐");
	}

	@Test
	void validationRejectsGrowingZoneOrSofteningDamage() {
		ZoneModel bad = ZoneModel.of(List.of(
				new ZoneModel.Stage(0, 0.8, 100, 30, 2.0, 10),
				new ZoneModel.Stage(1, 0.9, 200, 30, 1.0, 10)), List.of(), 100);
		List<String> errors = bad.validate();
		assertTrue(errors.stream().anyMatch(e -> e.contains("radiusScale")));
		assertTrue(errors.stream().anyMatch(e -> e.contains("单调不减")));
	}

	@Test
	void validationRejectsRingsNotCoveringRadius() {
		ZoneModel bad = ZoneModel.of(List.of(new ZoneModel.Stage(0, 0.5, 100, 30, 1.0, 10)),
				List.of(new ZoneModel.Ring(0, 120, 1.0, 1)), 140);
		assertTrue(bad.validate().stream().anyMatch(e -> e.contains("覆盖 sea.radius")));
	}
}
