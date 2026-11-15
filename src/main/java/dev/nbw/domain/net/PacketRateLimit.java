package dev.nbw.domain.net;

/**
 * 自定义输入包的每玩家速率限制（Gate G5 反作弊，2026-11-15）。
 *
 * <p>为什么需要：正常客户端 {@code nbw:hold} / {@code nbw:move} 约 10 包/秒，
 * 但恶意客户端可以发几千包/秒；每个包都会进服务端线程。没有上限就是一条低成本的 DoS 面。
 *
 * <p>用**固定 1 秒窗口**而不是滑动窗口：实现简单、无内存增长、读数好解释。
 * 代价是窗口边界可能瞬间多放一包，对“防洪泛”够用；不追求精确限速。
 *
 * <p>纯函数，不碰 MC；状态由调用方按玩家保存。
 */
public final class PacketRateLimit {

	/**
	 * 默认上限：40 包/秒。正常客户端 hold/move 各约 10/s，留 4 倍余量；
	 * 这个数是**安全上限**，不是玩法参数——改它不需要改任何玩法文档。
	 */
	public static final int DEFAULT_MAX_PER_SECOND = 40;

	/** 一名玩家在当前窗口内的计数。{@code windowIndex = tick / 20}。 */
	public record State(long windowIndex, int count) {
		public static final State EMPTY = new State(Long.MIN_VALUE, 0);
	}

	private PacketRateLimit() {
	}

	/**
	 * 尝试放行一个包。
	 *
	 * @param state        该玩家上一包后的状态（null/EMPTY 表示还没发过）
	 * @param nowTick      服务端当前 tick
	 * @param maxPerSecond 每秒上限；<=0 表示不限速（给测试/关闭限流用）
	 * @return 放行时返回新状态；超限返回 {@code null}（调用方丢弃这个包）
	 */
	public static State admit(State state, long nowTick, int maxPerSecond) {
		long window = Math.floorDiv(nowTick, 20L);
		if (maxPerSecond <= 0) {
			return new State(window, state == null ? 1 : state.count() + 1);
		}
		if (state == null || state.windowIndex() != window) {
			return new State(window, 1);
		}
		if (state.count() >= maxPerSecond) {
			return null;
		}
		return new State(window, state.count() + 1);
	}
}
