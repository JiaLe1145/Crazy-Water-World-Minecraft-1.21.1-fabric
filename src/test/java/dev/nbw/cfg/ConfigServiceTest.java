package dev.nbw.cfg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置层是长期服最容易出事故的地方：错误配置必须被拒绝且可定位，绝不能崩服（03 §6）。 */
class ConfigServiceTest {

	private static final String GOOD_ARENA = """
			{
			  "schemaVersion": 1,
			  "id": "default_sea",
			  "dimension": "minecraft:overworld",
			  "bounds": { "minX": -160, "minY": 40, "minZ": -160, "maxX": 160, "maxY": 72, "maxZ": 160 },
			  "sea": { "centerX": 0, "centerZ": 0, "radius": 140, "waterLevel": 62, "generateY": 63 },
			  "rings": [
				{ "fromRadius": 0,  "toRadius": 28,  "lootMultiplier": 2.5, "tier": 3 },
				{ "fromRadius": 28, "toRadius": 56,  "lootMultiplier": 2.0, "tier": 2 },
				{ "fromRadius": 56, "toRadius": 91,  "lootMultiplier": 1.5, "tier": 2 },
				{ "fromRadius": 91, "toRadius": 140, "lootMultiplier": 1.0, "tier": 1 }
			  ],
			  "spawns": {
				"teams": [
				  { "teamIndex": 0, "x": 0, "y": 63, "z": -112 },
				  { "teamIndex": 1, "x": 112, "y": 63, "z": 0 },
				  { "teamIndex": 2, "x": 0, "y": 63, "z": 112 },
				  { "teamIndex": 3, "x": -112, "y": 63, "z": 0 }
				],
				"auto": { "strategy": "equidistant", "minSeparation": 90 }
			  },
			  "loot": { "exclude": [], "candidates": "auto" },
			  "limits": { "maxRaftArea": 96, "maxBlocksPerRaft": 3200, "maxFacilities": 8, "buildYRange": [62, 80] }
			}
			""";

	private Path writeArena(Path worldRoot, String id, String json) throws IOException {
		Path dir = worldRoot.resolve("cww").resolve("arenas");
		Files.createDirectories(dir);
		Path file = dir.resolve(id + ".json");
		Files.writeString(file, json);
		return file;
	}

	@Test
	void goodArenaLoadsClean(@TempDir Path worldRoot) throws IOException {
		writeArena(worldRoot, "default_sea", GOOD_ARENA);
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "default_sea", true);
		assertTrue(loaded.ok(), () -> "不该有错误，实际：" + loaded.errors());
		assertEquals(140.0, loaded.zone().baseRadius(), 1e-9);
	}

	@Test
	void brokenJsonIsQuarantinedNotCrash(@TempDir Path worldRoot) throws IOException {
		Path file = writeArena(worldRoot, "bad_one", "{ this is not json ");
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "bad_one", true);
		assertFalse(loaded.ok());
		assertTrue(loaded.errors().get(0).contains("JSON 解析失败"));
		assertFalse(Files.exists(file), "坏文件必须被移走，不能留在 arenas/ 里反复报错");
		try (Stream<Path> s = Files.list(worldRoot.resolve("cww").resolve("_invalid"))) {
			assertEquals(1, s.count(), "应归档一份到 _invalid/");
		}
	}

	@Test
	void ringHoleIsReportedWithFieldLevelDetail(@TempDir Path worldRoot) throws IOException {
		String holey = GOOD_ARENA.replace("\"toRadius\": 56,", "\"toRadius\": 55,");
		writeArena(worldRoot, "holey_sea", holey);
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "holey_sea", true);
		assertFalse(loaded.ok());
		assertTrue(loaded.errors().stream().anyMatch(e -> e.contains("rings[2]") && e.contains("空洞")),
				() -> "错误要能定位到字段，实际：" + loaded.errors());
	}

	@Test
	void circleMustFitInsideBounds(@TempDir Path worldRoot) throws IOException {
		String tooBig = GOOD_ARENA.replace("\"radius\": 140", "\"radius\": 200");
		writeArena(worldRoot, "toobig_sea", tooBig);
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "toobig_sea", true);
		assertTrue(loaded.errors().stream().anyMatch(e -> e.contains("超出 bounds")),
				() -> "实际：" + loaded.errors());
	}

	@Test
	void spawnTooCloseToCenterIsRejected(@TempDir Path worldRoot) throws IOException {
		String centered = GOOD_ARENA.replace("\"z\": -112", "\"z\": -20");
		writeArena(worldRoot, "center_sea", centered);
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "center_sea", true);
		assertTrue(loaded.errors().stream().anyMatch(e -> e.contains("过近")), () -> "实际：" + loaded.errors());
	}

	@Test
	void unknownSchemaVersionIsRefusedNotGuessed(@TempDir Path worldRoot) throws IOException {
		String future = GOOD_ARENA.replace("\"schemaVersion\": 1", "\"schemaVersion\": 7");
		writeArena(worldRoot, "future_sea", future);
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "future_sea", true);
		assertTrue(loaded.errors().stream().anyMatch(e -> e.contains("schemaVersion")), () -> "实际：" + loaded.errors());
	}

	@Test
	void pathTraversalNamesAreRejected(@TempDir Path worldRoot) {
		for (String bad : List.of("../secret", "a/b", "A_bad", "", "name.with.dots", "x".repeat(33))) {
			assertFalse(ConfigService.isSafeName(bad), "应拒绝：" + bad);
		}
		assertTrue(ConfigService.isSafeName("default_sea"));
	}

	@Test
	void missingFileIsReportedWithoutThrowing(@TempDir Path worldRoot) {
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "not_there", true);
		assertFalse(loaded.ok());
		assertTrue(loaded.errors().get(0).contains("找不到 arena 文件"));
	}

	@Test
	void atomicWriteLeavesNoTempFile(@TempDir Path dir) throws IOException {
		Path target = dir.resolve("state").resolve("match.json");
		ConfigService.atomicWrite(target, "{\"ok\":true}");
		assertEquals("{\"ok\":true}", Files.readString(target));
		try (Stream<Path> s = Files.list(target.getParent())) {
			assertTrue(s.noneMatch(p -> p.toString().endsWith(".tmp")), "不应留下 .tmp 残file");
		}
	}
}
