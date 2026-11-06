package dev.nbw.domain.raft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桨的方向口径（M2-5，#39 之后的形状）。
 *
 * <p>桨这一层不再有"推力常数"：快慢由 {@link SpeedGovernor} 的位移档位决定，
 * 这里只管<b>谁往哪儿划</b>与<b>方向相反的桨要互相抵消</b>。
 * 抵消是净份数算式自然给出来的结果，所以这几条断言就是那条结果本身。
 */
class OarModelTest {

	private static final double EPS = 1e-12;

	private static double[] rows(double[][] dirs) {
		double[] sum = null;
		for (double[] d : dirs) {
			sum = OarModel.addDirection(sum, d[0], d[1]);
		}
		return sum;
	}

	@Test
	void oppositeStrokesCancelToZeroNetStrokes() {
		double[] sum = rows(new double[][]{{-1, 0}, {-1, 0}, {1, 0}, {1, 0}});
		assertEquals(0.0, sum[0], EPS, "两西两东必须抵消干净");
		assertEquals(0.0, OarModel.effectiveStrokes(sum[0], sum[1]), EPS,
				"抵消干净的桨不能还留出档位请求，否则筏子会朝其中一个方向慢慢偏");
	}

	@Test
	void partialCancellationKeepsNetAndSign() {
		double[] sum = rows(new double[][]{{-1, 0}, {-1, 0}, {1, 0}});
		assertEquals(1.0, OarModel.effectiveStrokes(sum[0], sum[1]), EPS, "两西一东应当净一份桨力");
		double[] unit = OarModel.unit(sum[0], sum[1]);
		assertEquals(-1.0, unit[0], EPS, "净方向必须是西（-X）；符号反了筏子会往后退");
		assertEquals(0.0, unit[1], EPS, "东西向的划桨不该漏出南北分量");
	}

	@Test
	void sameDirectionStrokesAddUpOnePerRower() {
		double[] sum = rows(new double[][]{{0, 1}, {0, 1}, {0, 1}, {0, 1}});
		assertEquals(4.0, OarModel.effectiveStrokes(sum[0], sum[1]), EPS, "4 人同向应当是 4 份桨力");
		double[] unit = OarModel.unit(sum[0], sum[1]);
		assertEquals(1.0, unit[1], EPS, "south 是 +Z（Minecraft 的 Z 轴向南）");
	}

	@Test
	void axisConventionIsNorthNegativeZ() {
		// 最容易搞错又最难发现的一步：north/south 反了不会报错，只会"往北划却在退"
		double[] north = OarModel.unit(0, -1);
		assertEquals(-1.0, north[1], EPS, "north 必须是 -Z");
		double[] west = OarModel.unit(-1, 0);
		assertEquals(-1.0, west[0], EPS, "west 必须是 -X");
	}

	@Test
	void dirtyDirectionsAreNotCountedAsStrokes() {
		double[] sum = rows(new double[][]{{0.7071, 0.7071}, {2, 0}, {0, 0},
				{Double.NaN, 1}, {Double.POSITIVE_INFINITY, 0}});
		assertEquals(0.0, sum[0], EPS, "脏方向一律不算份数：算进去就等于凭空多出一份动力");
		assertEquals(0.0, sum[1], EPS);
		assertEquals(0.0, OarModel.effectiveStrokes(sum[0], sum[1]), EPS);
	}

	@Test
	void isAxisAlignedAcceptsExactlyOneUnitComponent() {
		assertTrue(OarModel.isAxisAligned(-1, 0));
		assertTrue(OarModel.isAxisAligned(0, 1));
		assertTrue(OarModel.isAxisAligned(1.0, 0.0));
		assertFalse(OarModel.isAxisAligned(-1, -1), "两个分量都非零就是斜向");
		assertFalse(OarModel.isAxisAligned(0, 0), "全零不是方向");
		assertFalse(OarModel.isAxisAligned(0.5, 0), "非单位分量会被当成合法方向");
		assertFalse(OarModel.isAxisAligned(Double.NaN, 1), "NaN 不能算方向");
		assertFalse(OarModel.isAxisAligned(1.5, 0), "分量超 1 等于自带放大器");
	}

	@Test
	void unitHandlesDegenerateInputInsteadOfProducingNan() {
		double[] zero = OarModel.unit(0, 0);
		assertEquals(0.0, zero[0], EPS, "零向量的单位化必须是 (0,0)，让调用方跳过施力");
		assertEquals(0.0, zero[1], EPS);
		double[] scaled = OarModel.unit(3, 4);
		assertEquals(0.6, scaled[0], 1e-9, "合方向要归一化，否则档位数会被矢量长度污染");
		assertEquals(0.8, scaled[1], 1e-9);
		assertTrue(!Double.isNaN(OarModel.unit(Double.NaN, 1)[0]), "NaN 输入不能把 NaN 传出去");
	}

	@Test
	void effectiveStrokesIsTheVectorMagnitudeNotHeadcount() {
		// 净份数用模长：3 人里 2 南 1 北 = 净 1 份，而不是 3 份
		double[] sum = rows(new double[][]{{0, 1}, {0, 1}, {0, -1}});
		assertEquals(1.0, OarModel.effectiveStrokes(sum[0], sum[1]), EPS);
		// 斜交的两股力（南 + 东）各算各的方向，合成后模长是 sqrt(2)：
		// 这正是"档位是矢量口径"的体现，标量求和会把它算成 2
		double[] diag = rows(new double[][]{{0, 1}, {1, 0}});
		assertEquals(Math.sqrt(2.0), OarModel.effectiveStrokes(diag[0], diag[1]), 1e-9);
	}
}
