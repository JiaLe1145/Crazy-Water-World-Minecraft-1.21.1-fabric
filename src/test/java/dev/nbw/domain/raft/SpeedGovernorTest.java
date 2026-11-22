package dev.nbw.domain.raft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航行控制器与档位口径（#39）。
 *
 * <p>这一组测试守的是<b>数值之间的关系</b>，不是数值本身：桨:帆 = 2:3 来自 02 §6，
 * 上限只做保险丝来自"不能压平玩法"。谁改了档位而没说明理由，这里会直接红。
 */
class SpeedGovernorTest {

	private static final double EPS = 1e-9;

	@Test
	void oarAndSailUnitsKeepTheApprovedTwoToThreeRatio() {
		double rower = SpeedGovernor.rowTarget(1);
		double oneSailInTypicalWind = SpeedGovernor.sailTarget(1, 2.0);
		// 2026-11-22 用户反馈"速度过快、会翻船"，两档数值一起下调（原 0.0333 / 0.05）；
		// 但 2:3 的比例关系是 02 §6 的东西，下调后必须仍然精确成立
		assertEquals(0.022, rower, 1e-6, "单人单桨 = 0.022 格/tick（0.44 格/秒）");
		assertEquals(0.033, oneSailInTypicalWind, 1e-6, "1 帆在风强 2.0 = 0.033 格/tick（0.66 格/秒）");
		double ratio = rower / oneSailInTypicalWind;
		assertTrue(Math.abs(ratio - 2.0 / 3.0) < 0.01,
				"02 §6 的桨:帆 = 2:3 必须原样保留，实测比值 " + ratio);
	}

	@Test
	void capIsAFuseThatFullRigCanActuallyReach() {
		// 保险丝要成立，两件事必须同时满足：正常配置都还在限内（否则帆数/人数不再影响航速），
		// 而配满的那一档真能被夹住（否则上限是死代码，"桨风并用不越界"没有读数支撑）。
		// 2026-11-22 下调后的交叉点：每帆常态风请求 0.033 ⇒ 6 帆 0.198 在限内、
		// 7 帆 0.231 正好越过上限 0.22；每人 0.022 ⇒ 满编 5 人 0.11 远在线内。
		assertTrue(SpeedGovernor.sailTarget(6, 2.0) < SpeedGovernor.MAX_TRAVEL_SPEED,
				"6 帆就贴上限，帆数从第 6 块起不再影响航速：" + SpeedGovernor.sailTarget(6, 2.0));
		assertTrue(SpeedGovernor.rowTarget(5) < SpeedGovernor.MAX_TRAVEL_SPEED,
				"满编 5 人划桨就贴上限：" + SpeedGovernor.rowTarget(5));
		assertEquals(SpeedGovernor.MAX_TRAVEL_SPEED, SpeedGovernor.sailTarget(7, 2.0), EPS,
				"7 帆常态风必须正好夹在上限（0.033×7 = 0.231 > 0.22）");
		assertEquals(SpeedGovernor.MAX_TRAVEL_SPEED, SpeedGovernor.sailTarget(7, 4.0), EPS,
				"7 帆 + 风暴的请求 0.462 必须被夹住，越界的请求不能被放行");
	}

	@Test
	void sailTargetScalesLinearlyWithSailsAndStrength() {
		assertEquals(0.066, SpeedGovernor.sailTarget(2, 2.0), EPS, "2 帆 @风强2 应是 1 帆的两倍");
		assertEquals(0.066, SpeedGovernor.sailTarget(1, 4.0), EPS, "1 帆 @风强4 应等于 2 帆 @风强2");
		assertEquals(0.0, SpeedGovernor.sailTarget(3, 0.0), EPS, "静风时帆不给出任何目标速度");
	}

