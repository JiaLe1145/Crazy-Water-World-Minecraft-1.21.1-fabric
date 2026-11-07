package dev.nbw.domain.raft;

/**
 * 遥控器输入 → 移动意图的纯函数（问题③：W前进/S后退/A左转/D右转）。
 *
 * <p>零依赖、可单测。它只把四个布尔键翻译成两个有符号量：
 * <ul>
 *   <li>{@code linear}：+1 前进、-1 后退、0 不动（W/S 同时按或都不按 = 0）；</li>
 *   <li>{@code turn}：+1 左转、-1 右转、0 不转（A/D 同理互斥）。</li>
 * </ul>
 * 真正的"前进方向取筏的朝向、施多大冲量"在 {@code raft/RemoteService} 里，不在这里。
 */
public final class HelmModel {

	/** 一次按键组合翻译出来的移动意图。 */
	public record Intent(int linear, int turn) {

		public static final Intent NONE = new Intent(0, 0);

		public boolean idle() {
			return linear == 0 && turn == 0;
		}
	}

	private HelmModel() {
	}

	/**
	 * 四个键 → 意图。前进后退互斥、左转右转互斥，同时按或都不按记 0。
	 * 这样"W 和 S 一起按"不会白得双倍动力，"A 和 D 一起按"也不会原地抖动。
	 */
	public static Intent fromKeys(boolean forward, boolean backward, boolean left, boolean right) {
		int linear = (forward == backward) ? 0 : (forward ? 1 : -1);
		int turn = (left == right) ? 0 : (left ? 1 : -1);
		return new Intent(linear, turn);
	}
}
