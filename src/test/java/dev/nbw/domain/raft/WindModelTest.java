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
	void windUnitsIsSailCountTimesWindStrength() {
		// 航行数值现在只认"迎风帆数 × 风强"这一个无量纲量（SpeedGovernor 的档位输入），
		// 所以它必须与已批准的 02 §6 比例严格一致，不能偷偷带上 FORCE_PER_SAIL。
		assertEquals(6.0, WindModel.windUnits(3, 0, 0, 0, new Wind(0.0, -1.0, 2.0)), 1e-9);
		assertEquals(12.0, WindModel.windUnits(6, 0, 0, 0, new Wind(0.0, -1.0, 2.0)), 1e-9);
		// 风强翻倍，份数也翻倍（每块帆的份数是 迎风 × 帆数，迎风里已经含了一次风强）：
		// 风暴事件因此真的能推得更快，这条就是那个断言。
		assertEquals(12.0, WindModel.windUnits(3, 0, 0, 0, new Wind(0.0, -1.0, 4.0)), 1e-9);
	}

	@Test
	void windUnitsCancelsLeewardAndCrosswiseSails() {
		// 背风不受力（不是负数）：北风下 3 块朝南的帆一份都不算，所以 3 北 + 3 南 = 3 北 = 6 份
		// （每块帆的份数是 迎风 1 × 风强 2）。
		assertEquals(6.0, WindModel.windUnits(3, 3, 0, 0, new Wind(0.0, -1.0, 2.0)), 1e-9);
		assertEquals(0.0, WindModel.windUnits(0, 0, 4, 0, new Wind(0.0, -1.0, 2.0)), 1e-9);
		// 斜风下北与东两块帆都受风，但它们是互相垂直的贡献：档位取的是合矢量的模长，
		// 不是把份数直接相加（sqrt(1.414²+1.414²)=2.0）。这条断言就是"矢量口径"的证据，
		// 哪天有人把它改成标量求和，这里会立刻红。
		double diag = WindModel.windUnits(1, 1, 1, 1, new Wind(0.7071, -0.7071, 2.0));
		assertEquals(2.0, diag, 1e-3);
	}

	@Test
	void windUnitsIsZeroWhenCalmOrUnrigged() {
		assertEquals(0.0, WindModel.windUnits(5, 0, 0, 0, Wind.none()), 1e-9);
		assertEquals(0.0, WindModel.windUnits(0, 0, 0, 0, new Wind(0.0, -1.0, 2.0)), 1e-9);
		assertTrue(!Double.isNaN(WindModel.windUnits(3, 0, 0, 0, new Wind(0.0, -1.0, 0.0))),
				"静风必须给 0 档，不能给 NaN —— NaN 会一路污染到刚体速度");
	}
}
