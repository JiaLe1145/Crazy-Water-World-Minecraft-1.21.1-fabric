package dev.nbw.domain.survivor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工匠修筏加成（01 §7 A6，2026-11-14 用户批准：A2-v2 残骸修复 8s → 6s）。
 *
 * <p>把倍率改成 1.0、把 120 写成 160，或把"无工匠"分支也乘上倍率，这组用例都会红。
 */
class CraftsmanRuleTest {

	@Test
	void craftsmanShortensRepairFromEightSecondsToSix() {
		assertEquals(160, CraftsmanRule.repairChannelTicks(160, false),
				"无工匠必须保持 8 秒，不能默认给所有队加速");
		assertEquals(120, CraftsmanRule.repairChannelTicks(160, true),
				"有工匠：8 秒 → 6 秒（120 tick）");
	}

	@Test
	void multiplierIsExactlyThreeQuarters() {
		assertEquals(0.75, CraftsmanRule.REPAIR_TIME_MULTIPLIER, 1e-12,
				"倍率是公开常量，防止实现与文档走散");
		assertEquals(75, CraftsmanRule.repairChannelTicks(100, true),
				"倍率对所有基准时长一致生效（向上取整）");
	}

	@Test
	void nonPositiveBaseIsReturnedUnchanged() {
		assertEquals(0, CraftsmanRule.repairChannelTicks(0, true),
				"0 长度不能因为乘倍率变成负数或 1");
		assertEquals(-5, CraftsmanRule.repairChannelTicks(-5, true));
	}
}
