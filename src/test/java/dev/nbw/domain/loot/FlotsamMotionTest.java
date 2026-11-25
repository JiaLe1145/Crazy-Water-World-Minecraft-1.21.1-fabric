package dev.nbw.domain.loot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 海面漂浮物的水面运动（2026-11-25 新增）。
 *
 * <p>这里锁的是产品口径里最容易被改坏的两条：**所有箱子同向**、**漂移不失控**。
 * 任何"给每箱加速度随机量"的改动都会让 {@link #velocityIsIdenticalForEveryPhase()} 变红。
 */
class FlotsamMotionTest {

	private static final double BASE_PER_TICK = FlotsamMotion.FLOW_SPEED_PER_SECOND / 20.0;

	/** 洋流速度不接受相位：这是"所有箱子同向同速"的实现点。 */
	@Test
	void velocityIsIdenticalForEveryPhase() {
		for (int t = 0; t < 2000; t += 7) {
			double[] a = FlotsamMotion.velocity(t);
			double[] b = FlotsamMotion.velocity(t);
			assertEquals(a[0], b[0], 0.0, "同一 tick 必须给出同一个 dx");
			assertEquals(a[1], b[1], 0.0, "同一 tick 必须给出同一个 dz");
		}
	}

	/** 整段生命周期里方向都不许变（只允许速度有阵性）。 */
	@Test
	void directionNeverDrifts() {
		double len = Math.hypot(FlotsamMotion.FLOW_X, FlotsamMotion.FLOW_Z);
		double nx = FlotsamMotion.FLOW_X / len;
		double nz = FlotsamMotion.FLOW_Z / len;
		for (long t = 0; t <= 20 * 600; t += 13) {
			double[] v = FlotsamMotion.velocity(t);
			assertEquals(nx, v[0] / Math.hypot(v[0], v[1]), 1.0e-9, "tick=" + t + " 的 dx 方向偏了");
			assertEquals(nz, v[1] / Math.hypot(v[0], v[1]), 1.0e-9, "tick=" + t + " 的 dz 方向偏了");
		}
	}

	/** 速度必须落在基准值的 ±阵性 之内，且始终为正（不许出现倒退）。 */
	@Test
	void speedStaysInsideGustBand() {
		double lo = BASE_PER_TICK * (1.0 - FlotsamMotion.GUST_RATIO);
		double hi = BASE_PER_TICK * (1.0 + FlotsamMotion.GUST_RATIO);
		for (long t = 0; t <= 20 * 600; t += 3) {
			double speed = FlotsamMotion.speedPerTick(t);
			assertTrue(speed > 0.0, "tick=" + t + " 速度必须为正");
			assertTrue(speed >= lo - 1.0e-12 && speed <= hi + 1.0e-12,
					"tick=" + t + " 速度 " + speed + " 超出 [" + lo + ", " + hi + "]");
		}
	}

	/** 一分钟的漂移量要"看得出来"，又不能一转眼跑出视野。 */
	@Test
	void oneMinuteDriftIsVisibleButSlow() {
		int ticks = 20 * 60;
		double distance = 0.0;
		for (int t = 0; t < ticks; t++) {
			distance += Math.hypot(FlotsamMotion.velocity(t)[0], FlotsamMotion.velocity(t)[1]);
		}
		assertTrue(distance > 15.0, "一分钟只漂 " + distance + " 格，玩家看不出来在动");
		assertTrue(distance < 21.0, "一分钟漂了 " + distance + " 格，已经不像漂浮物");
	}

	/** 起伏幅度受限、且整周期均值为 0（不会越漂越高）。 */
	@Test
	void bobStaysWithinAmplitudeAndAveragesToZero() {
		double sum = 0.0;
		int samples = 0;
		for (int t = 0; t < 20 * 60; t++) {
			double bob = FlotsamMotion.bob(t, 1.234);
			assertTrue(Math.abs(bob) <= FlotsamMotion.BOB_AMPLITUDE + 1.0e-12,
					"tick=" + t + " 起伏 " + bob + " 超过幅度");
			sum += bob;
			samples++;
		}
		assertEquals(0.0, sum / samples, 5.0e-3, "起伏均值必须贴近 0，否则箱子会整体浮高/沉底");
	}

	/** 摇晃角受限（渲染只做小角度，超过 10° 就像翻箱）。 */
	@Test
	void rollAndPitchStaySmall() {
		for (int t = 0; t < 20 * 60; t++) {
			double roll = FlotsamMotion.rollDeg(t, 0.7);
			double pitch = FlotsamMotion.pitchDeg(t, 0.7);
			assertTrue(Math.abs(roll) <= FlotsamMotion.ROLL_DEG + 1.0e-12, "tick=" + t + " 横滚过大：" + roll);
			assertTrue(Math.abs(pitch) <= FlotsamMotion.PITCH_DEG + 1.0e-12, "tick=" + t + " 纵摇过大：" + pitch);
			assertTrue(FlotsamMotion.ROLL_DEG <= 10.0 && FlotsamMotion.PITCH_DEG <= 10.0,
					"摇晃幅度上限必须是小角度");
		}
	}

	/** 不同相位的箱子只差在摇晃/起伏，水平方向仍是同一条洋流。 */
	@Test
	void phasesOnlyChangeBobbingNotDirection() {
		double[] v0 = FlotsamMotion.velocity(321);
		for (double phase = 0.0; phase < Math.PI * 2.0; phase += 0.37) {
			assertNotEquals(FlotsamMotion.bob(321, phase), FlotsamMotion.bob(321, phase + 1.0),
					"相位不同就必须有不同起伏（否则所有箱子同步上下，像同一块木板）");
			assertEquals(v0[0], FlotsamMotion.velocity(321)[0], 0.0, "水平速度不能随相位变");
			assertEquals(v0[1], FlotsamMotion.velocity(321)[1], 0.0, "水平速度不能随相位变");
		}
	}

	/** 读数是中文 8 方位：+X = 东。 */
	@Test
	void flowLabelIsEastByDefault() {
		assertEquals("东", FlotsamMotion.flowLabel());
	}
}
