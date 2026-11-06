package dev.nbw.domain.raft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桨力口径单测（M2-5）。
 *
 * <p>这里最重要的不是"算得对"，是<b>标定值别被无声改掉</b>：
 * {@code THRUST_PER_OAR} 是从两个实测锚点推出来的，改它等于改玩法档位表。
 * 所以两条绊线一上一下：单桨必须明显低于上限（否则帆数与桨数都不再影响航速），
 * 满员划桨必须能顶到上限（否则上限对桨是死代码）。
 */
class OarModelTest {

	/** 实测水阻（M2-RESULTS §3.9）：1 帆在风强 2 时有效推力 0.4、终端 0.156 格/tick。 */
	private static final double DRAG_PER_SPEED = 0.4 / 0.156;
	private static final double MEASURED_ONE_SAIL_SPEED = 0.156;

	private static double soloSpeed() {
		return OarModel.THRUST_PER_OAR / DRAG_PER_SPEED;
	}

	@Test
	void soloRowerIsTwoThirdsOfOneSail() {
		double expected = MEASURED_ONE_SAIL_SPEED * OarModel.SPEED_RATIO_VS_ONE_SAIL;
		double actual = soloSpeed();
		// 0.0 挡不住任何错误，所以带宽取 ±15%：改常数、改比例、改水阻锚点都会掉出去
		assertTrue(Math.abs(actual - expected) <= expected * 0.15,
				"单桨终端速度应当是单帆的 2/3（P-01：桨 ≈2 格/秒），实测口径推算=" + actual
						+ " 期望=" + expected);
		assertTrue(actual > 0.08 && actual < 0.13,
				"单桨速度落在 0.08..0.13 格/tick 之外（≈1.6..2.6 格/秒），与 02 §6 的桨档不符：" + actual);
	}

	@Test
	void calibrationTripwireBracketsTheCap() {
		assertTrue(soloSpeed() < OarModel.MAX_SPEED * 0.5,
				"一个人划就贴着上限，桨数将不再影响航速：" + soloSpeed());
		// 满员 4 人一队再加码到 8 人（运维手改场地）时，自然速度必须越过上限，
		// 否则上限对桨是死代码，"桨风并用不越界"这条断言也就是在自说自话
		double crowded = 8 * OarModel.THRUST_PER_OAR / DRAG_PER_SPEED;
		assertTrue(crowded > OarModel.MAX_SPEED,
				"8 人划桨的自然速度都碰不到上限，上限对桨不起作用：" + crowded);
	}

	@Test
	void axisComponentsKeepTheSigns() {
		// 最容易搞错的一步：north = -Z、west = -X。符号反了不会报错，只会"往后退"
		assertEquals(-OarModel.THRUST_PER_OAR, OarModel.thrustOf(-1, 0)[0], 1e-12, "west 必须往 -X 推");
		assertEquals(-OarModel.THRUST_PER_OAR, OarModel.thrustOf(0, -1)[1], 1e-12, "north 必须往 -Z 推");
		assertEquals(OarModel.THRUST_PER_OAR, OarModel.thrustOf(1, 0)[0], 1e-12, "east 必须往 +X 推");
		assertEquals(OarModel.THRUST_PER_OAR, OarModel.thrustOf(0, 1)[1], 1e-12, "south 必须往 +Z 推");
	}

	@Test
	void dirtyDirectionsYieldNoThrust() {
		assertEquals(0.0, OarModel.thrustOf(0.7071, 0.7071)[0], 1e-12, "斜向不该出力");
		assertEquals(0.0, OarModel.thrustOf(0.7071, 0.7071)[1], 1e-12, "斜向不该出力");
		assertEquals(0.0, OarModel.thrustOf(2, 0)[0], 1e-12, "分量超 1 等于自带放大器，必须拒");
		assertEquals(0.0, OarModel.thrustOf(0, 0)[0], 1e-12, "全零是无向，不是推力");
		assertFalse(Double.isNaN(OarModel.THRUST_PER_OAR), "标定值本身不该是 NaN");
		assertTrue(OarModel.thrustOf(Double.NaN, 0)[0] == 0.0, "NaN 方向必须当没划");
		assertTrue(OarModel.thrustOf(Double.POSITIVE_INFINITY, 0)[0] == 0.0, "Inf 方向必须当没划");
	}

