package dev.nbw.cfg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "default_sea", true, ArenaJsonSource.NONE);
		assertTrue(loaded.ok(), () -> "不该有错误，实际：" + loaded.errors());
		assertEquals(140.0, loaded.zone().baseRadius(), 1e-9);
	}

	@Test
	void brokenJsonIsQuarantinedNotCrash(@TempDir Path worldRoot) throws IOException {
		Path file = writeArena(worldRoot, "bad_one", "{ this is not json ");
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "bad_one", true, ArenaJsonSource.NONE);
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
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "holey_sea", true, ArenaJsonSource.NONE);
		assertFalse(loaded.ok());
		assertTrue(loaded.errors().stream().anyMatch(e -> e.contains("rings[2]") && e.contains("空洞")),
				() -> "错误要能定位到字段，实际：" + loaded.errors());
	}

	@Test
	void circleMustFitInsideBounds(@TempDir Path worldRoot) throws IOException {
		String tooBig = GOOD_ARENA.replace("\"radius\": 140", "\"radius\": 200");
		writeArena(worldRoot, "toobig_sea", tooBig);
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "toobig_sea", true, ArenaJsonSource.NONE);
		assertTrue(loaded.errors().stream().anyMatch(e -> e.contains("超出 bounds")),
				() -> "实际：" + loaded.errors());
	}

	@Test
	void spawnTooCloseToCenterIsRejected(@TempDir Path worldRoot) throws IOException {
		String centered = GOOD_ARENA.replace("\"z\": -112", "\"z\": -20");
		writeArena(worldRoot, "center_sea", centered);
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "center_sea", true, ArenaJsonSource.NONE);
		assertTrue(loaded.errors().stream().anyMatch(e -> e.contains("过近")), () -> "实际：" + loaded.errors());
	}

	@Test
	void unknownSchemaVersionIsRefusedNotGuessed(@TempDir Path worldRoot) throws IOException {
		String future = GOOD_ARENA.replace("\"schemaVersion\": 1", "\"schemaVersion\": 7");
		writeArena(worldRoot, "future_sea", future);
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "future_sea", true, ArenaJsonSource.NONE);
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
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "not_there", true, ArenaJsonSource.NONE);
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

	// ---------------------------------------------------------------- 内置默认图

	/** 从测试 classpath 取随包发布的 arena 目录（Gradle 下是 build/resources/main，与 jar 内同一份内容）。 */
	private static Path shippedArenaDir() {
		var url = Objects.requireNonNull(ConfigServiceTest.class.getResource("/data/nbw/arena"),
				"classpath 上找不到 data/nbw/arena —— resources 没被正确处理，内置图根本没进包");
		try {
			return Path.of(url.toURI());
		} catch (Exception e) {
			throw new AssertionError("内置 arena 目录不是可遍历的路径（说明它只在 jar 里）：" + url, e);
		}
	}

	@Test
	void shippedDefaultsPassTheSameValidation(@TempDir Path emptyWorld) throws IOException {
		Path dir = shippedArenaDir();
		List<String> ids;
		try (Stream<Path> s = Files.list(dir)) {
			ids = s.filter(p -> p.toString().endsWith(".json"))
					.map(p -> p.getFileName().toString().replace(".json", ""))
					.sorted()
					.toList();
		}
		// 显式点名：目录被遍历成空集合时，下面的循环会"全过"，那是恒真的假绿
		assertTrue(ids.containsAll(List.of("default_sea", "test_sea")), () -> "内置图缺人：" + ids);

		ArenaJsonSource builtin = ArenaJsonSource.fromDirectory(dir);
		for (String id : ids) {
			ConfigService.Loaded loaded = ConfigService.loadArena(emptyWorld, id, true, builtin);
			assertTrue(loaded.ok(), () -> "随包默认图 " + id + " 必须能过同一套校验，实际：" + loaded.errors());
			assertEquals(ConfigService.Loaded.Source.BUILTIN, loaded.source(),
					"world 目录是空的，这张图只能来自内置");
			// 文件名 = id 是 03 §2 的硬约定：不一致时 /cww arena test <id> 找到的不是它自己
			assertEquals(id, loaded.arena().id, () -> "内置图文件名与 id 字段不一致：" + id);
		}
	}

	@Test
	void worldFileShadowsBuiltinEvenWhenBroken(@TempDir Path worldRoot) throws IOException {
		Path dir = shippedArenaDir();
		// 故意写一张坏的本地覆盖图：本地文件一旦存在就是权威，绝不能"这张坏了那就用内置的好图"，
		// 否则地图作者的错误配置会被静默吞掉，03 §6.2 的"必须显式暴露"当场失效。
		Path local = writeArena(worldRoot, "default_sea", GOOD_ARENA.replace("\"radius\": 140", "\"radius\": 400"));
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "default_sea", true,
				ArenaJsonSource.fromDirectory(dir));

		assertFalse(loaded.ok(), "本地坏图必须被拒绝，不许回退到内置好图");
		assertEquals(ConfigService.Loaded.Source.WORLD, loaded.source());
		assertTrue(loaded.errors().stream().anyMatch(e -> e.contains("超出 bounds")), () -> "实际：" + loaded.errors());
		assertFalse(Files.exists(local), "坏的本地图同样要归档");
	}

	@Test
	void builtinIsNeverQuarantined(@TempDir Path worldRoot) throws IOException {
		Path builtinDir = worldRoot.resolve("jarview");
		Files.createDirectories(builtinDir);
		Files.writeString(builtinDir.resolve("broken_one.json"), "{ this is not json ");

		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, "broken_one", true,
				ArenaJsonSource.fromDirectory(builtinDir));
		assertFalse(loaded.ok());
		assertEquals(ConfigService.Loaded.Source.BUILTIN, loaded.source());
		assertFalse(Files.exists(worldRoot.resolve("cww").resolve("_invalid")),
				"内置图在 jar 里挪不动，把'内置图坏了'伪装成'已归档'会把排查方向带偏");
	}
}
