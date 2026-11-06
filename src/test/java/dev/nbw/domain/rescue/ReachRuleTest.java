package dev.nbw.domain.rescue;

import dev.nbw.domain.rescue.ReachRule.Stage;
import dev.nbw.domain.rescue.ReachRule.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队友伸手救援的状态机与资格闸门（01 §8，动作在<b>救援方</b>那一侧）。
 *
 * <p>这一组要守住的三件事，恰好是三条最容易"错了也不报"的地方：
 * <b>时序</b>（02 §8 写 1 秒就是 20 tick，不能因为谁都觉得"多等两拍无所谓"而漂）；
 * <b>闸门各自单独拦得住</b>（尤其"伸手上限"这一道——它一旦形同虚设，全图捞人就变成常规操作，
 * 自救那 2 秒和"游回筏边"这个玩法动机会直接被掏空）；
 * <b>中途失效必须从零开始</b>（留一半进度等于下一次白送，跟 #39 那类"两处各写一半"是同一种病）。
 *
 * <p>断言一律比 {@code stage()} 而不是整个 {@code State}（record 带 tick，直接比会写出恒不等）。
 */
class ReachRuleTest {

	private static final double EPS = 1e-9;

	/** 全开的输入：PLAY、筏在、同队、不是自己、被救者在水里且扒着得、距离在伸手上限内。 */
	private static State reach(State prev) {
		return ReachRule.next(prev, true, true, false);
	}

	/** 从给定状态推到 PULLED 要几拍（比抄常量更能抓住时序回归）。 */
	private static int ticksToPull(State from) {
		State s = from;
		int n = 0;
		while (s.stage() != Stage.PULLED && n < 500) {
			s = reach(s);
			n++;
		}
		return n;
	}

	/** 闸门全开时的一次 denyReason 调用，只把要单独试的那一项改掉。 */
	private static String deny(boolean phase, boolean raft, boolean sameTeam, boolean notSelf,
			boolean inWater, double gap, double distance) {
		return ReachRule.denyReason(phase, raft, sameTeam, notSelf, inWater, gap, distance);
	}

	private static final double OK_GAP = 1.0;
	private static final double OK_DIST = 1.5;

	@Test
	void oneSecondOfHoldingPullsThemIn() {
		State first = reach(State.off());
		assertEquals(Stage.REACH, first.stage(), "第一拍是配对人、开始用力，不当场把人搬上筏");
		assertEquals(0, first.tick(), "配对那一拍不该算进 20 拍里");
		State s = first;
		int ticks = 0;
		while (s.stage() != Stage.PULLED && ticks < 200) {
			s = reach(s);
			ticks++;
		}
		assertEquals(Stage.PULLED, s.stage(), "按满 1 秒必须真的把人拉上来，否则这条路是死的");
		assertEquals(ReachRule.REACH_TICKS, ticks, "配对之后 20 拍（1 秒）就该拉完，实测 " + ticks);
		assertEquals(ReachRule.REACH_TICKS, s.tick(), "状态里记的 tick 必须与真实推进次数一致");
		assertEquals(ReachRule.REACH_TICKS + 1, ticksToPull(State.off()),
				"冷启动是 21 拍：第一拍只负责配对人");
		assertNull(deny(true, true, true, true, true, OK_GAP, OK_DIST), "全开的输入不该有拒绝原因");
	}

	@Test
	void teammateRescueIsExactlyTwiceAsFastAsSelfRescue() {
		// 02 §8：自救 2 秒、队友伸手 1 秒。写成"两者之比"而不是两个孤立的数，
		// 是因为以后调其中一边时必须看见另一边也跟着动，不然"队友救援更快"这条动机就悄悄没了
		assertEquals(2.0, (double) ClingRule.CLIMB_TICKS / ReachRule.REACH_TICKS, EPS,
				"伸手必须是自救的一半时间，否则队友救援没有意义");
		assertEquals(20, ReachRule.REACH_TICKS, "02 §8 的\"贴边 1s\"就是 20 tick");
	}

	@Test
	void pulledIsASingleTickEventThenReleases() {
		State s = State.off();
		for (int i = 0; i < 200 && s.stage() != Stage.PULLED; i++) {
			s = reach(s);
		}
		assertEquals(Stage.PULLED, s.stage());
		State after = reach(s);
		assertEquals(Stage.IDLE, after.stage(), "拉完必须交回普通状态，否则同一个人会被一直搬");
		assertEquals(0, after.tick(), "一次性事件之后计数要归零");
	}

