package dev.nbw.domain.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预热背压的判定。这条路径的存在理由本身就是一个实测教训：
 * 把速率从 2 个/tick 降到 1 个/tick 之后，首批 81 个区块仍然攒出 6137ms 落后 ——
 * 所以"控速率"不是解法，"看服务端快慢再决定加不加活"才是。
 */
class ChunkPacingTest {

	@Test
	void normalTickPaceKeepsLoading() {
		// 50ms 是一 tick 的正常间隔：这时应该继续加载，否则预热永远做不完
		assertTrue(ChunkPacing.shouldLoadThisTick(0, ChunkPacing.SLOW_TICK_MS));
		assertTrue(ChunkPacing.shouldLoadThisTick(50, ChunkPacing.SLOW_TICK_MS));
		assertTrue(ChunkPacing.shouldLoadThisTick(ChunkPacing.SLOW_TICK_MS, ChunkPacing.SLOW_TICK_MS));
	}

	@Test
	void slowTickPauses() {
		assertFalse(ChunkPacing.shouldLoadThisTick(ChunkPacing.SLOW_TICK_MS + 1, ChunkPacing.SLOW_TICK_MS),
				"服务端已经在补账还继续加区块，就是当年那次 6.1 秒尖峰的成因");
		assertFalse(ChunkPacing.shouldLoadThisTick(6137, ChunkPacing.SLOW_TICK_MS));
	}

	@Test
	void clockBackwardsPausesInsteadOfLoading() {
		// 时钟回拨给出的负间隔不能当成"跑得很快"，宁可停一拍
		assertFalse(ChunkPacing.shouldLoadThisTick(-5, ChunkPacing.SLOW_TICK_MS));
	}

	@Test
	void disabledThresholdNeverBlocksProgress() {
		// 关背压的语义必须是"照常加载"，而不是"永远不加载"把预热冻死
		assertTrue(ChunkPacing.shouldLoadThisTick(99999, 0));
		assertTrue(ChunkPacing.shouldLoadThisTick(99999, -1));
	}

	@Test
	void everyTickWithinBudgetStillMakesProgress() {
		// 绊线：阈值如果被调到 ≤50ms，正常 tick 也会被判定为"慢"，预热就会原地踏步
		assertTrue(ChunkPacing.shouldLoadThisTick(50, ChunkPacing.SLOW_TICK_MS),
				"阈值必须严格大于一个正常 tick 的 50ms：" + ChunkPacing.SLOW_TICK_MS);
	}
}
