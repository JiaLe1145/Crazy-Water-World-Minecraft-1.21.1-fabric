package dev.nbw.domain.construct;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建造规则（01 §4.2 / M2-6）。
 *
 * <p>这组测试守的是<b>五条门各自能不能单独挡住人</b>：归属、阶段、建造区、邻接、数量上限。
 * 适配层的探针只能证明"某一次点击被拒了"，证明不了"是被哪一条拒的"——
 * 而线上真出事时（别队 grief、上限配错、建造区太小导致开局就放不了方块），
 * 需要的正是后者。所以每条门都在这里各测一次"只差这一个输入"的对照。
 */
class BuildRulesTest {

	private static final int AREA = 96;
	private static final int MAX_BLOCKS = 3200;
	private static final int MAX_FACILITIES = 8;
	/** 默认用例的甲板列数：49 列 → 设施位 min(8, 49/5)=8，让硬上限成为约束。 */
	private static final int COLUMNS = 49;

	/** 一次"本来就合法"的放置：自家筏、甲板边上贴着一格、数量都在线内。 */
	private static String place(int lx, int ly, int lz, int neighborMask,
			int blocks, boolean facility, int facilities) {
		return place(lx, ly, lz, neighborMask, blocks, facility, facilities, COLUMNS);
	}

	private static String place(int lx, int ly, int lz, int neighborMask,
			int blocks, boolean facility, int facilities, int columns) {
		return BuildRules.checkPlace(true, 0, 0, lx, ly, lz, AREA, neighborMask,
				blocks, MAX_BLOCKS, columns, facility, facilities, MAX_FACILITIES);
	}

	@Test
	void legalPlacementOnOwnRaftIsAllowed() {
		// 这条是其余用例的"绿灯基线"：它要是红了，后面所有"被拒"的断言都说明不了任何事
		assertNull(place(3, 1, 0, 1, 49, false, 1), "贴着甲板边缘正常扩建应当放行");
		assertNull(BuildRules.checkBreak(true, 0, 0, false), "拆自家普通方块应当放行");
	}

	@Test
	void ownershipGateRejectsEachWrongdoerSeparately() {
		// 四道前置门必须给出四个不同的原因，否则玩家与探针都无法判断"到底哪条拦的我"
		String wrongPhase = BuildRules.checkOwnership(false, 0, 0);
		String noTeam = BuildRules.checkOwnership(true, -1, 0);
		String openSea = BuildRules.checkOwnership(true, 0, -1);
		String enemyRaft = BuildRules.checkOwnership(true, 0, 1);
		for (String r : new String[]{wrongPhase, noTeam, openSea, enemyRaft}) {
			assertNotNull(r, "前置门不该放行");
		}
		assertNotEquals(wrongPhase, noTeam);
		assertNotEquals(noTeam, openSea);
		assertNotEquals(openSea, enemyRaft);
		assertNotEquals(wrongPhase, enemyRaft);
		assertTrue(enemyRaft.contains("1"), "别队筏的原因必须说清是哪一队：" + enemyRaft);
		assertNull(BuildRules.checkOwnership(true, 2, 2), "同队自家筏不该被归属门拦住");
	}

	@Test
	void phaseGateWinsOverEverythingElse() {
		// 大厅里没分队、点的也不是筏：玩家第一句应该听到"现在还不行（阶段）"，不是"超出建造区"
		String r = BuildRules.checkPlace(false, -1, -1, 999, 999, 999,
				AREA, 0, MAX_BLOCKS, MAX_BLOCKS, 0, true, MAX_FACILITIES, MAX_FACILITIES);
		assertTrue(r.contains("阶段"), "阶段门应当最先说：" + r);
	}

	@Test
	void buildRadiusFollowsAreaCeilingAndDiesForTinyAreas() {
		// 半径必须让 footprint 落在面积上限之内，否则 maxRaftArea 这个字段名就是假的
		assertEquals(4, BuildRules.buildRadius(96), "96 → 9×9=81 列 ≤ 96");
		assertEquals(4, BuildRules.buildRadius(100), "100 也只能取奇数边长 9，10 边长是偶数要退一格");
		assertEquals(3, BuildRules.buildRadius(49), "49 正好等于初始筏 7×7，语义是只许原范围内重建");
		assertEquals(0, BuildRules.buildRadius(5), "小面积自然收敛到 0，不需要特例");
		assertEquals(0, BuildRules.buildRadius(4));
		assertEquals(0, BuildRules.buildRadius(0));
		assertEquals(0, BuildRules.buildRadius(-7), "脏配置不许变成负半径（那会让原因难读）");
		assertTrue(BuildRules.buildRadius(400) > BuildRules.buildRadius(96), "半径必须随面积单调增");
		assertTrue((2 * BuildRules.buildRadius(96) + 1) * (2 * BuildRules.buildRadius(96) + 1) <= 96,
				"建造区 footprint 不得超过 maxRaftArea，这才是这个字段的定义");
	}

