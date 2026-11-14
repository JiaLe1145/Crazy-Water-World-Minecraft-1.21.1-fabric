package dev.nbw.domain.raft;

/**
 * 幸存者舵手（01 §7 A6 / 02 §6）的航行加成。
 *
 * <p><b>口径来源：</b>02 §6 原表写的是离散航行时代的"单次位移 +1 格、冷却 −1 s"；
 * M2 之后航行已换成连续目标速度模型（{@link SpeedGovernor}），离散档位不再存在。
 * 2026-11-14 用户批准折算为：<b>目标速度与转向角冲量各 ×1.15</b>。
 * 这是当前模型下同时覆盖"航速"和"转向响应"两条描述的最小改动——
 * 只乘目标值，不改任何既有档位常量，也不动船体上限 {@link SpeedGovernor#MAX_TRAVEL_SPEED}。
 *
 * <p>纯函数：适配层（{@code raft/RemoteService}、{@code raft/WindService}）自己查
 * "这队有没有舵手"，再把倍率乘到遥控速度、帆速、转向冲量三处。
 */
public final class HelmsmanRule {

	/** 有舵手时目标速度与转向冲量的倍率（用户 2026-11-14 批准，+15%）。 */
	public static final double BONUS_MULTIPLIER = 1.15;

	private HelmsmanRule() {
	}

	/**
	 * 舵手倍率。
	 *
	 * <p>没有舵手必须是 1.0：这个值直接乘在每队的目标速度上，
	 * 写成默认加成会让所有队都凭空变快，竞技公平性当场失效。
	 */
	public static double multiplier(boolean hasHelmsman) {
		return hasHelmsman ? BONUS_MULTIPLIER : 1.0;
	}

	/** 遥控 / 风帆的目标位移速度（格/tick）。 */
	public static double speed(double baseSpeed, boolean hasHelmsman) {
		return baseSpeed * multiplier(hasHelmsman);
	}

	/** 转向角冲量。与 {@link #speed} 同倍率，对应 02 §6 的"缩短桨冷却"里的响应部分。 */
	public static double turnImpulse(double baseImpulse, boolean hasHelmsman) {
		return baseImpulse * multiplier(hasHelmsman);
	}
}
