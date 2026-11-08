package dev.nbw.domain.rescue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 抛绳索/钩竿纯规则（M2 尾巴）。纯函数，不需要起服。 */
class LineRuleTest {

	@Test
	void ropeRequiresTeammateInWaterInRange() {
		// 全过：对局中 + 队友 + 在水里 + 不是自己 + 12 格内
		assertNull(LineRule.ropeDeny(true, true, true, true, 11.0));
		assertNull(LineRule.ropeDeny(true, true, true, true, 12.0), "贴射程上沿 12.0 应放行");
	}

	@Test
	void ropeRejectsEachGateSeparately() {
		assertNotNull(LineRule.ropeDeny(false, true, true, true, 5.0), "非对局必须拒");
		assertNotNull(LineRule.ropeDeny(true, true, true, false, 5.0), "抛给自己必须拒");
		assertNotNull(LineRule.ropeDeny(true, false, true, true, 5.0), "抛给敌人必须拒");
		assertNotNull(LineRule.ropeDeny(true, true, false, true, 5.0), "目标不在水里必须拒");
		assertNotNull(LineRule.ropeDeny(true, true, true, true, 12.5), "超射程必须拒");
	}

	@Test
	void grapnelRequiresEnemyInRange() {
		assertNull(LineRule.grapnelDeny(true, true, true, 15.0));
		assertNull(LineRule.grapnelDeny(true, true, true, 16.0), "贴射程上沿 16.0 应放行");
	}

	@Test
	void grapnelRejectsEachGateSeparately() {
		assertNotNull(LineRule.grapnelDeny(false, true, true, 5.0), "非对局必须拒");
		assertNotNull(LineRule.grapnelDeny(true, true, false, 5.0), "钩自己必须拒");
		assertNotNull(LineRule.grapnelDeny(true, false, true, 5.0), "钩队友必须拒");
		assertNotNull(LineRule.grapnelDeny(true, true, true, 16.5), "超射程必须拒");
	}

	@Test
	void rangesMatchDesign() {
		assertEquals(12.0, LineRule.ROPE_RANGE);
		assertEquals(16.0, LineRule.GRAPNEL_RANGE);
		assertEquals(2.0, LineRule.GRAPNEL_PULL_GAP);
	}
}