	@Test
	void isAxisAlignedAcceptsExactlyOneUnitComponent() {
		assertTrue(OarModel.isAxisAligned(-1, 0));
		assertTrue(OarModel.isAxisAligned(0, 1));
		assertTrue(OarModel.isAxisAligned(1.0, 0.0));
		// 这四条全是"看起来像方向"的脏值，放行任何一条都会让合力算式失去意义
		assertTrue(!OarModel.isAxisAligned(-1, -1), "两个分量都非零就是斜向");
		assertTrue(!OarModel.isAxisAligned(0, 0), "全零不是方向");
		assertTrue(!OarModel.isAxisAligned(0.5, 0), "非单位分量会被当成合法方向");
		assertTrue(!OarModel.isAxisAligned(Double.NaN, 1), "NaN 不能算方向");
	}

	@Test
	void oppositeRowersCancelExactly() {
		double[] sum = null;
		sum = OarModel.accumulate(sum, -1, 0);   // 一人往西
		sum = OarModel.accumulate(sum, 1, 0);    // 一人往东
		assertEquals(0.0, sum[0], 1e-12, "一西一东必须抵消干净");
		assertEquals(0.0, sum[1], 1e-12, "抵消不该漏出横向分量");
		// null 起点是刻意的：命令层拼合力时第一次调用还没有累加器
		assertNotEquals(0.0, OarModel.accumulate(null, 0, -1)[1], "null 起点应当等价于零起点");
	}

	@Test
	void sameDirectionRowersAddUp() {
		double[] sum = new double[]{0.0, 0.0};
		for (int i = 0; i < 4; i++) {
			sum = OarModel.accumulate(sum, 0, -1);
		}
		assertEquals(4 * OarModel.THRUST_PER_OAR, -sum[1], 1e-12, "4 人同向应当等于 4 倍单桨推力");
	}

	@Test
	void capIsSharedWithSailsAndConvergesSmoothly() {
		assertEquals(WindModel.MAX_SAIL_SPEED, OarModel.MAX_SPEED, 1e-12,
				"上限是船体的属性，桨与帆必须共用同一个数");
		double[] atRest = OarModel.scaledByCap(OarModel.THRUST_PER_OAR, 0.0, 0.0);
		assertEquals(OarModel.THRUST_PER_OAR, atRest[0], 1e-12, "静止时不该吃任何收口");
		double[] atCap = OarModel.scaledByCap(OarModel.THRUST_PER_OAR, 0.0, OarModel.MAX_SPEED);
		assertEquals(0.0, atCap[0], 1e-12, "到达上限必须归零，否则上限只是软建议");
		// headroom 用 (v/cap)^4：0.9 倍上限时还剩 1-0.9^4=0.34，0.95 倍时剩 0.19
		double[] nearCap = OarModel.scaledByCap(OarModel.THRUST_PER_OAR, 0.0, OarModel.MAX_SPEED * 0.9);
		assertTrue(nearCap[0] > 0.0 && nearCap[0] < OarModel.THRUST_PER_OAR * 0.4,
				"0.9 倍上限时收力不在 0..0.4 倍推力区间，上限要么形同虚设要么把动力砍死：" + nearCap[0]);
		double[] almostCap = OarModel.scaledByCap(OarModel.THRUST_PER_OAR, 0.0, OarModel.MAX_SPEED * 0.95);
		assertTrue(almostCap[0] > 0.0 && almostCap[0] < OarModel.THRUST_PER_OAR * 0.2,
				"0.95 倍上限时还没进一步收力，说明收口曲线不够陡：" + almostCap[0]);
	}

	@Test
	void scaledByCapGuardsAgainstDirtyInputs() {
		assertEquals(0.0, OarModel.scaledByCap(Double.NaN, 1.0, 0.1)[0], 1e-12, "NaN 推力必须归零");
		assertEquals(0.0, OarModel.scaledByCap(1.0, 0.0, Double.NaN)[1], 1e-12, "NaN 速度必须归零");
		// 反向：负速度（读错符号）不该被当成"倒着划得飞快"
		double[] back = OarModel.scaledByCap(OarModel.THRUST_PER_OAR, 0.0, -0.3);
		assertTrue(back[0] >= OarModel.THRUST_PER_OAR - 1e-12,
				"负速度按 headroom 的口径等于静止，不该反过来加力：" + back[0]);
	}
}
