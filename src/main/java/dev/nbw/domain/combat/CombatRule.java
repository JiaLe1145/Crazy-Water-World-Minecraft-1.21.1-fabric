package dev.nbw.domain.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * PVP 近战武器的纯规则（09 §2.1 + §5.1）。
 *
 * <p>零 Minecraft import：攻速闸门、持有上限、数值查表都可以脱离服务端被测。
 * 特殊效果（流血/骨折/连击移速/射程/风暴减益）本轮不做，属 M3/M4（见 09 §2.1 的落地范围），
 * 对应字段仍随表下发但不进判定，避免把"漏做"和"没这回事"混在一起。
 */
public final class CombatRule {

	/** 武器类别。本轮只落地 MELEE；RANGED（§2.2）/ TOOL（§2.3）随 M3/M4 补。 */
	public enum Kind { MELEE, RANGED, TOOL }

	/** 每人同时最多持有的近战武器件数（09 §5.1：1 近战 + 1 远程 + 1 工具，本轮只有近战）。 */
	public static final int MAX_MELEE_HELD = 1;

	/** 武器 id 白名单（与 cfg 的文件名白名单同一套：小写字母数字下划线）。 */
	private static final String ID_PATTERN = "[a-z0-9_]{1,32}";

	/**
	 * 一件武器的数值规格。伤害单位是 HP（♥×2），冷却单位是 tick（秒×20），
	 * 击退是无量纲强度（适配层换算成 {@code takeKnockback} 的 strength），
	 * coreMultiplier 是"对筏心破拆倍率"（本轮只随表下发，不接判定——筏心 HP 系统属 M3/M4）。
	 */
	public record WeaponSpec(String id, Kind kind, double damage, int cooldownTicks,
			double knockback, double coreMultiplier) {

		public boolean isMelee() {
			return kind == Kind.MELEE;
		}

		/** 字段级校验：返回空列表表示可用。配置侧拒绝加载而非猜测纠正。 */
		public List<String> errors() {
			List<String> out = new ArrayList<>();
			if (id == null || !id.matches(ID_PATTERN)) {
				out.add("武器 id 不合法：" + id + "（只允许 [a-z0-9_]{1,32}）");
			}
			if (kind == null) {
				out.add("武器 " + id + " 缺少 kind");
			}
			if (!(damage > 0)) {
				out.add("武器 " + id + " 的 damage 必须 > 0，当前 " + damage);
			}
			if (cooldownTicks <= 0) {
				out.add("武器 " + id + " 的 cooldownTicks 必须 > 0，当前 " + cooldownTicks);
			}
			if (knockback < 0) {
				out.add("武器 " + id + " 的 knockback 不得为负，当前 " + knockback);
			}
			if (coreMultiplier < 1.0) {
				out.add("武器 " + id + " 的 coreMultiplier 必须 >= 1.0，当前 " + coreMultiplier);
			}
			return out;
		}
	}

	private CombatRule() {
	}

	/**
	 * 持有上限门：给一名已持有 {@code meleeHeld} 件近战的玩家再塞一件 {@code adding} 类武器是否越限。
	 *
	 * @return 拒绝原因；允许则返回 null
	 */
	public static String holdViolation(int meleeHeld, Kind adding) {
		if (adding == Kind.MELEE && meleeHeld >= MAX_MELEE_HELD) {
			return "已持有近战武器（每人限 " + MAX_MELEE_HELD + " 件近战，09 §5.1）";
		}
		return null;
	}

	/**
	 * 攻速闸门：距上次有效攻击 {@code lastAttackTick} 不满 {@code cooldownTicks} 就是超频。
	 * {@code lastAttackTick < 0} 表示"从没打过"，永远放行。
	 *
	 * @return 拒绝原因；允许则返回 null
	 */
	public static String cooldownViolation(long lastAttackTick, long nowTick, int cooldownTicks) {
		if (lastAttackTick >= 0 && nowTick - lastAttackTick < cooldownTicks) {
			return "攻速超频（冷却 " + cooldownTicks + " tick，距上次有效攻击仅 "
					+ (nowTick - lastAttackTick) + " tick）";
		}
		return null;
	}
}
