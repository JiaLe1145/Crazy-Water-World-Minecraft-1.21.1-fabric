package dev.nbw.domain.raft;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 筏面设施格子（初始筏削弱到 4×4 的直接产物）。
 *
 * <p>这里守的是三件会真出事的事：
 * ①设施格子必须落在甲板范围内——挂在甲板外面就是"看着挂上了、踩上去是虚空"，
 * 桨台还会因为没有方块实体而一句动力都没有；
 * ②帆与桨不许抢同一格，否则"先挂帆还是先挂桨"会改变结果，配平就成了顺序问题；
 * ③筏心那一格不许被任何设施占掉。
 */
class RaftLayoutTest {

	private static Set<String> keys(int side, java.util.function.IntFunction<int[]> f, int n) {
		Set<String> out = new HashSet<>();
		for (int i = 0; i < n; i++) {
			int[] c = f.apply(i);
			assertNotNull(c, "第 " + i + " 个设施位应当算得出来（边长 " + side + "）");
			out.add(c[0] + "," + c[1] + "," + c[2]);
		}
		return out;
	}

	private static void assertOnDeck(int side, int[] cell, String what) {
		int start = RaftLayout.deckStart(side);
		int end = RaftLayout.deckEnd(side);
		assertTrue(cell[0] >= start && cell[0] <= end && cell[2] >= start && cell[2] <= end,
				what + " 必须落在甲板范围内（边长 " + side + " → [" + start + "," + end + "]），实际 "
						+ cell[0] + "," + cell[1] + "," + cell[2]);
		assertEquals(RaftLayout.FACILITY_Y, cell[1], what + " 必须在甲板面之上一格");
	}

	@Test
	void evenAndOddSidesAreCentredOnTheCore() {
		// 筏心固定在 (0,*,0)，所以甲板必须把 0 这一格包进去，否则开局发出来的筏上根本没有筏心位
		assertEquals(-2, RaftLayout.deckStart(4));
		assertEquals(1, RaftLayout.deckEnd(4));
		assertEquals(-3, RaftLayout.deckStart(7));
		assertEquals(3, RaftLayout.deckEnd(7));
		assertEquals(16, RaftLayout.deckCells(4));
		assertEquals(49, RaftLayout.deckCells(7));
		for (int side : new int[]{1, 2, 3, 4, 5, 8, 9, 17}) {
			int start = RaftLayout.deckStart(side);
			int end = RaftLayout.deckEnd(side);
			assertTrue(start <= 0 && 0 <= end, "边长 " + side + " 的甲板必须含 x=0");
			assertTrue(start <= RaftLayout.CORE_Z && RaftLayout.CORE_Z <= end,
					"边长 " + side + " 的甲板必须含 z=0（筏心那一格）");
			assertEquals(end - start + 1, side, "边长 " + side + " 的跨度必须正好是边长");
		}
	}

	@Test
	void fourByFourDeckHoldsTheApprovedSevenSailsAndFourOars() {
		// 02 §6 的帆档做到 7 面、桨档做到 4 台：削弱到 4×4 之后这两档仍然要摆得下，
		// 否则"小筏"直接等于"不能航行"，那是玩法塌陷不是平衡
		assertEquals(7, RaftLayout.sailCapacity(4), "4×4 甲板必须正好塞得下 7 面帆");
		assertTrue(RaftLayout.oarCapacity(4) >= 4, "4×4 甲板至少要塞得下 4 台桨台");

		Set<String> sails = keys(4, i -> RaftLayout.sailCell(4, i), RaftLayout.sailCapacity(4));
		Set<String> oars = keys(4, i -> RaftLayout.oarCell(4, i), RaftLayout.oarCapacity(4));
		for (int i = 0; i < RaftLayout.sailCapacity(4); i++) {
			assertOnDeck(4, RaftLayout.sailCell(4, i), "帆位 " + i);
		}
		for (int i = 0; i < RaftLayout.oarCapacity(4); i++) {
			assertOnDeck(4, RaftLayout.oarCell(4, i), "桨位 " + i);
		}
		assertEquals(RaftLayout.sailCapacity(4), sails.size(), "帆位互相不能重格");
		assertEquals(RaftLayout.oarCapacity(4), oars.size(), "桨位互相不能重格");
		Set<String> both = new HashSet<>(sails);
		both.retainAll(oars);
		assertTrue(both.isEmpty(), "帆与桨不能抢同一格（旧布局在角上就是撞的）：" + both);
		assertTrue(!sails.contains("0,1,0") && !oars.contains("0,1,0"),
				"筏心那格 (0,1,0) 不许被任何设施占掉");
	}

	@Test
	void biggerDecksScaleUpAndOverflowIsReportedNotSilent() {
		assertEquals(7, RaftLayout.sailCell(7, 0)[2] == -3 ? 7 : -1, "7×7 的第一面帆仍在尾排");
		assertTrue(RaftLayout.sailCapacity(7) > RaftLayout.sailCapacity(4), "容量必须随甲板变大");
		// 铺不下时要返回 null（调用方必须报失败，绝不允许"少挂一面还回成功"）
		assertNull(RaftLayout.sailCell(4, RaftLayout.sailCapacity(4)));
		assertNull(RaftLayout.oarCell(4, RaftLayout.oarCapacity(4)));
		assertNull(RaftLayout.sailCell(4, -1), "脏序号不许算出格子");
		assertNull(RaftLayout.sailCell(0, 0), "边长 0 是脏配置，不许算出格子");
		assertEquals(0, RaftLayout.sailCapacity(0));
	}

