package dev.nbw.domain.net;

/**
 * 长按输入的接受判定（纯函数，零 Minecraft 依赖）。
 *
 * <p>客户端发来的"我在按着"只有两种可信用途：<b>续期</b>与<b>解除</b>。
 * 进度、时长、成功与否一律不许从包里读——那是服务端 tick 自己的事
 * （{@code RaftCoreService.onEndTick} 数 160 tick，{@code ChannelRule} 判完成）。
 *
 * <p>距离闸门是防绕过的主面：外挂可以直接构造这个包，但它没法让自己的真实坐标靠近目标。
 */
public final class HoldPolicy {

	public enum Decision {
		/** 目标在可达范围内：续期该玩家的引导窗口 */
		KEEPALIVE,
		/** 松手：解除该玩家的引导 */
		RELEASE,
		/** 超距或距离数值非法：丢弃 */
		REJECT
	}

	/**
	 * 生存模式下的可达距离（格）。取 8 而不是原版的 4.5：筏会随浪起伏、人也不总站在方块正中，
	 * 卡在 4.5 会出现"明明看着能修却不给修"。旁观者用 {@link #reachFor} 归零。
	 */
	public static final double REACH_BLOCKS = 8.0;

	private HoldPolicy() {
	}

	/**
	 * @param distance 玩家到目标方块中心的欧氏距离（格）；NaN 或负数视为非法
	 * @param reach    本次可达上限，见 {@link #REACH_BLOCKS} 与 {@link #reachFor}
	 */
	public static Decision decide(boolean active, double distance, double reach) {
		if (!active) {
			// 松手不卡距离：人走远了也必须能把引导解掉，否则"解除"反而被距离闸门挡住
			return Decision.RELEASE;
		}
		if (!(distance >= 0.0) || distance > reach) {
			// 不用 !Double.isFinite：那会漏掉"负距离"这种上游算错的情况，这里两种都拒
			return Decision.REJECT;
		}
		return Decision.KEEPALIVE;
	}

	/** 旁观者不该驱动任何引导：上限归零比事后判游戏模式更早、更省，也更好测。 */
	public static double reachFor(boolean spectator) {
		return spectator ? 0.0 : REACH_BLOCKS;
	}
}
