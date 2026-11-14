package dev.nbw.domain.raft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 舵手加成（01 §7 A6 / 02 §6，2026-11-14 用户批准折算为 ×1.15）。
 *
 * <p>每条断言都钉一个能失败的方向：把 {@link HelmsmanRule#BONUS_MULTIPLIER}
 * 改成 1.0 或把倍率错接到"无舵手"分支，这组用例会立刻红。
 */
class HelmsmanRuleTest {

	@Test
	void helmsmanRaisesTargetSpeedByFifteenPercent() {
		assertEquals(1.15, HelmsmanRule.speed(1.0, true), 1e-9,
				"有舵手时目标速度必须是 ×1.15（不是 +15 格/秒，也不是任意倍率）");
		assertEquals(SpeedGovernor.REMOTE_SPEED * 1.15,
				HelmsmanRule.speed(SpeedGovernor.REMOTE_SPEED, true), 1e-9,
				"遥控目标速度按同一口径放大");
	}

	@Test
	void noHelmsmanLeavesSpeedUntouched() {
		assertEquals(SpeedGovernor.REMOTE_SPEED,
				HelmsmanRule.speed(SpeedGovernor.REMOTE_SPEED, false), 1e-12,
				"没有舵手必须原样返回——默认加成等于给所有队装隐形发动机，竞技公平性当场失效");
	}

	@Test
	void turnImpulseUsesTheSameBonus() {
		assertEquals(0.5, HelmsmanRule.turnImpulse(0.5, false), 1e-12,
				"无舵手时转向冲量不能被顺手放大");
		assertEquals(0.575, HelmsmanRule.turnImpulse(0.5, true), 1e-12,
				"转向响应同样 +15%（0.5 → 0.575）");
	}

	@Test
	void bonusIsMeaningfullyDifferentFromNoBonus() {
		// 反证：如果常量存在但没接上（或倍率写成 1.0），下面两条会红。
		assertEquals(HelmsmanRule.BONUS_MULTIPLIER, HelmsmanRule.multiplier(true),
				"有舵手时返回公开的加成常量，防止实现与文档走散");
		assertNotEquals(HelmsmanRule.multiplier(false), HelmsmanRule.multiplier(true),
				"有/无舵手必须产生不同的倍率，否则这条机制不存在");
	}
}