	@Test
	void dvCapScalesWithTheTargetAndStaysInASafeBand() {
		// 2026-11-22：限幅从"一个绝对数"改成"目标速度的倍数"。
		// 理由（实测踩过两次）：绝对限幅只要低于档位目标，所有档位就都被推成同一个速度
		// （遥控/划桨/帆 全变 6.6 格/秒）；只要高于档位目标太多，又是一记猛踢（倾斜 12.9°）。
		// 相对限幅两头都占得住：低速档按低速推、高速档按高速推，加速都是 3~4 拍的小坡。
		assertEquals(1.8, SpeedGovernor.DV_CAP_RATIO, 1e-9, "倍数关系是这套配平的根");
		assertEquals(0.11 * 1.8, SpeedGovernor.dvCap(0.11), 1e-9, "遥控档的每 tick 增量上限");
		assertEquals(0.22 * 1.8, SpeedGovernor.dvCap(0.22), 1e-9, "满帆档的每 tick 增量上限");
		// 稳态 = dvCap / 阻尼率(k≈2) ≈ 0.9×目标：既追得上，又不会明显过冲
		assertTrue(SpeedGovernor.dvCap(0.22) / 2.0 < 0.22,
				"稳态必须低于目标，否则控制器会一推一停地抖");
		assertTrue(SpeedGovernor.dvCap(0.22) / 2.0 > 0.22 * 0.75,
				"稳态也不能离目标太远，否则帆多/人多的档位形同虚设");
		// 绝对上限只在"目标被算成天文数字"时兜底，正常档位用不到它
		assertTrue(SpeedGovernor.MAX_DV_PER_TICK > SpeedGovernor.dvCap(SpeedGovernor.MAX_TRAVEL_SPEED),
				"绝对上限不该在正常档位上生效：" + SpeedGovernor.MAX_DV_PER_TICK);
		assertEquals(0.0, SpeedGovernor.dvCap(0.0), 1e-12, "没有目标就没有增量额度");
	}

	@Test
	void impulseIsProportionalWhileTheErrorIsSmall() {
		// 纯比例只发生在"误差 < 18% 目标"的窄带里（因为限幅 = 1.8×目标、增益 = 10）。
		// 这不是缺陷而是刻意的：大误差时一直按限幅推（追得快），接近目标才切成比例（不过冲）。
		// 稳态误差正好是 10% 目标，落在这条带子里 —— 这正是我们要的工作点。
		double mass = 25.5;
		double target = SpeedGovernor.MAX_TRAVEL_SPEED;
		assertEquals(mass * (target - 0.20) * SpeedGovernor.GAIN,
				SpeedGovernor.impulse(mass, 0.20, target), 1e-9, "误差 9% 时按 gain 比例纠正");
		assertEquals(mass * (target - 0.19) * SpeedGovernor.GAIN,
				SpeedGovernor.impulse(mass, 0.19, target), 1e-9, "实测要按沿方向的值扣掉");
		// 突破 18% 就进入限幅区：再大的误差也只给到 1.8×目标
		assertEquals(mass * SpeedGovernor.dvCap(target),
				SpeedGovernor.impulse(mass, 0.0, target), 1e-12, "大误差一律撞在限幅上");
	}

	@Test
	void rateLimitCapsTheStartupKick() {
		// 启动那一 tick 误差等于整个目标值。没有限幅的话纯比例会给出巨大的冲量 ——
		// 实测后果是筏子 8.4 秒飞出 1475 格（GAIN=20 那次，见 #39），所以限幅必须能失败。
		double mass = 25.5;
		double target = SpeedGovernor.MAX_TRAVEL_SPEED;
		double kick = SpeedGovernor.impulse(mass, 0.0, target);
		double cap = Math.min(SpeedGovernor.MAX_DV_PER_TICK, target * SpeedGovernor.DV_CAP_RATIO);
		assertEquals(mass * cap, kick, 1e-12, "最大误差那一 tick 必须被限幅接住");
		assertTrue(kick < mass * target * SpeedGovernor.GAIN,
				"限幅必须真的起作用，不能写个用不上的上限");
		// 换算成"每 tick 最多抬高的速度增量"，那才是给读者的硬约束
		assertEquals(cap, kick / mass, 1e-12,
				"冲量除以质量就是这一 tick 的速度增量，任何误差下都不许超过限幅");
	}

	@Test
	void controllerNeverBrakesReverse() {
		double target = SpeedGovernor.MAX_TRAVEL_SPEED;
		assertEquals(0.0, SpeedGovernor.impulse(25.5, target, target), EPS, "正好追平目标时一句力都不施");
		assertEquals(0.0, SpeedGovernor.impulse(25.5, target + 0.01, target), EPS,
				"已经超过目标时必须输出 0，而不是负冲量去倒车——减速是玩法的事");
		// 在比例带里：被水顶着倒退（实测值更小）⇒ 误差更大 ⇒ 冲量更大
		assertTrue(SpeedGovernor.impulse(25.5, 0.19, target)
						> SpeedGovernor.impulse(25.5, 0.20, target),
				"被水顶着倒退时误差要变大，冲量必须比「差一点就到目标」时更大");
	}

