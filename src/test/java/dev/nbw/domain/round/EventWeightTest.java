package dev.nbw.domain.round;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 事件权重（02 §10 的“间隔权重”）。边界用 pickAt 钉，不依赖随机序列。 */
class EventWeightTest {

	@Test
	void weightsMatchTheDocumentedHighMediumLowTable() {
		// 02 §10：物资雨=高、风暴/倒流=中、聚集点/悬赏=低。
		assertEquals(EventWeight.HIGH, EventWeight.weightOf("supply_rain"));
		assertEquals(EventWeight.MEDIUM, EventWeight.weightOf("storm"));
		assertEquals(EventWeight.MEDIUM, EventWeight.weightOf("black_sea_surge"));
		assertEquals(EventWeight.LOW, EventWeight.weightOf("survivor_drift"));
		assertEquals(EventWeight.LOW, EventWeight.weightOf("bounty"));
	}

	@Test
	void unknownAndNullOrCaseInsensitiveInputsAreSafe() {
		// 运维在 pool 里写新事件名时不能把整池抽不出来，也不能因为 null 抛异常。
		assertEquals(EventWeight.DEFAULT, EventWeight.weightOf("treasure_fragment"));
		assertEquals(EventWeight.DEFAULT, EventWeight.weightOf(null));
		assertEquals(EventWeight.MEDIUM, EventWeight.weightOf("STORM"));
		assertEquals(EventWeight.HIGH, EventWeight.weightOf(" supply_rain "));
	}

	@Test
	void pickAtUsesTheExactBoundariesOfTheWeightedRange() {
		// 物资雨 3 + 悬赏 1 = 4：roll 0/1/2 命中物资雨，3 命中悬赏。
		List<String> pool = List.of("supply_rain", "bounty");
		assertEquals("supply_rain", EventWeight.pickAt(pool, 0));
		assertEquals("supply_rain", EventWeight.pickAt(pool, 1));
		assertEquals("supply_rain", EventWeight.pickAt(pool, 2));
		assertEquals("bounty", EventWeight.pickAt(pool, 3));
	}

	@Test
	void pickAtWrapsRatherThanThrowingOnOutOfRangeRolls() {
		List<String> pool = List.of("supply_rain", "bounty");   // 总权重 4
		assertEquals("supply_rain", EventWeight.pickAt(pool, 4));
		assertEquals("bounty", EventWeight.pickAt(pool, -1));    // floorMod(-1,4)=3
		assertEquals("bounty", EventWeight.pickAt(pool, 999));   // 999 mod 4 = 3
	}

	@Test
	void aMediumPlusLowPoolSplitsAtTwoThirds() {
		// 风暴 2 + 聚集点 1 = 3：roll 0/1 风暴，2 聚集点。
		List<String> pool = List.of("storm", "survivor_drift");
		assertEquals("storm", EventWeight.pickAt(pool, 0));
		assertEquals("storm", EventWeight.pickAt(pool, 1));
		assertEquals("survivor_drift", EventWeight.pickAt(pool, 2));
	}

	@Test
	void allUnknownEventsStillSpreadAcrossThePool() {
		// 全是未知事件时按各 1 处理，不能永远返回第一个。
		List<String> pool = List.of("foo", "bar");
		assertEquals("foo", EventWeight.pickAt(pool, 0));
		assertEquals("bar", EventWeight.pickAt(pool, 1));
	}

	@Test
	void emptyOrNullPoolReturnsNullInsteadOfThrowing() {
		assertNull(EventWeight.pick(List.of(), new Random(1)));
		assertNull(EventWeight.pick(null, new Random(1)));
		assertNull(EventWeight.pickAt(List.of(), 0));
	}

	@Test
	void randomPickNeverLeavesThePool() {
		List<String> pool = List.of("supply_rain", "storm", "black_sea_surge",
				"survivor_drift", "bounty");
		Random rnd = new Random(20261114L);
		for (int i = 0; i < 500; i++) {
			assertTrue(pool.contains(EventWeight.pick(pool, rnd)));
		}
	}
}
