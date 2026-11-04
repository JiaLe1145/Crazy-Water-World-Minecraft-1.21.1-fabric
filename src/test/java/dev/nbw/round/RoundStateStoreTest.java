package dev.nbw.round;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nbw.domain.round.Phase;
import dev.nbw.domain.round.RoundConfig;
import dev.nbw.domain.round.RoundEvent;
import dev.nbw.domain.round.RoundMachine;
import dev.nbw.domain.round.RoundSnapshot;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回合状态落盘文件层（Gate G1"重启能恢复到当前阶段"的磁盘那一半）。
 *
 * <p>这里测的是"文件真的能被读回来、读不回来时怎么表态"。
 * 用 {@code @TempDir} 而不是内存串：原子移动与 {@code .tmp} 残留只能在真实文件系统上看出来。
 */
class RoundStateStoreTest {

	private static final UUID P1 = UUID.nameUUIDFromBytes("p1".getBytes());
	private static final UUID P2 = UUID.nameUUIDFromBytes("p2".getBytes());

	private static RoundMachine inPlay() {
		RoundMachine m = new RoundMachine(new RoundConfig(2, 2, 2, 40, 20, 600, 40, 10));
		m.setConfigIds("flat_test", "test_3min");   // 与 RoundManager.loadConfigs 成功后的动作一致
		m.queue(P1, "p1");
		m.queue(P2, "p2");
		m.forceTeam(P1, 0);
		m.forceTeam(P2, 1);
		m.fire(RoundEvent.START);
		for (int i = 0; i < 41; i++) {
			m.tick();
		}
		m.fire(RoundEvent.ENROLL_TIMEOUT);
		for (int i = 0; i < 21; i++) {
			m.tick();
		}
		m.fire(RoundEvent.READY_TIMEOUT);
		return m;
	}

	private static Path stateFile(Path worldRoot) {
		return RoundStateStore.stateFile(worldRoot);
	}

	@Test
	void absentFileIsReportedAsAbsentNotBroken(@TempDir Path world) {
		RoundStateStore.Load load = RoundStateStore.read(world);
		assertEquals(RoundStateStore.Status.ABSENT, load.status());
		assertFalse(load.ok());
		assertTrue(load.errors().isEmpty(), "首次启动不该带错误");
		assertTrue(RoundStateStore.describe(load).contains("首次启动"));
	}

	@Test
	void writeThenReadRoundTripsTheWholeSnapshot(@TempDir Path world) {
		RoundMachine live = inPlay();
		RoundSnapshot snap = live.snapshot(RoundStateStore.AUDIT_KEEP);
		RoundStateStore.write(world, snap);

		RoundStateStore.Load load = RoundStateStore.read(world);
		assertTrue(load.ok(), () -> "读不回来：" + load.errors());
		RoundSnapshot back = load.snapshot();

		assertEquals(snap.phase(), back.phase());
		assertEquals(snap.tickInPhase(), back.tickInPhase());
		assertEquals(snap.totalTicks(), back.totalTicks());
		assertEquals(snap.matchIndex(), back.matchIndex());
		assertEquals(snap.matchStartTick(), back.matchStartTick());
		assertEquals(snap.autoStart(), back.autoStart());
		assertEquals(snap.winnerTeamIndex(), back.winnerTeamIndex());
		assertEquals(snap.teams(), back.teams());
		assertEquals(snap.sessions(), back.sessions());
		assertEquals(snap.queue(), back.queue());
		assertEquals(snap.audit(), back.audit());
	}

	@Test
	void restoredFromFileContinuesTheMatch(@TempDir Path world) {
		RoundMachine live = inPlay();
		RoundStateStore.write(world, live.snapshot(RoundStateStore.AUDIT_KEEP));

		RoundMachine restored = new RoundMachine(new RoundConfig(2, 2, 2, 40, 20, 600, 40, 10));
		restored.restore(RoundStateStore.read(world).snapshot());
		assertEquals(Phase.PLAY, restored.phase());

		restored.fire(RoundEvent.WINNER_DECIDED);
		assertEquals(Phase.SETTLE, restored.phase(), "从盘上恢复的机器必须还能正常推进");
	}

	@Test
	void writeIsAtomicAndLeavesNoTempFile(@TempDir Path world) throws IOException {
		RoundStateStore.write(world, inPlay().snapshot(60));
		assertTrue(Files.isRegularFile(stateFile(world)));
		try (var stream = Files.list(stateFile(world).getParent())) {
			List<String> names = stream.map(p -> p.getFileName().toString()).toList();
			assertTrue(names.contains("round.json"), names.toString());
			assertFalse(names.stream().anyMatch(n -> n.endsWith(".tmp")),
					"临时文件残留说明移动没走完：" + names);
		}
	}

