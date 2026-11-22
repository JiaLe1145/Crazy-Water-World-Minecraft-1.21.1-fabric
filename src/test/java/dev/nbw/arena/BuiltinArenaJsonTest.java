package dev.nbw.arena;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置图清单（{@link BuiltinArenaJson#IDS}）必须与 resources 下真实存在的文件一一对应。
 *
 * <p><b>为什么补这条测试：</b>2026-11-21 的全量测试里发现 IDS 只写了
 * {@code default_sea / test_sea} 两张，而 {@code data/nbw/arena/} 下其实有 6 张。
 * 直接后果是 {@code /cww arena list} 漏报 4 张图、也永远报不出"这 4 张被 world/cww
 * 同名文件覆盖"（覆盖关系对排查"改了内置图却不生效"很关键）。原注释声称有单测兜这条，
 * 但那条测试并不存在 —— 所以这里把它真的写出来：漏一个 id 就红。
 *
 * <p>反向也兜：IDS 里写了一个 resources 里不存在的 id 同样红（否则是永远读不到的幽灵条目）。
 */
class BuiltinArenaJsonTest {

	/** 内置 arena 目录（classpath 定位，dev 与 jar 两种形态都能解析成目录）。 */
	private static Path arenaJsonDir() {
		URL url = Objects.requireNonNull(BuiltinArenaJsonTest.class.getResource("/data/nbw/arena"),
				"resources 没进 classpath：/data/nbw/arena 找不到");
		try {
			return Path.of(url.toURI());
		} catch (Exception e) {
			throw new AssertionError("内置 arena 目录不可遍历：" + url, e);
		}
	}

	/** resources 下实际存在的图 id（按文件名去 .json）。 */
	private static List<String> resourceIds() throws IOException {
		Set<String> ids = new LinkedHashSet<>();
		try (Stream<Path> s = Files.list(arenaJsonDir())) {
			s.filter(Files::isRegularFile)
					.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".json"))
					.map(n -> n.substring(0, n.length() - ".json".length()))
					.sorted()
					.forEach(ids::add);
		}
		return new ArrayList<>(ids);
	}

	@Test
	void idsMatchResourcesExactly() throws IOException {
		List<String> onDisk = resourceIds();
		assertFalse(onDisk.isEmpty(), "resources 下一张内置图都没有，测试本身失效了");
		List<String> declared = new ArrayList<>(BuiltinArenaJson.IDS);
		declared.sort(null);
		assertEquals(onDisk, declared,
				"BuiltinArenaJson.IDS 与 data/nbw/arena/*.json 不一致："
						+ "磁盘=" + onDisk + " 声明=" + declared);
	}

	@Test
	void idsHaveNoDuplicates() {
		assertEquals(BuiltinArenaJson.IDS.size(), new LinkedHashSet<>(BuiltinArenaJson.IDS).size(),
				"IDS 里有重复 id：" + BuiltinArenaJson.IDS);
	}

	/**
	 * 每张内置图都必须能被 classloader 读到，且 {@code id} 字段与文件名一致。
	 *
	 * <p>后者曾经是真 bug 的温床：{@code /cww arena test <文件名>} 按文件名找文件，
	 * 但图内 {@code id} 用于日志与状态落盘，两者不一致就会出现"读的是 A、写的是 B"。
	 */
	@Test
	void everyBuiltinArenaIsReadableAndSelfConsistent() {
		for (String id : BuiltinArenaJson.IDS) {
			var text = BuiltinArenaJson.SOURCE.readBuiltin(id);
			assertTrue(text.isPresent(), "内置图 '" + id + "' 读不到（classloader 返回空）");
			assertFalse(text.get().isBlank(), "内置图 '" + id + "' 内容为空");
			JsonObject doc = JsonParser.parseString(text.get()).getAsJsonObject();
			assertNotNull(doc.get("id"), "内置图 '" + id + "' 缺 id 字段");
			assertEquals(id, doc.get("id").getAsString(),
					"内置图文件名 '" + id + "' 与图内 id 不一致");
			assertEquals(1, doc.get("schemaVersion").getAsInt(), "内置图 '" + id + "' schemaVersion 必须是 1");
		}
	}

	/**
	 * 测试夹具图不得进随机池：{@code canSelect} 必须显式为 false。
	 *
	 * <p>这张断言是 2026-11-21 扩 IDS 时的护栏 —— 扩表前 {@code flat_test} /
	 * {@code test_side7} 没写 {@code canSelect}（缺省为 true），一旦把它们加进 IDS
	 * 就会被 {@link ArenaPool#random} 抽到真实对局里。夹具图必须自己声明不可选。
	 */
	@Test
	void fixtureMapsAreNotSelectable() {
		List<String> fixtures = List.of("flat_test", "test_build", "test_sail7", "test_sea", "test_side7");
		for (String id : fixtures) {
			var text = BuiltinArenaJson.SOURCE.readBuiltin(id).orElseThrow();
			JsonObject doc = JsonParser.parseString(text).getAsJsonObject();
			assertTrue(doc.has("canSelect"), "夹具图 '" + id + "' 必须显式写 canSelect:false（缺省是 true，会被随机抽到）");
			assertFalse(doc.get("canSelect").getAsBoolean(), "夹具图 '" + id + "' 的 canSelect 必须是 false");
			if (doc.has("weight")) {
				assertEquals(0, doc.get("weight").getAsInt(), "夹具图 '" + id + "' 的 weight 必须是 0");
			}
		}
	}

	/** 真赛图（default_sea）必须可选，否则随机开不出正式对局。 */
	@Test
	void productionMapIsSelectable() {
		var text = BuiltinArenaJson.SOURCE.readBuiltin("default_sea").orElseThrow();
		JsonObject doc = JsonParser.parseString(text).getAsJsonObject();
		boolean canSelect = !doc.has("canSelect") || doc.get("canSelect").getAsBoolean();
		assertTrue(canSelect, "default_sea 必须是可选的正式赛图");
		assertTrue(doc.has("weight") && doc.get("weight").getAsInt() > 0,
				"default_sea 必须给正权重，否则随机开不出对局");
	}

	/**
	 * 出生锚点的 Y 必须等于 {@code sea.waterLevel}（= 水线那一格 = 筏底基准）。
	 *
	 * <p><b>这条是 2026-11-22"开局掉水里"的直接护栏。</b>
	 * 锚点 Y 有两种错误写法，两种都真出现过：
	 * <ul>
	 *   <li>写成 {@code waterLevel+1}（自动锚点的旧口径）：筏子比水面高一格，开局先自由落体；</li>
	 *   <li>写成 {@code generateY+4}（2026-11-21 为了"别掉水"抬的）：筏子凭空出现在水面之上
	 *       三四格，落水要好几秒 —— 用户看到的就是"人先掉水里，木筏过一会儿才浮上来"。</li>
	 * </ul>
	 * 锚点 Y 的语义是"筏底基准"：筏体生成在 {@code y+1}、甲板占 {@code y+1..y+2}。
	 * 玩家的落点由 {@code RaftSpawner.entryPoint} 按 plot 局部坐标算，不再复用这个 Y。
	 */
	@Test
	void teamSpawnYIsTheWaterLineNotThePlayerFeet() {
		for (String id : BuiltinArenaJson.IDS) {
			var text = BuiltinArenaJson.SOURCE.readBuiltin(id).orElseThrow();
			JsonObject doc = JsonParser.parseString(text).getAsJsonObject();
			int waterLevel = doc.getAsJsonObject("sea").get("waterLevel").getAsInt();
			if (!doc.has("spawns") || !doc.getAsJsonObject("spawns").has("teams")) {
				continue;
			}
			var teams = doc.getAsJsonObject("spawns").getAsJsonArray("teams");
			for (var el : teams) {
				JsonObject t = el.getAsJsonObject();
				assertEquals(waterLevel, t.get("y").getAsInt(),
						"图 '" + id + "' 队 " + t.get("teamIndex").getAsInt()
								+ " 的出生锚点 Y 必须等于 waterLevel（筏底基准），当前 "
								+ t.get("y").getAsInt() + " vs " + waterLevel);
			}
		}
	}
}
