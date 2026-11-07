package dev.nbw.domain.combat;

import dev.nbw.domain.combat.CombatRule.Kind;
import dev.nbw.domain.combat.CombatRule.WeaponSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PVP 近战纯规则（09 §2.1 + §5.1）。纯函数，不需要起服。 */
class CombatRuleTest {

	private static final WeaponSpec CUTLASS = new WeaponSpec("cutlass", Kind.MELEE, 9.0, 12, 1.0, 1.0);
	private static final WeaponSpec TWIN_BLADE = new WeaponSpec("twin_blade", Kind.MELEE, 7.0, 7, 0.5, 1.0);

	// ---------------------------------------------------------------- 持有上限门

	@Test
	void firstMeleeIsAlwaysAllowed() {
		// 一名玩家还没拿任何近战武器时，第一件近战永远放行。
		assertNull(CombatRule.holdViolation(0, Kind.MELEE));
	}

	@Test
	void secondMeleeIsRejected() {
		// 09 §5.1：近战上限 1 件。已经拿了 1 件再给第 2 件必须被拒，否则"六把武器轮流出"的碾压回来了。
		assertNotNull(CombatRule.holdViolation(1, Kind.MELEE));
	}

	@Test
	void holdingOneMeleeStillAllowsNonMeleeKinds() {
		// 上限是按类别的：1 近战 + 1 远程 + 1 工具。拿了近战不妨碍以后拿远程/工具（本轮还没注册）。
		// 这道门现在只判 MELEE，所以哪怕传别的 kind 也不该被近战上限卡住。
		assertNull(CombatRule.holdViolation(1, Kind.RANGED));
		assertNull(CombatRule.holdViolation(1, Kind.TOOL));
	}

	@Test
	void holdViolationReportsAReasonNotJustNull() {
		// 拒绝必须带原因串（适配层靠它回给玩家），不能只靠 null 区分——那会让"为什么被拒"无从回答。
		String reason = CombatRule.holdViolation(1, Kind.MELEE);
		assertNotNull(reason);
		assertTrue(reason.contains("近战"), "原因要点名是近战上限：" + reason);
	}

	// ---------------------------------------------------------------- 攻速闸门

	@Test
	void neverAttackedBeforeIsAllowed() {
		// lastAttackTick < 0 表示"从没打过"，第一下永远放行。
		assertNull(CombatRule.cooldownViolation(-1, 100, CUTLASS.cooldownTicks()));
	}

	@Test
	void attackExactlyAtCooldownBoundaryIsAllowed() {
		// 冷却 12 tick：距上次 12 tick（含）就该放行，13 tick 更该放行。
		assertNull(CombatRule.cooldownViolation(100, 112, CUTLASS.cooldownTicks()));
		assertNull(CombatRule.cooldownViolation(100, 113, CUTLASS.cooldownTicks()));
	}

	@Test
	void attackWithinCooldownIsRejected() {
		// 冷却 12 tick：距上次 11 tick 就是超频，必须被拒。
		assertNotNull(CombatRule.cooldownViolation(100, 111, CUTLASS.cooldownTicks()));
	}

	@Test
	void eachWeaponUsesItsOwnCooldown() {
		// 弯刀 7 tick vs 砍刀 12 tick：同一时间差，弯刀放行、砍刀拒绝，证明查表而不是抄常量。
		assertNull(CombatRule.cooldownViolation(100, 108, TWIN_BLADE.cooldownTicks()));
		assertNotNull(CombatRule.cooldownViolation(100, 108, CUTLASS.cooldownTicks()));
	}

	@Test
	void cooldownViolationReportsTheGap() {
		// 拒绝原因要把"还差多少"说出来，否则玩家看不到冷却进度、只能瞎点。
		String reason = CombatRule.cooldownViolation(100, 111, CUTLASS.cooldownTicks());
		assertNotNull(reason);
		assertTrue(reason.contains("11"), "原因要点名距上次的 tick 数：" + reason);
	}

	// ---------------------------------------------------------------- 规格校验

	@Test
	void specValidationRejectsBrokenRows() {
		// 配置侧拒绝加载坏行（不猜测纠正）；每条字段错误都能独立冒出来。
		assertTrue(CUTLASS.errors().isEmpty(), "合法行不该报错：" + CUTLASS.errors());
		assertFalse(new WeaponSpec("", Kind.MELEE, 9.0, 12, 1.0, 1.0).errors().isEmpty(), "空 id 必须报错");
		assertFalse(new WeaponSpec("x", null, 9.0, 12, 1.0, 1.0).errors().isEmpty(), "缺 kind 必须报错");
		assertFalse(new WeaponSpec("x", Kind.MELEE, 0.0, 12, 1.0, 1.0).errors().isEmpty(), "damage<=0 必须报错");
		assertFalse(new WeaponSpec("x", Kind.MELEE, 9.0, 0, 1.0, 1.0).errors().isEmpty(), "cooldown<=0 必须报错");
		assertFalse(new WeaponSpec("x", Kind.MELEE, 9.0, 12, -1.0, 1.0).errors().isEmpty(), "负击退必须报错");
		assertFalse(new WeaponSpec("x", Kind.MELEE, 9.0, 12, 1.0, 0.5).errors().isEmpty(), "coreMultiplier<1 必须报错");
	}

	@Test
	void specValidationIsFieldLevelNotAllOrNothing() {
		// 一行坏两处要报两条，而不是"第一条错了就短路"——那样配平的人要一轮一轮地改。
		List<String> errs = new WeaponSpec("x", null, 0.0, 0, -1.0, 0.5).errors();
		assertTrue(errs.size() >= 4, "多处坏要报多条，实际 " + errs.size() + " 条：" + errs);
	}

	@Test
	void kindIdentityIsStable() {
		// 近战判定不能靠字符串比较，否则"MELEE"和"melee"会变成两把不同类别的武器。
		assertTrue(CUTLASS.isMelee());
		assertFalse(new WeaponSpec("r", Kind.RANGED, 5.0, 10, 1.0, 1.0).isMelee());
	}
}
