package dev.nbw.domain.rescue;

import dev.nbw.domain.rescue.ClingRule.Stage;
import dev.nbw.domain.rescue.ClingRule.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扒边自救的状态机（01 §8）。
 *
 * <p>这组测试盯的是三段最容易悄悄错的地方：<b>时序</b>（2 秒就是 40 tick，一秒不多一秒不少）、
 * <b>打断之后必须从零开始</b>（留一半进度就是白送）、
 * <b>每一道输入门各自都能单独断开攀爬</b>（否则"松手就掉下去"只是注释里写着好看）。
 *
 * <p>断言一律比 {@code stage()} 而不是整个 {@code State}：record 里还带着 tick，
 * 直接比会写成"期望 OFF，实际 State[stage=OFF, tick=0]"这种永远不相等的假失败（我第一次就踩了）。
 */
class ClingRuleTest {

	private static final double EPS = 1e-9;

	private static State cling(State prev) {
		return ClingRule.next(prev, true, true, true, true, true, 0.0, false);
	}

	/** 从给定状态走到 LANDED 要几个 tick（比抄一个常量更能抓住时序回归）。 */
	private static int ticksToLand(State from) {
		State s = from;
		int n = 0;
		while (s.stage() != Stage.LANDED && n < 500) {
			s = cling(s);
			n++;
		}
		return n;
	}

	@Test
	void grabThenTwoSecondsThenLanded() {
		State first = cling(State.off());
		assertEquals(Stage.GRAB, first.stage(), "第一拍应当是扒住，不是直接起飞");
		State s = first;
		int ticks = 0;
		while (s.stage() != Stage.LANDED && ticks < 200) {
			s = cling(s);
			ticks++;
		}
		assertEquals(Stage.LANDED, s.stage(), "持续扒边最终必须上筏，否则自救这条路是死的");
		assertEquals(ClingRule.CLIMB_TICKS, ticks, "从 GRAB 起再走 2 秒就该上筏，实测 " + ticks);
		assertEquals(ClingRule.CLIMB_TICKS, s.tick(), "状态里记的 tick 必须与真实推进次数一致");
		// GRAB 那一拍不出力，所以冷启动是 41 tick；写死这条，改时序的人会看见自己动了什么
		assertEquals(ClingRule.CLIMB_TICKS + 1, ticksToLand(State.off()));
		assertNull(ClingRule.denyReason(true, true, true), "自家筏、PLAY、筏还在——不该有拒绝原因");
	}

	@Test
	void landedIsASingleTickEventThenReleases() {
		State s = State.off();
		for (int i = 0; i < 200 && s.stage() != Stage.LANDED; i++) {
			s = cling(s);
		}
		assertEquals(Stage.LANDED, s.stage());
		State after = cling(s);
		assertEquals(Stage.OFF, after.stage(), "上筏之后必须交回普通移动，否则人会一直挂在贴地状态");
		assertEquals(0, after.tick(), "一次性事件之后计数要归零");
	}

	@Test
	void climbInterpolatesFromWhereTheyGrabbedOntoTheDeckSurface() {
		double base = 60.0;                       // 人扒着的时候泡在水里的 Y
		double deck = ClingRule.deckSurfaceY(63.0);
		assertEquals(64.0, deck, EPS, "甲板面 = 位姿原点 Y + 1（甲板方块占局部 y∈[0,1)）");
		assertFalse(ClingRule.liftsThisTick(Stage.GRAB), "扒住那一拍不许出力");
		assertTrue(ClingRule.liftsThisTick(Stage.CLIMB), "爬的那一拍必须出力");
		assertEquals(base, ClingRule.climbY(base, deck, 0), EPS, "第 0 拍必须还在原地");
		assertEquals((base + deck) / 2, ClingRule.climbY(base, deck, ClingRule.CLIMB_TICKS / 2), 1e-9,
				"插值必须线性：半程就是中点，实测 " + ClingRule.climbY(base, deck, ClingRule.CLIMB_TICKS / 2));
		assertEquals(deck, ClingRule.climbY(base, deck, ClingRule.CLIMB_TICKS), EPS,
				"2 秒终点必须正好是甲板面——多出来的高度是白送，少了就是没爬完");
		assertEquals(deck, ClingRule.climbY(base, deck, ClingRule.CLIMB_TICKS * 3), EPS,
				"超时不许继续往上叠（否则人会被顶到桅杆上去）");
		double prev = ClingRule.climbY(base, deck, 0);
		for (int tick = 1; tick <= ClingRule.CLIMB_TICKS; tick++) {
			double now = ClingRule.climbY(base, deck, tick);
			assertTrue(now >= prev - EPS, "高度必须随 tick 单调不减，tick=" + tick);
			prev = now;
		}
		// 起点比甲板面还高（比如筏子刚被浪抬起来）时，不许把人往下拽：这是绝对插值的边界
		assertTrue(ClingRule.climbY(70.0, deck, ClingRule.CLIMB_TICKS / 2) < 70.0,
				"起点高于甲板面时插值会往下走，这条行为要显式记着（适配层靠筏子实际位置避免误用）");
	}