	@Test
	void impulseRejectsUnusableInputsInsteadOfEmittingNan() {
		assertEquals(0.0, SpeedGovernor.impulse(Double.NaN, 0.0, 0.1), EPS, "质量读不出来绝不能施力");
		assertEquals(0.0, SpeedGovernor.impulse(0.0, 0.0, 0.1), EPS, "质量 0 说明筏不在场");
		assertEquals(0.0, SpeedGovernor.impulse(-25.5, 0.0, 0.1), EPS, "负质量是脏数据");
		assertEquals(0.0, SpeedGovernor.impulse(25.5, 0.0, Double.NaN), EPS, "NaN 目标必须归零");
		assertEquals(0.0, SpeedGovernor.impulse(25.5, 0.0, 0.0), EPS, "目标 0 就是不要动力");
	}

	@Test
	void actualAlongKeepsTheSignAgainstReverseDrift() {
		assertEquals(-0.05, SpeedGovernor.actualAlong(-0.05, 0.0, 1.0, 0.0), EPS,
				"往回漂必须读成负数，否则控制器会以为已经在前进而不再出力");
		assertEquals(0.03, SpeedGovernor.actualAlong(0.0, 0.03, 0.0, 1.0), EPS, "取的是沿请求方向的分量");
		assertEquals(0.0, SpeedGovernor.actualAlong(0.0, 0.03, 1.0, 0.0), EPS,
				"纯横向的漂移一格都不该算进前进速度");
		assertEquals(0.04, SpeedGovernor.actualAlong(0.04, 0.03, 1.0, 0.0), EPS,
				"混合漂移只取沿请求方向的那一分量（0.04），横分量 0.03 不算");
		assertEquals(0.0, SpeedGovernor.actualAlong(Double.NaN, 0.01, 1.0, 0.0), EPS,
				"位姿读不出来时按静止处理，不能把 NaN 传进冲量");
	}

	@Test
	void clampSpeedTreatsGarbageAsNoDemand() {
		assertEquals(0.0, SpeedGovernor.clampSpeed(Double.NaN), EPS);
		assertEquals(0.0, SpeedGovernor.clampSpeed(-1.0), EPS);
		assertEquals(0.0, SpeedGovernor.clampSpeed(0.0), EPS);
		assertEquals(0.1, SpeedGovernor.clampSpeed(0.1), EPS);
		assertEquals(SpeedGovernor.MAX_TRAVEL_SPEED, SpeedGovernor.clampSpeed(99.0), EPS);
	}

	@Test
	void combineAddsTargetVectorsAndCancelsOpposites() {
		// combine 返回的是目标<b>速度矢量</b>（已经乘过速率）加速率本身，调用方靠 dir = v/speed 施力
		double[] same = SpeedGovernor.combine(0.0, 1.0, 0.02, 0.0, 1.0, 0.02);
		assertEquals(0.04, same[2], EPS, "两股同向的目标速度要相加");
		assertEquals(0.04, same[1], EPS, "矢量必须已经带上速率，调用方就不用再乘");
		assertEquals(0.0, same[0], EPS, "两股南向的目标不该有东西分量");
		double[] opposed = SpeedGovernor.combine(0.0, 1.0, 0.02, 0.0, -1.0, 0.02);
		assertEquals(0.0, opposed[2], EPS, "方向相反且等量必须得到「零请求」，让调用方跳过施力");
		assertEquals(0.0, opposed[0], EPS);
		double[] capped = SpeedGovernor.combine(0.0, 1.0, 0.2, 0.0, 1.0, 0.2);
		assertEquals(SpeedGovernor.MAX_TRAVEL_SPEED, capped[2], EPS, "合请求必须被上限夹住");
		assertEquals(SpeedGovernor.MAX_TRAVEL_SPEED, capped[1], EPS, "夹住之后矢量也只该有 0.25");
		double mag = Math.hypot(capped[0], capped[1]) / capped[2];
		assertEquals(1.0, mag, EPS, "矢量除以速率必须还是单位方向，否则施力方向会歪");
	}
}