	@Test
	void buildZoneIsBoundedHorizontallyAndVertically() {
		int r = BuildRules.buildRadius(AREA);
		assertTrue(BuildRules.inBuildZone(r, 0, -r, r), "建造区四角是包含边界");
		assertFalse(BuildRules.inBuildZone(r + 1, 0, 0, r), "水平越界必须不算在区内");
		assertFalse(BuildRules.inBuildZone(0, BuildRules.MAX_LOCAL_Y + 1, 0, r), "屋顶以上不许造");
		assertFalse(BuildRules.inBuildZone(0, BuildRules.MIN_LOCAL_Y - 1, 0, r), "甲板下 2 格不许造");
		assertNotNull(place(r + 1, 1, 0, 1, 49, false, 1), "越界放置必须被拒");
		assertTrue(place(r + 1, 1, 0, 1, 49, false, 1).contains("超出建造区"));
		assertNull(place(r, 1, 0, 1, 49, false, 1), "边界内一格应当放行");
	}

	@Test
	void floatingBlockNeedsExactlyOneSolidNeighbour() {
		assertNotNull(place(4, 4, 4, 0, 49, false, 1), "六邻全空不能放");
		assertTrue(place(4, 4, 4, 0, 49, false, 1).contains("依附"));
		assertNull(place(4, 4, 4, 1, 49, false, 1), "只有一格邻接也应当放行");
		assertNull(place(4, 4, 4, 0b111111, 49, false, 1), "六面围住更要放行");
	}

	@Test
	void blockCountCapRejectsOnlyAtTheLimit() {
		assertNull(place(4, 1, 0, 1, MAX_BLOCKS - 1, false, 1), "还差一格应当放行");
		String at = place(4, 1, 0, 1, MAX_BLOCKS, false, 1);
		assertNotNull(at, "达到上限必须被拒");
		assertTrue(at.contains(String.valueOf(MAX_BLOCKS)), "原因里要带上限数值，玩家才知道卡在哪：" + at);
		assertNotNull(place(4, 1, 0, 1, MAX_BLOCKS + 500, false, 1), "超上限更要拒（扫描值可能因别处写入而越过）");
	}

	@Test
	void facilityCapOnlyBitesFacilityBlocks() {
		// 同一次判定，唯一区别是"放的是不是设施"：这条断言防止我们把上限错扣在普通方块头上
		assertNull(place(4, 1, 0, 1, 49, false, MAX_FACILITIES), "设施位满时仍应能放普通方块");
		String r = place(4, 1, 0, 1, 49, true, MAX_FACILITIES);
		assertNotNull(r, "设施位满必须拒绝再来一个设施");
		assertTrue(r.contains("设施"), "原因要说清是设施位卡的：" + r);
		assertTrue(r.contains("maxFacilities"), "撞到硬上限时原因要点名 limits.maxFacilities：" + r);
		assertNull(place(4, 1, 0, 1, 49, true, MAX_FACILITIES - 1), "还剩一个位子时必须放行");
	}

	@Test
	void facilitySlotsGrowWithDeckAreaNotWithBlockCount() {
		// 02 §5 批准的口径：每 5 格甲板 1 位，maxFacilities 只是天花板
		assertEquals(3, BuildRules.facilitySlots(16, MAX_FACILITIES), "4×4=16 列 → 3 位");
		assertEquals(5, BuildRules.facilitySlots(25, MAX_FACILITIES), "5×5=25 列 → 5 位");
		assertEquals(8, BuildRules.facilitySlots(81, MAX_FACILITIES), "81 列本该给 16 位，被硬上限夹在 8");
		assertEquals(0, BuildRules.facilitySlots(4, MAX_FACILITIES), "不足 5 列一个位都不给");
		assertEquals(0, BuildRules.facilitySlots(100, 0), "硬上限配成 0 就是不给位（脏配置要 fail closed）");
		assertEquals(0, BuildRules.facilitySlots(0, MAX_FACILITIES), "读不到列数时不许默认放行");

		// 同样 3 个设施、只有甲板大小不同：小筏被面积卡住，大筏还在限内
		String small = place(4, 1, 0, 1, 16, true, 3, 16);
		assertNotNull(small, "16 列的筏只有 3 个位，第 4 个设施必须被拒");
		assertTrue(small.contains("每 " + BuildRules.DECK_COLUMNS_PER_FACILITY + " 格甲板"),
				"被面积卡住时原因要说「甲板太小」，不是「撞到 maxFacilities」：" + small);
		assertNull(place(4, 1, 0, 1, 49, true, 3, 49), "同样 3 个设施在 49 列的筏上限内，必须放行");
	}

	@Test
	void breakIgnoresBuildZoneButProtectsTheCore() {
		// 建造区外可能留着历史方块（改过配置），这时必须还能拆掉它——
		// 拆不动的方块是玩家最恼火的 bug，所以破坏判定压根不接受坐标
		assertNull(BuildRules.checkBreak(true, 0, 0, false));
		assertNotNull(BuildRules.checkBreak(true, 0, 1, false), "别队筏不许拆");
		assertNotNull(BuildRules.checkBreak(true, -1, 0, false), "没分队不许拆");
		assertNotNull(BuildRules.checkBreak(true, 0, -1, false), "公共海域不许拆");
		assertNotNull(BuildRules.checkBreak(false, 0, 0, false), "非 PLAY 阶段不许拆");
		String core = BuildRules.checkBreak(true, 0, 0, true);
		assertNotNull(core);
		assertTrue(core.contains("筏心"), "筏心要走破拆伤害那条路，原因必须写明：" + core);
	}

}