	@Test
	void damageKnocksYouOffAndForgetsAllProgress() {
		State s = State.off();
		for (int i = 0; i < ClingRule.CLIMB_TICKS - 5; i++) {
			s = cling(s);
		}
		assertTrue(s.climbing(), "前置：确实已经爬到一半以上：" + s);
		State hit = ClingRule.next(s, true, true, true, true, true, 0.0, true);
		assertEquals(Stage.OFF, hit.stage(), "攀爬中被打必须掉回水里");
		assertEquals(0, hit.tick(), "被打之后进度必须清零，留一半就是白送");
		assertEquals(ticksToLand(State.off()), ticksToLand(hit),
				"打断之后重来必须和完全冷启动一样久");
	}

	@Test
	void everyInputAloneCanBreakTheCling() {
		State mid = State.off();
		for (int i = 0; i < 10; i++) {
			mid = cling(mid);
		}
		assertTrue(mid.climbing(), "前置：正在爬");
		assertEquals(Stage.OFF, ClingRule.next(mid, false, true, true, true, true, 0.0, false).stage(), "换阶段");
		assertEquals(Stage.OFF, ClingRule.next(mid, true, false, true, true, true, 0.0, false).stage(), "不是自家筏");
		assertEquals(Stage.OFF, ClingRule.next(mid, true, true, false, true, true, 0.0, false).stage(), "筏没了");
		assertEquals(Stage.OFF, ClingRule.next(mid, true, true, true, false, true, 0.0, false).stage(), "松手");
		// 爬的过程中离开水面是必然的（人要从水里升到甲板面上），所以这一条不再是断开的理由；
		// "必须在水里"只用来限制起爬，见下面 climbingAboveWaterlineKeepsClimbing
		assertTrue(ClingRule.next(mid, true, true, true, true, false, 0.0, false).climbing(),
				"已经爬了 10 拍的人离开水面必须还在爬，否则永远爬不完");
		assertEquals(Stage.OFF, ClingRule.next(mid, true, true, true, true, true,
				ClingRule.HULL_REACH + 0.01, false).stage(), "间隙超出伸手范围");
		assertTrue(cling(mid).climbing(), "同样的输入什么都不改时必须还在爬（上面六条不是恒真）");
	}

	@Test
	void climbingAboveTheWaterlineStillFinishesButDryStartIsRefused() {
		// 这条是实测逼出来的：人在爬到一半时必然高出水面，一路要求在水里就会半路弹回水里，
		// rescue_probe 当时看到"扒住 3 次才上筏 1 次"，终点 Y 卡在水面线下面
		State s = State.off();
		for (int i = 0; i < 12; i++) {
			s = ClingRule.next(s, true, true, true, true, true, 0.0, false);
		}
		assertTrue(s.climbing(), "先在爬");
		State dry = ClingRule.next(s, true, true, true, true, false, 0.0, false);
		assertTrue(dry.climbing(), "爬的过程中人离开水面也必须继续爬完");
		assertEquals(s.tick() + 1, dry.tick(), "继续爬的时候 tick 必须接着走，不能重开");
		// 反过来：没下水、直接站在筏上按着，不该白送一次"自救"
		assertEquals(Stage.OFF, ClingRule.next(State.off(), true, true, true, true, false, 0.0, false).stage(),
				"不在水里就不许起爬（那不算落水自救）");
	}

	@Test
	void reachBoundaryIsInclusiveThenExclusive() {
		assertEquals(Stage.GRAB, ClingRule.next(State.off(), true, true, true, true, true,
				ClingRule.HULL_REACH, false).stage(), "正好够得着时应当能扒住");
		assertEquals(Stage.OFF, ClingRule.next(State.off(), true, true, true, true, true,
				ClingRule.HULL_REACH + 0.001, false).stage(), "差一丝就够不着，这才会逼出队友救援");
	}

	@Test
	void hullGapMeasuresTheHullNotTheDeckCenter() {
		double half = 2.0;                       // 4×4 筏：footprint 半边宽
		assertEquals(0.0, ClingRule.hullGap(0, 0, half), EPS, "筏中心没有间隙");
		assertEquals(0.0, ClingRule.hullGap(half, half, half), EPS, "角上仍在 footprint 内");
		assertEquals(1.5, ClingRule.hullGap(half + 1.5, 0, half), EPS, "正东 1.5 格外");
		assertEquals(1.5, ClingRule.hullGap(0, -(half + 1.5), half), EPS, "四个方向必须对称");
		assertEquals(Math.hypot(1.0, 2.0), ClingRule.hullGap(half + 1, -(half + 2), half), 1e-9,
				"斜向按欧氏距离算");
		assertTrue(ClingRule.hullGap(half + ClingRule.HULL_REACH, 0, half) <= ClingRule.HULL_REACH + EPS,
				"正好伸手距离的那一格必须判成够得着");
		assertTrue(ClingRule.hullGap(half + ClingRule.HULL_REACH + 0.5, 0, half) > ClingRule.HULL_REACH,
				"再远半格就该判够不着");
	}

	@Test
	void denyReasonsNameDifferentProblems() {
		String phase = ClingRule.denyReason(false, true, true);
		String noRaft = ClingRule.denyReason(true, true, false);
		String notMine = ClingRule.denyReason(true, false, true);
		for (String r : new String[]{phase, noRaft, notMine}) {
			assertNotNull(r, "该拒的必须给原因");
		}
		assertNotEquals(phase, noRaft);
		assertNotEquals(noRaft, notMine);
		assertTrue(notMine.contains("自己队"), "越权的原因要说清是谁的筏：" + notMine);
	}
}