	@Test
	void damageOrReleaseForgetsAllProgress() {
		State s = State.off();
		for (int i = 0; i < ReachRule.REACH_TICKS - 5; i++) {
			s = reach(s);
		}
		assertTrue(s.reaching(), "前置：确实已经拉到一半：" + s);
		State hit = ReachRule.next(s, true, true, true);
		assertEquals(Stage.IDLE, hit.stage(), "救援方挨一刀，手一抖人就没了");
		assertEquals(0, hit.tick(), "打断之后进度必须清零，留一半就是白送");
		assertEquals(ticksToPull(State.off()), ticksToPull(hit), "重来一次必须和完全冷启动一样久");

		State released = ReachRule.next(s, false, true, false);
		assertEquals(Stage.IDLE, released.stage(), "松手就是放弃这次救援");
		assertEquals(0, released.tick(), "松手之后同样不许留进度");
	}

	@Test
	void losingTheVictimMidPullRestartsFromZero() {
		// 被救者游开、上岸、或筏子把人甩出伸手范围，都属于"这一趟作废"，不是"暂停"
		State s = State.off();
		for (int i = 0; i < 10; i++) {
			s = reach(s);
		}
		assertEquals(Stage.IDLE, ReachRule.next(s, true, false, false).stage(), "目标不再合格就该作废");
		assertTrue(reach(s).reaching(), "同样的输入什么都不改时必须还在拉（上面那条不是恒真）");
	}

	@Test
	void handReachBoundaryIsInclusiveThenExclusive() {
		assertNull(deny(true, true, true, true, true, OK_GAP, ReachRule.HAND_REACH),
				"正好在伸手上限那一格必须够得着");
		String tooFar = deny(true, true, true, true, true, OK_GAP, ReachRule.HAND_REACH + 0.001);
		assertNotNull(tooFar, "再远一丝就得拒");
		assertTrue(tooFar.contains("伸手"), "这一条的原因要说是伸手距离不够，别和'被救者离壳太远'混成一句："
				+ tooFar);
	}

	@Test
	void eachGateAloneRefusesTheRescue() {
		assertNotNull(deny(false, true, true, true, true, OK_GAP, OK_DIST), "非 PLAY 阶段不许救援");
		assertNotNull(deny(true, false, true, true, true, OK_GAP, OK_DIST), "筏沉了不许救援");
		assertNotNull(deny(true, true, false, true, true, OK_GAP, OK_DIST), "敌队不许捞");
		assertNotNull(deny(true, true, true, false, true, OK_GAP, OK_DIST), "自己不算队友");
		assertNotNull(deny(true, true, true, true, false, OK_GAP, OK_DIST), "人不在水里没什么可救");
		assertNotNull(deny(true, true, true, true, true, ClingRule.HULL_REACH + 0.5, OK_DIST),
				"被救者连自家船壳都够不着时，伸手救不了：先开筏过去");
		assertNotNull(deny(true, true, true, true, true, OK_GAP, ReachRule.HAND_REACH + 0.5), "超出伸手上限");
		// 六道门各自的原因都必须不一样，否则排查时看不出是哪一条挡住了
		String[] reasons = {
				deny(false, true, true, true, true, OK_GAP, OK_DIST),
				deny(true, false, true, true, true, OK_GAP, OK_DIST),
				deny(true, true, false, true, true, OK_GAP, OK_DIST),
				deny(true, true, true, false, true, OK_GAP, OK_DIST),
				deny(true, true, true, true, false, OK_GAP, OK_DIST),
				deny(true, true, true, true, true, ClingRule.HULL_REACH + 0.5, OK_DIST),
				deny(true, true, true, true, true, OK_GAP, ReachRule.HAND_REACH + 0.5)};
		for (int i = 0; i < reasons.length; i++) {
			for (int j = i + 1; j < reasons.length; j++) {
				assertNotEquals(reasons[i], reasons[j], "第 " + i + " 与第 " + j + " 条门必须给出可区分的原因");
			}
		}
	}

