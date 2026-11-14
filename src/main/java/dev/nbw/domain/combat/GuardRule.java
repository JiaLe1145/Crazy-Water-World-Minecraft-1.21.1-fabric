package dev.nbw.domain.combat;

/**
 * 幸存者卫兵的守筏射击规则（01 §7 A6 / 02 §9，2026-11-14 用户批准"文档锚点"口径）。
 *
 * <p><b>锚点怎么来的：</b>02 §9 写"卫兵战力 ≈ 0.6 名中等玩家（守筏可，进攻送死）"。
 * 现役 5 件近战（paddle / cutlass / pike / warhammer / twin_blade）的平均 DPS 约为
 * 11.2 HP/s，×0.6 ≈ 6.7；本轮取整为 <b>6.5 HP/s</b>，也就是每 20 tick 一发 6.5 HP。
 *
 * <p><b>边界（刻意的）：</b>只从<b>本队筏心</b>量 12 格（3D）内选最近的敌方玩家；
 * 不追击、不预判、不误伤同队、风暴中照常。范围外一秒都不打，对应"进攻送死"。
 *
 * <p><b>难度缩放：</b>Minecraft 会对 {@code mobAttack} 打玩家的伤害按难度缩放
 * （Easy ×0.65）。6.5 是 <b>Normal</b> 难度下的值；dev 服 easy 实测只有 4.25，
 * 探针要先切 normal 再验锚点。配平时不要把难度差当成代码少打。
 *
 * <p>纯函数：适配层（{@code round/GuardService}）负责找实体、查队伍、施加伤害。
 */
public final class GuardRule {

	/** 目标选择半径（格，3D，从本队筏心量）。 */
	public static final double RANGE = 12.0;

	/** 开火间隔（tick）。20 tick = 1 秒。 */
	public static final int INTERVAL_TICKS = 20;

	/**
	 * 每发伤害（HP）。6.5 = 0.6 × 近战平均 DPS（≈11.2）取整；
	 * 单位是 HP（20 HP = 10♥），与武器表一致。
	 */
	public static final double DAMAGE_PER_SHOT = 6.5;

	private GuardRule() {
	}

	/** 目标是否在守筏射程内（相对筏心的 3D 偏移）。 */
	public static boolean inRange(double dx, double dy, double dz) {
		return dx * dx + dy * dy + dz * dz <= RANGE * RANGE;
	}

	/** 理论持续 DPS，给文档/探针核对"0.6 名中等玩家"锚点。 */
	public static double damagePerSecond() {
		return DAMAGE_PER_SHOT * 20.0 / INTERVAL_TICKS;
	}
}
