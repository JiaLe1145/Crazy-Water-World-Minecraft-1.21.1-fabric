package dev.nbw.domain.raft;

import org.junit.jupiter.api.Test;

import dev.nbw.domain.raft.WindModel.Wind;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 风对帆的作用模型（M2-5）。
 *
 * <p>这一层是"帆到底能不能当动力"的唯一真相来源，而且它<b>不需要起服就能测</b>——
 * 风推不推得动筏子是物理库的事，但"给定风和帆朝向应该得到什么方向的力"是我们自己的规则，
 * 必须先在没有物理的环境里锁死，否则真机上分不清是规则错还是物理错。
 */
class WindModelTest {

	private static final double EPS = 1e-9;

	// Minecraft 轴：+X 东，-X 西，+Z 南，-Z 北

	@Test
	void windBlowingNorthPushesNorthFacingSailNorth() {
		double[] f = WindModel.thrustOn(0.0, -1.0, 4, new Wind(0.0, -1.0, 1.0));
		assertEquals(0.0, f[0], EPS);
		assertTrue(f[1] < 0.0, "朝北的帆遇到北风应当得到向北（-Z）的力，实际 " + f[1]);
	}

	@Test
	void sailFacingPerpendicularToWindGetsNothing() {
		// 帆朝北、风从东边吹：法向与风垂直，投影为 0
		double[] f = WindModel.thrustOn(0.0, -1.0, 6, new Wind(1.0, 0.0, 2.0));
		assertEquals(0.0, f[0], EPS);
		assertEquals(0.0, f[1], EPS);
	}

	@Test
	void leewardSailIsClampedToZeroNotPushedBackwards() {
		// 帆朝南、风往北吹（背面受风）：不该得到反向力
		double[] f = WindModel.thrustOn(0.0, 1.0, 5, new Wind(0.0, -1.0, 3.0));
		assertEquals(0.0, f[0], EPS);
		assertEquals(0.0, f[1], EPS);
	}

	@Test
	void thrustGrowsLinearlyWithSailCount() {
		Wind w = new Wind(0.0, -1.0, 1.0);
		double one = WindModel.thrustOn(0.0, -1.0, 1, w)[1];
		double five = WindModel.thrustOn(0.0, -1.0, 5, w)[1];
		assertEquals(one * 5.0, five, 1e-6);
	}

	@Test
	void thrustGrowsLinearlyWithStrength() {
		double weak = WindModel.thrustOn(0.0, -1.0, 3, new Wind(0.0, -1.0, 1.0))[1];
		double strong = WindModel.thrustOn(0.0, -1.0, 3, new Wind(0.0, -1.0, 2.5))[1];
		assertEquals(weak * 2.5, strong, 1e-6);
	}

	@Test
	void noSailsOrCalmWindProduceNothing() {
		assertArrayEquals(new double[]{0.0, 0.0},
				WindModel.thrustOn(0.0, -1.0, 0, new Wind(0.0, -1.0, 3.0)), EPS);
		assertArrayEquals(new double[]{0.0, 0.0},
				WindModel.thrustOn(0.0, -1.0, 7, Wind.none()), EPS);
		assertTrue(Wind.none().calm());
		assertFalse(new Wind(0.0, -1.0, 0.01).calm());
	}

	@Test
	void fleetWithMixedFacingOnlyCountsWindwardSails() {
		// 北风：朝北的 3 块受力，朝南的 2 块背风不受力，东西向 4 块垂直不受力
		double[] f = WindModel.fleetThrust(3, 2, 2, 2, new Wind(0.0, -1.0, 1.0));
		assertEquals(0.0, f[0], EPS);
		assertEquals(-3.0 * WindModel.FORCE_PER_SAIL, f[1], 1e-6);
	}

	@Test
	void fleetSumsIndependentAxes() {
		// 东北风：朝北与朝东的帆都迎风，合力应当同时有 -X 与 -Z 分量
		double[] f = WindModel.fleetThrust(2, 0, 0, 0, new Wind(0.7071, -0.7071, 2.0));
		assertTrue(f[1] < 0.0);
		assertEquals(0.0, f[0], EPS);   // 帆朝北，法向没有 X 分量，风再大也推不出 X 方向的力

		double[] both = WindModel.fleetThrust(2, 0, 3, 0, new Wind(0.7071, -0.7071, 2.0));
		assertTrue(both[0] > 0.0, "朝东的帆被东北风推向 +X");
		assertTrue(both[1] < 0.0, "朝北的帆被东北风推向 -Z");
	}