	@Test
	void liftedVictimLeavesTheWaterAndDriftsOutOfReachButStaysPullable() {
		// 2026-11-06 实测形状：拉回计数不涨、伸手作废一路涨、被救者 Y 只爬到 62.79（甲板面 64.02）。
		// 两条门都是被"拉回这个动作本身"否定的：往上抬必然离开水面；
		// 而往甲板方向抬，会离泡在筏外的救援方越来越远（那一趟距离从 2.30 涨到 3.56，第 13 拍自爆）。
		// 所以"在水里"与"伸手上限"只判起拉那一拍，一旦抓住，1 秒之内不许因为这两条松手。
		assertNotNull(deny(true, true, true, true, false, OK_GAP, OK_DIST), "起拉那一拍不在水里就该拒");
		assertNotNull(deny(true, true, true, true, true, OK_GAP, ReachRule.HAND_REACH + 0.5),
				"起拉那一拍超出伸手上限就该拒");
		assertNull(ReachRule.denyOngoing(true, true, true, true, OK_GAP),
				"抬起来以后不在水里、离救援方更远了，都不许把这一趟判废，否则 1 秒永远走不完");
		// 但只放开这两条，其余闸门一条都不许跟着松
		assertNotNull(ReachRule.denyOngoing(true, true, true, true, ClingRule.HULL_REACH + 0.5),
				"被救者连自家船壳都够不着了（被浪推走），进行中也要立刻作废");
		assertNotNull(ReachRule.denyOngoing(false, true, true, true, OK_GAP),
				"阶段离开 PLAY 必须当场结束，不许把人都拖进下一局");
		assertNotNull(ReachRule.denyOngoing(true, false, true, true, OK_GAP),
				"筏沉了就没有落点，进行中也必须停");
		assertNotNull(ReachRule.denyOngoing(true, true, false, true, OK_GAP),
				"中途换队（重开或转队）必须作废");
	}

	@Test
	void refusingToPullYourselfIsNamedSeparately() {
		String self = deny(true, true, true, false, true, OK_GAP, OK_DIST);
		assertNotNull(self);
		assertTrue(self.contains("自救"), "自己拉自己要被指回扒边那条 2 秒的路，原因里要说清楚：" + self);
	}

	@Test
	void handDistanceIsHorizontalAndSymmetric() {
		assertEquals(0.0, ReachRule.handDistance(1.0, -2.0, 1.0, -2.0), EPS, "同一点距离为 0");
		assertEquals(5.0, ReachRule.handDistance(0.0, 0.0, 3.0, 4.0), EPS, "水平面按欧氏距离算");
		assertEquals(ReachRule.handDistance(0, 0, 3, 4), ReachRule.handDistance(3, 4, 0, 0), EPS,
				"救援方与被救者换位置，结论不许变");
		// 垂直分量不计：筏子一起伏，如果把 Y 算进去，同一趟伸手会忽成忽败
		double dx = ReachRule.handDistance(0.0, 0.0, 1.25, 0.0);
		assertTrue(dx < ReachRule.HAND_REACH, "贴着壳边的救援方与扒壳的人应当轻松够得着，实测 " + dx);
		assertTrue(ReachRule.handDistance(0.0, 0.0, 12.0, 0.0) > ReachRule.HAND_REACH,
				"02 §8 的 12 格是抛绳索的射程，伸手不该覆盖到那一档");
	}

	@Test
	void reachTicksMatchTheInterpolator() {
		// 拉回与自救共用 ClingRule.climbLocal，只是时长不同：这里钉住"伸手那一档"确实收敛到落点
		double[] base = {1.0, -1.3, -3.2};
		double[] target = ClingRule.landingLocal(base[0], base[2], 2.0);
		assertArrayEqualsTarget(target, ClingRule.climbLocal(base, target, ReachRule.REACH_TICKS,
				ReachRule.REACH_TICKS));
		double[] prev = ClingRule.climbLocal(base, target, 0, ReachRule.REACH_TICKS);
		for (int tick = 1; tick <= ReachRule.REACH_TICKS; tick++) {
			double[] now = ClingRule.climbLocal(base, target, tick, ReachRule.REACH_TICKS);
			assertTrue(Math.abs(target[1] - now[1]) <= Math.abs(target[1] - prev[1]) + 1e-9,
					"1 秒内必须一路靠近甲板面，tick=" + tick);
			prev = now;
		}
	}

	private static void assertArrayEqualsTarget(double[] expected, double[] actual) {
		for (int i = 0; i < 3; i++) {
			assertEquals(expected[i], actual[i], EPS, "终点分量 " + i + " 必须正好是夹进甲板内的落点");
		}
	}
}
