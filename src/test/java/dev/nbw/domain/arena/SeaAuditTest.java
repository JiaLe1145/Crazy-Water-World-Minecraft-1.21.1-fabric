package dev.nbw.domain.arena;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地形审计的判定口径。这里最有价值的一条是"区块没加载不能被读成没有海"——
 * 读数和结论必须一一对应，否则一条假失败会把人引去查一个根本不存在的 bug。
 */
class SeaAuditTest {

	private static final SeaAudit.Expect CENTER = new SeaAudit.Expect(0, 0, "圈心", 62);

	private static SeaAudit.Column good() {
		return new SeaAudit.Column(true, 62, 52);
	}

	@Test
	void matchingSeaIsClean() {
		assertEquals(SeaAudit.Verdict.OK, SeaAudit.verdict(CENTER, good()));
		assertTrue(SeaAudit.audit(List.of(new SeaAudit.Reading(CENTER, good()))).isEmpty(),
				"完全吻合时不该编出任何问题行");
	}

	@Test
	void unloadedChunkIsNotReportedAsMissingSea() {
		SeaAudit.Reading r = new SeaAudit.Reading(CENTER, SeaAudit.Column.notLoaded());
		assertEquals(SeaAudit.Verdict.CHUNK_NOT_LOADED, r.verdict());
		String line = SeaAudit.describe(r);
		assertTrue(line.contains("无法判定"), () -> "要说清是判不了，实际：" + line);
		assertFalse(line.contains("没有水"), () -> "未加载绝不能说成没有海，实际：" + line);
		// 拼多段字符串时 .formatted 只会绑到最后一段，占位符会原样发出去 —— 这里把它钉住
		assertTrue(line.contains("圈心 (0,0)"), () -> "标签和坐标必须真被替换进去，实际：" + line);
		assertFalse(line.contains("%s") || line.contains("%d"), () -> "有占位符没填： " + line);
	}

	@Test
	void dryColumnIsNoWater() {
		SeaAudit.Column c = new SeaAudit.Column(true, -1, -1);
		assertEquals(SeaAudit.Verdict.NO_WATER, SeaAudit.verdict(CENTER, c));
		assertTrue(SeaAudit.describe(new SeaAudit.Reading(CENTER, c)).contains("一个水方块都没有"));
	}

	@Test
	void waterAtWrongLevelReportsTheGap() {
		SeaAudit.Column c = new SeaAudit.Column(true, 64, 52);
		assertEquals(SeaAudit.Verdict.WRONG_LEVEL, SeaAudit.verdict(CENTER, c));
		String line = SeaAudit.describe(new SeaAudit.Reading(CENTER, c));
		assertTrue(line.contains("差 2 格"), () -> "要报出差几格，实际：" + line);
	}

	@Test
	void floorDepthMismatchIsItsOwnVerdict() {
		// 水面正常但海床比分层浅一格（建海中断、或水位改了没重生成地形）—— 这既不是没水也不是水位错
		assertEquals(SeaAudit.Verdict.WRONG_FLOOR, SeaAudit.verdict(CENTER, new SeaAudit.Column(true, 62, 53)));
		assertEquals(SeaAudit.Verdict.WRONG_FLOOR, SeaAudit.verdict(CENTER, new SeaAudit.Column(true, 62, 45)));
	}

	@Test
	void floorTopDerivesFromWaterDepth() {
		assertEquals(62 - SeaAudit.WATER_DEPTH, CENTER.floorTop());
		assertEquals(62 + SeaAudit.AIR_CLEAR, CENTER.airCeiling());
	}

	@Test
	void everyVerdictMessageIsFullySubstituted() {
		List<SeaAudit.Column> samples = List.of(
				good(),                                          // OK
				new SeaAudit.Column(true, -1, -1),               // NO_WATER
				new SeaAudit.Column(true, 64, 52),               // WRONG_LEVEL
				new SeaAudit.Column(true, 62, 53),               // WRONG_FLOOR
				SeaAudit.Column.notLoaded());                    // CHUNK_NOT_LOADED
		for (SeaAudit.Column c : samples) {
			String line = SeaAudit.describe(new SeaAudit.Reading(CENTER, c));
			assertFalse(line.contains("%s") || line.contains("%d"),
					() -> c + " 这条文案有占位符没填：" + line);
			assertTrue(line.contains("圈心 (0,0)"), () -> c + " 这条文案没带坐标：" + line);
		}
		// 五个分支必须真的互不相同，否则上面两条断言其实只测了一种文案
		assertEquals(5, samples.stream().map(c -> SeaAudit.verdict(CENTER, c)).distinct().count(),
				"五个采样要能走出五种判定");
	}

	@Test
	void auditListsOnlyProblemsInOrder() {
		List<SeaAudit.Reading> readings = List.of(
				new SeaAudit.Reading(CENTER, good()),
				new SeaAudit.Reading(new SeaAudit.Expect(0, -112, "队0", 62), new SeaAudit.Column(true, -1, -1)),
				new SeaAudit.Reading(new SeaAudit.Expect(112, 0, "队1", 62), SeaAudit.Column.notLoaded()));
		List<String> problems = SeaAudit.audit(readings);
		assertEquals(2, problems.size(), "好的一列不该混进问题清单");
		assertTrue(problems.get(0).contains("队0"), () -> "实际：" + problems);
		assertTrue(problems.get(1).contains("队1"), () -> "实际：" + problems);
	}
}
