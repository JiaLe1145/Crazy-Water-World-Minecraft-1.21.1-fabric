package dev.nbw.domain.survivor;

/**
 * 幸存者工匠（01 §7 A6）的修筏加成。
 *
 * <p><b>本轮落地范围：</b>队伍里有工匠时，A2-v2 残骸修复引导
 * （{@link dev.nbw.domain.raft.ChannelRule}）从 8 秒（160 tick）缩短到 6 秒（120 tick）。
 * 2026-11-14 用户批准。
 *
 * <p><b>明确没做：</b>01 §7 写的"建造加速"和"重置沉筏倒计时"。
 * <ul>
 *   <li>建造在本项目里是<b>瞬时事件</b>（右键判定通过就落方块），没有冷却或引导时长可以缩短；
 *       要"建造加速"必须先新造一个建造冷却，那是新机制，不能借工匠之名偷偷加。</li>
 *   <li>"重置沉筏倒计时"属于已删除的 A3 建材重修；现在只有 A2-v2 残骸修复
 *       （成功即由 {@code RaftSinking.cancelByCoreRestore} 解除倒计时）。</li>
 * </ul>
 *
 * <p>纯函数，不依赖 Minecraft；适配层自己查"这队有没有工匠"。
 */
public final class CraftsmanRule {

	/** 有工匠时残骸修复引导时长的倍率：160 → 120 tick（2026-11-14 批准，8s → 6s）。 */
	public static final double REPAIR_TIME_MULTIPLIER = 0.75;

	private CraftsmanRule() {
	}

	/**
	 * 本次残骸修复引导需要多少 tick。
	 *
	 * @param baseTicks    无加成时的引导时长（{@code ChannelRule.CHANNEL_TICKS}）
	 * @param hasCraftsman 本队是否有工匠
	 * @return 有工匠 → 向上取整的 75%，否则原样返回
	 */
	public static int repairChannelTicks(int baseTicks, boolean hasCraftsman) {
		if (!hasCraftsman || baseTicks <= 0) {
			return baseTicks;
		}
		return (int) Math.ceil(baseTicks * REPAIR_TIME_MULTIPLIER);
	}
}