	@Test
	void stateFileLivesUnderWorldCwwStateNotUnderConfig(@TempDir Path world) {
		Path file = stateFile(world);
		assertTrue(file.toString().replace('\\', '/').endsWith("/cww/state/round.json"), file.toString());
		// 03 §5：写入型数据与地图作者可编辑的配置严格分离
		assertFalse(file.toString().replace('\\', '/').contains("/arenas/"));
		assertFalse(file.toString().replace('\\', '/').contains("/formats/"));
	}

	@Test
	void truncatedJsonIsInvalidNotSilentlyEmpty(@TempDir Path world) throws IOException {
		RoundStateStore.write(world, inPlay().snapshot(60));
		String json = Files.readString(stateFile(world), StandardCharsets.UTF_8);
		Files.writeString(stateFile(world), json.substring(0, json.length() / 2), StandardCharsets.UTF_8);

		RoundStateStore.Load load = RoundStateStore.read(world);
		assertEquals(RoundStateStore.Status.INVALID, load.status());
		assertFalse(load.errors().isEmpty(), "半截文件必须报错，不能当成" + RoundStateStore.Status.OK + "恢复");
		assertNull(load.snapshot());
	}

	@Test
	void unknownSchemaVersionIsRejected(@TempDir Path world) throws IOException {
		RoundStateStore.write(world, inPlay().snapshot(60));
		String json = Files.readString(stateFile(world), StandardCharsets.UTF_8)
				.replace("\"schemaVersion\":" + RoundStateStore.SCHEMA_VERSION, "\"schemaVersion\":99");
		Files.writeString(stateFile(world), json, StandardCharsets.UTF_8);

		RoundStateStore.Load load = RoundStateStore.read(world);
		assertEquals(RoundStateStore.Status.INVALID, load.status());
		assertTrue(String.join(" ", load.errors()).contains("schemaVersion"), load.errors().toString());
	}

	@Test
	void garbagePhaseNameIsRejectedWithAReadableError(@TempDir Path world) throws IOException {
		Files.createDirectories(stateFile(world).getParent());
		Files.writeString(stateFile(world),
				"{\"schemaVersion\":1,\"phase\":\"NOT_A_PHASE\",\"tickInPhase\":0,\"totalTicks\":0,"
						+ "\"matchIndex\":0,\"matchStartTick\":0,\"autoStart\":false,\"winnerTeamIndex\":-1,"
						+ "\"teams\":[],\"sessions\":[],\"queue\":[],\"audit\":[],\"auditNotes\":[]}",
				StandardCharsets.UTF_8);

		RoundStateStore.Load load = RoundStateStore.read(world);
		assertEquals(RoundStateStore.Status.INVALID, load.status());
		assertTrue(String.join(" ", load.errors()).contains("phase"), load.errors().toString());
	}

	@Test
	void missingOptionalCollectionsDegradeToEmpty(@TempDir Path world) throws IOException {
		Files.createDirectories(stateFile(world).getParent());
		Files.writeString(stateFile(world),
				"{\"schemaVersion\":1,\"phase\":\"LOBBY\",\"tickInPhase\":0,\"totalTicks\":0,"
						+ "\"matchIndex\":0,\"matchStartTick\":0,\"autoStart\":false,\"winnerTeamIndex\":-1,"
						+ "\"teams\":[{\"index\":0,\"name\":\"赤队\",\"maxSize\":2,\"members\":[]}],"
						+ "\"sessions\":[],\"queue\":[],"
						+ "\"arenaId\":\"flat_test\",\"formatId\":\"test_3min\"}",
				StandardCharsets.UTF_8);

		RoundStateStore.Load load = RoundStateStore.read(world);
		assertTrue(load.ok(), () -> "audit 缺失不该让整份快照作废：" + load.errors());
		assertEquals(Phase.LOBBY, load.snapshot().phase());
		assertTrue(load.snapshot().audit().isEmpty());
	}

	@Test
	void describeCarriesTheNumbersOperatorsAskFor(@TempDir Path world) {
		RoundStateStore.write(world, inPlay().snapshot(60));
		String text = RoundStateStore.describe(RoundStateStore.read(world));
		assertTrue(text.contains("PLAY"), text);
		assertTrue(text.contains("round.json"), text);
	}

	@Test
	void rewritingTwiceKeepsTheFileReadable(@TempDir Path world) {
		RoundMachine m = inPlay();
		RoundStateStore.write(world, m.snapshot(60));
		m.fire(RoundEvent.WINNER_DECIDED);
		RoundStateStore.write(world, m.snapshot(60));

		RoundStateStore.Load load = RoundStateStore.read(world);
		assertTrue(load.ok(), () -> load.errors().toString());
		assertEquals(Phase.SETTLE, load.snapshot().phase());
	}
}
