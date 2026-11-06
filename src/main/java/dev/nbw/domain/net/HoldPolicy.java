package dev.nbw.domain.net;

/**
 * 长按输入的接受判定（纯函数，零 Minecraft 依赖）。
 *
 * <p>客户端发来的"我在按着"只有两种可信用途：<b>续期</b>与<b>解除</b>。
 * 进度、时长、成功与否一律不许从包里读——那是服务端 tick 自己的事
 * （{@code RaftCoreService.onEndTick} 数 160 tick，{@code ChannelRule} 判完成）。
 *
 * <p>距离闸门是防绕过的主面：外挂可以直接构造这个包，但它没法让自己的真实坐标靠近目标。
 * 前提是<b>这个距离必须在同一个坐标空间里量出来</b>——筏上方块交给我们的不是玩家眼前的显示坐标，
 * 而是 sub-level 的存储坐标，所以传进来的数一律由 {@link #localDistance} 这种"甲板局部帧"算法给出，
 * 不许图省事用父世界直算（那样每个筏上长按都会被判成千万格外，症状是"按什么都没反应"）。
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

	/**
	 * 在<b>甲板局部帧</b>里量"手到方块"的距离。
	 *
	 * <p><b>为什么不能在父世界里直接量。</b>Sable 把 sub-level 的方块交给交互事件的坐标是
	 * <b>存储坐标</b>（实测量级 2×10^7，plot 排在 {@code DEFAULT_ORIGIN=10000} 格之外），
	 * 而玩家的 {@code getPos()} 是父世界的显示坐标（量级几百）。两者相减恒得千万级的假距离，
	 * 于是筏上每一个长按都被判 REJECT。Sable 自己就从不裸比这个距离，它把玩家眼睛反变换进
	 * sub-level 那一帧再比（{@code mixin/interaction_distance/PlayerMixin.java:38}）。
	 *
	 * <p>两侧换算出来数值上属于<b>同一帧</b>（甲板中心为原点、且不随筏子旋转，
	 * 因为存储格是固定网格、旋转只发生在位姿那一步），所以筏子开着、歪着都不影响这个数。
	 *
	 * @param eyeLocal    玩家眼睛的甲板局部坐标（{@code SableRafts.worldToLocal} 的输出）
	 * @param targetCornerLocal 目标方块的<b>角</b>局部坐标（存储坐标减 plot 中心块），本函数自己加 0.5 取中心
	 * @return 欧氏距离；任一侧是 {@code NaN}（筏不在、读不到位姿）时返回 {@code -1}，
	 *         {@link #decide} 会把它当非法数值拒掉——宁可拒绝也不放行
	 */
	public static double localDistance(double[] eyeLocal, double[] targetCornerLocal) {
		if (!valid(eyeLocal) || !valid(targetCornerLocal)) {
			return -1.0;
		}
		double dx = eyeLocal[0] - (targetCornerLocal[0] + 0.5);
		double dy = eyeLocal[1] - (targetCornerLocal[1] + 0.5);
		double dz = eyeLocal[2] - (targetCornerLocal[2] + 0.5);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** NaN 与"数组不对"都算无效；分量取值范围不做假设（局部坐标本来就是围绕 0 的小数）。 */
	private static boolean valid(double[] v) {
		return v != null && v.length >= 3 && !Double.isNaN(v[0]) && !Double.isNaN(v[1]) && !Double.isNaN(v[2]);
	}
}
