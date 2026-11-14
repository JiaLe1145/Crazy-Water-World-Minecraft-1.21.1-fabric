package dev.nbw.domain.construct;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 扩建甲板成本（02 §5：4 木 + 1 布；"布"取线）。
 *
 * <p>把"放下的那块算不算 1 木"写错，或把线写成 0，这组用例会立刻红。
 */
class ExpansionRuleTest {

	@Test
	void plankDeckCellOnlyCostsThreeExtraPlanks() {
		assertEquals(4, ExpansionRule.PLANKS_PER_CELL, "02 §5 的 4 木");
		assertEquals(3, ExpansionRule.extraPlanks(true),
				"放下的木板本身算 1 块，额外只扣 3 块（否则把玩家手里的最后一块也扣走）");
	}

	@Test
	void nonPlankDeckCellStillCostsFourPlanks() {
		assertEquals(4, ExpansionRule.extraPlanks(false),
				"放下的不是木板时，4 木必须全额从背包扣");
	}

	@Test
	void clothIsOneStringPerCell() {
		assertEquals(1, ExpansionRule.STRING_PER_CELL);
		assertEquals(1, ExpansionRule.stringCost());
	}
}