	@Test
	void thrustIsAlwaysParallelToTheSailNormalForAxisAlignedNormals() {
		// Sable 取的是 Vec3.atLowerCornerOf(dir.getNormal())，法向只能是轴对齐单位矢量，
		// 所以"斜向帆"这个输入我们永远拿不到——之前拿它编了个期望，那是测不存在的情况。
		// 真正要锁的是不变量：推力方向永远与法向同向，且不会凭空多出垂直分量。
		double[][] normals = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
		Wind[] winds = {new Wind(0, -1, 2), new Wind(1, 0, 2), new Wind(0.7071, -0.7071, 3),
				new Wind(-0.5, 0.5, 1)};
		for (double[] nrm : normals) {
			for (Wind w : winds) {
				double[] f = WindModel.thrustOn(nrm[0], nrm[1], 3, w);
				// 平行：叉积为 0
				double cross = nrm[0] * f[1] - nrm[1] * f[0];
				assertEquals(0.0, cross, 1e-6,
						"法向 (" + nrm[0] + "," + nrm[1] + ") 风 (" + w.dirX() + "," + w.dirZ()
								+ ") 得到 (" + f[0] + "," + f[1] + ")，不平行于法向");
				// 同向或为零：点积非负
				assertTrue(nrm[0] * f[0] + nrm[1] * f[1] >= -1e-9, "推力不该与法向反向");
			}
		}
	}

	@Test
	void strengthRangeRejectsOnlyOutOfRange() {
		assertTrue(WindModel.strengthInRange(0.0));
		assertTrue(WindModel.strengthInRange(WindModel.MAX_STRENGTH));
		assertFalse(WindModel.strengthInRange(-0.01));
		assertFalse(WindModel.strengthInRange(WindModel.MAX_STRENGTH + 0.01));
	}

	@Test
	void maxStrengthIsSmallEnoughToStayOneOfSeveralPowerSources() {
		// 风不该吃掉桨与其它动力来源；上限留在个位数是这条约束的直接体现
		assertTrue(WindModel.MAX_STRENGTH <= 8.0, "风强上限过大：" + WindModel.MAX_STRENGTH);
	}

	// ---------------------------------------------------------------- 航速上限

	@Test
	void headroomIsFullAtRestAndZeroAtCap() {
		assertEquals(1.0, WindModel.headroom(0.0, WindModel.MAX_SAIL_SPEED), EPS);
		assertEquals(0.0, WindModel.headroom(WindModel.MAX_SAIL_SPEED, WindModel.MAX_SAIL_SPEED), EPS);
	}

	@Test
	void headroomNeverGoesNegativeOrAboveOne() {
		// 超上限、负速度都不该产出负系数，也不该产出 >1 的"免费加速"
		assertEquals(0.0, WindModel.headroom(WindModel.MAX_SAIL_SPEED * 3.0, WindModel.MAX_SAIL_SPEED), EPS);
		assertEquals(1.0, WindModel.headroom(-1.0, WindModel.MAX_SAIL_SPEED), EPS);
		for (double v = 0.0; v <= WindModel.MAX_SAIL_SPEED * 1.5; v += 0.01) {
			double h = WindModel.headroom(v, WindModel.MAX_SAIL_SPEED);
			assertTrue(h >= 0.0 && h <= 1.0, "v=" + v + " 余量越界：" + h);
		}
	}

	@Test
	void headroomDecreasesMonotonicallyWithSpeed() {
		double prev = 1.0;
		for (double v = 0.0; v < WindModel.MAX_SAIL_SPEED; v += 0.005) {
			double h = WindModel.headroom(v, WindModel.MAX_SAIL_SPEED);
			assertTrue(h <= prev + 1e-12, "v=" + v + " 余量没随速度单调下降：" + h + " > " + prev);
			prev = h;
		}
	}

	@Test
	void zeroOrNegativeCapStopsThrustInsteadOfProducingNaN() {
		// 除零会得到 NaN，NaN 乘进推力就是失控的筏子；上限为 0 的语义必须是"不推进"
		assertEquals(0.0, WindModel.headroom(0.1, 0.0), EPS);
		assertEquals(0.0, WindModel.headroom(0.1, -1.0), EPS);
	}

	@Test
	void softKneeKeepsMidSpeedRangeNearlyUnpenalised() {
		// 四次方拐点存在的意义：一半速度时几乎不吃力，否则"帆多更快"会被余量提前压平
		assertTrue(WindModel.headroom(WindModel.MAX_SAIL_SPEED * 0.5, WindModel.MAX_SAIL_SPEED) > 0.9,
				"半速就收力，帆数差异会被上限抹平");
		assertTrue(WindModel.headroom(WindModel.MAX_SAIL_SPEED * 0.95, WindModel.MAX_SAIL_SPEED) < 0.2,
				"逼近上限时收力不够，上限形同虚设");
	}

	@Test
	void calibrationTripwireSailCountsSpanTheCap() {
		// 真机标定（M2-RESULTS §3.8）：水阻 ≈ 3.88 × 速度（3 帆 + 风强 2 时自然终端 1.546 格/tick，
		// 当时每帆推力系数是 1.0）。用它当绊线：上限既不能高到永远碰不到，也不能低到把所有船压成一个速度。
		double dragPerSpeed = 3.88;
		double typicalWind = 2.0;
		double vOne = 1 * WindModel.FORCE_PER_SAIL * typicalWind / dragPerSpeed;
		double vSeven = 7 * WindModel.FORCE_PER_SAIL * typicalWind / dragPerSpeed;
		assertTrue(vOne < WindModel.MAX_SAIL_SPEED * 0.5,
				"1 帆就已经贴着上限，帆数将不再影响航速：" + vOne);
		assertTrue(vSeven > WindModel.MAX_SAIL_SPEED,
				"满帆的自然速度都碰不到上限，上限是死代码：" + vSeven);
	}
}