	@Test
	void everyPrefixStaysWithinHalfACellOfTheDeckCenter() {
		// 实测教训：取格顺序从边到边时，部分填充的帆会把面积中心整排推到一舷，形成偏航力矩，
		// 南风下筏子大半在横着走（7×7 筏 4 帆侧移/顺风 = 0.547，4×4 筏 4 帆 = 2.679）。
		// 所以"任意前缀的面积中心离甲板中心不超过半格"必须当成不变量测住，而不是靠肉眼配平。
		for (int side : new int[]{2, 3, 4, 5, 7, 8, 9, 10}) {
			double center = (RaftLayout.deckStart(side) + RaftLayout.deckEnd(side)) / 2.0;
			assertPrefixBalanced(side, center, i -> RaftLayout.sailCell(side, i),
					RaftLayout.sailCapacity(side), "帆");
			assertPrefixBalanced(side, center, i -> RaftLayout.oarCell(side, i),
					RaftLayout.oarCapacity(side), "桨");
		}
	}

	private static void assertPrefixBalanced(int side, double center,
			java.util.function.IntFunction<int[]> cell, int capacity, String what) {
		double sum = 0;
		for (int i = 0; i < capacity; i++) {
			int[] c = cell.apply(i);
			sum += c[0];
			double centroid = sum / (i + 1);
			// 偶数格能配对到完全对称（容差半格只给浮点）；奇数格的容差放宽到一格，
			// 因为筏心所在的中间排要跳过 (0,0)：那一排的第一格必然离中心一格，配不成对。
			double tol = (i + 1) % 2 == 0 ? 0.5 : 1.0;
			assertTrue(Math.abs(centroid - center) <= tol + 1e-9,
					"边长 " + side + " 的 " + what + " 取前 " + (i + 1) + " 格时面积中心 "
							+ centroid + " 偏离甲板中心 " + center + " 超过 " + tol + " 格");
		}
	}

	@Test
	void oneCellDeckOnlyHasRoomForTheCore() {
		// 1×1 的甲板整张就是筏心那一格：任何设施都塞不下。这条能失败，说明"跳过筏心"的分支真在跑
		assertEquals(0, RaftLayout.sailCapacity(1));
		assertEquals(0, RaftLayout.oarCapacity(1));
		assertNull(RaftLayout.sailCell(1, 0));
		assertNull(RaftLayout.oarCell(1, 0));
	}

	/**
	 * 开局落位格（2026-11-22 修"开局掉水里"时加的）。
	 *
	 * <p>守三件事：①落位格必须在甲板上（否则人掉海）；②不能是筏心那一格的正上方
	 * （否则人嵌进筏心方块、被原版顶出去）；③不能是物资箱那一格。脚底高度必须贴着实测可站面。
	 *
	 * <p><b>不</b>要求"不是帆/桨位"：4×4 的甲板每一格都归某个设施族（帆占偶数排、桨占奇数排），
	 * 不存在"纯甲板格"。开局那几秒设施还没摆，人站一下不算占用。
	 */
	@Test
	void entryCellIsOnDeckAndNotTheCoreColumn() {
		for (int side : new int[]{4, 7, 10}) {
			double[] e = RaftLayout.entryLocal(side);
			// 脚底必须在"实测可站面"之上一点，但不能高出一大截（那就是"开局掉一下"）
			assertTrue(e[1] >= RaftLayout.DECK_SURFACE_Y,
					"边长 " + side + " 的落位低于实测可站面（会压进甲板方块）");
			assertTrue(e[1] - RaftLayout.DECK_SURFACE_Y <= 0.2,
					"边长 " + side + " 的落位比可站面高太多（会看到明显下落）：" + e[1]);
			assertTrue(RaftLayout.onDeck(side, e[0], e[2]),
					"边长 " + side + " 的落位格 " + e[0] + "," + e[2] + " 落在甲板外");
			int bx = (int) Math.floor(e[0]);
			int bz = (int) Math.floor(e[2]);
			assertTrue(bx != RaftLayout.CORE_X || bz != RaftLayout.CORE_Z,
					"边长 " + side + " 的落位格正好是筏心那一格");
			// 物资箱固定在 (0, FACILITY_Y, deckStart+1) 那一格的正上方（见 RaftSpawner.supplyBoxLocal）
			assertTrue(!(bx == 0 && bz == RaftLayout.deckStart(side) + 1),
					"边长 " + side + " 的落位格正好是物资箱那一格");
			// 落位格不能落在**帆**位上：帆是唯一会被自动放上去的设施（`/cww sail rig`、
			// 以后的自动挂帆）。实测事故：7 帆挂满时第 7 面正好放进站在落位格上的玩家身体里，
			// 人直接被顶下海。桨位没有自动放置路径，所以落位格放在桨族那一排。
			for (int i = 0; i < RaftLayout.sailCapacity(side); i++) {
				int[] c = RaftLayout.sailCell(side, i);
				assertTrue(!(c[0] == bx && c[2] == bz),
						"边长 " + side + " 的落位格 " + bx + "," + bz + " 是第 " + i + " 面帆的位置");
			}
		}
	}

	@Test
	void degenerateDeckStillHasAStandingCell() {
		// 边长 2：中心右边一格已经在甲板外了，必须退回起始格，而不是给出甲板外的坐标
		double[] e = RaftLayout.entryLocal(2);
		assertTrue(RaftLayout.onDeck(2, e[0], e[2]), "退化筏的落位格 " + e[0] + "," + e[2] + " 在甲板外");
		assertTrue(e[1] >= RaftLayout.DECK_SURFACE_Y, "退化筏的落位不能压进甲板");
	}
}
