package dev.nbw.domain.arena;

/**
 * 区块预热的背压判定（纯函数，零 Minecraft 依赖）。
 *
 * <p><b>为什么不用"每 tick 固定几个"。</b>实测把节流从 2 个/tick 收到 1 个/tick，
 * 首批 81 个区块仍然攒出一次 6137ms / 122 tick 的落后 —— 因为总量没变，
 *  vanilla 的区块加载成本不是"每 tick 几个"能线性控制的。
 *
 * <p>改用背压：<b>服务端已经跑得慢了就先停手</b>。信号取自 END_SERVER_TICK 两次回调之间的
 * 真实墙钟间隔（不依赖任何 vanilla 内部字段，1.21.1 的 yarn 里没有 getLastTickTime），
 * 间隔超过预算就说明积压在长，这一 tick 不再添新活。
 */
public final class ChunkPacing {

	/** 正常一 tick 是 50ms；超过这个值就认为服务端在补账，先不加载新区块。 */
	public static final long SLOW_TICK_MS = 60;

	private ChunkPacing() {
	}

	/**
	 * @param elapsedMs     距上次 tick 回调过了多少毫秒；负值（时钟回拨）一律当作慢，宁可停手
	 * @param slowThreshold 判"慢"的阈值，见 {@link #SLOW_TICK_MS}
	 */
	public static boolean shouldLoadThisTick(long elapsedMs, long slowThreshold) {
		if (slowThreshold <= 0) {
			return true;   // 阈值设成 0 或负数 = 关闭背压，而不是"永远不加载"
		}
		return elapsedMs >= 0 && elapsedMs <= slowThreshold;
	}

}
