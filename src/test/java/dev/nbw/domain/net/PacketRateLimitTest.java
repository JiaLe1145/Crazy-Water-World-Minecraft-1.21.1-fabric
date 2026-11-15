package dev.nbw.domain.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 输入包限速（Gate G5 反作弊）。窗口边界用 tick 钉，不依赖真实服务端。 */
class PacketRateLimitTest {

	@Test
	void firstBurstUpToTheLimitIsAllowedThenDropped() {
		PacketRateLimit.State st = null;
		for (int i = 0; i < 40; i++) {
			st = PacketRateLimit.admit(st, 100, 40);
			assertNotNull(st, String.format("第 %d 包应当放行", i + 1));
		}
		assertEquals(40, st.count());
		assertNull(PacketRateLimit.admit(st, 100, 40), "第 41 包必须被丢弃");
	}

	@Test
	void aNewWindowResetsTheCount() {
		PacketRateLimit.State st = null;
		for (int i = 0; i < 40; i++) {
			st = PacketRateLimit.admit(st, 100, 40);
		}
		assertNull(PacketRateLimit.admit(st, 100, 40));
		// tick 120 = 下一个 1 秒窗口（windowIndex 6 vs 5）
		PacketRateLimit.State next = PacketRateLimit.admit(st, 120, 40);
		assertNotNull(next, "新窗口第一包必须放行");
		assertEquals(1, next.count());
	}

	@Test
	void theWindowBoundaryIsAtEveryTwentyTicks() {
		// tick 119 与 120 是两个窗口；119 的计数不能压到 120 的窗口上。
		PacketRateLimit.State st = PacketRateLimit.admit(null, 119, 40);
		assertEquals(5, st.windowIndex());   // 119 / 20 = 5
		PacketRateLimit.State next = PacketRateLimit.admit(st, 120, 40);
		assertEquals(6, next.windowIndex());
		assertEquals(1, next.count());
	}

	@Test
	void zeroOrNegativeLimitMeansUnlimited() {
		PacketRateLimit.State st = null;
		for (int i = 0; i < 500; i++) {
			st = PacketRateLimit.admit(st, 200, 0);
			assertNotNull(st, String.format("限速关闭时第 %d 包也应放行", i + 1));
		}
	}

	@Test
	void nullStateStartsAFreshWindow() {
		PacketRateLimit.State st = PacketRateLimit.admit(null, -1, 40);
		assertNotNull(st);
		assertEquals(1, st.count());
		assertEquals(Math.floorDiv(-1L, 20L), st.windowIndex());
	}
}
