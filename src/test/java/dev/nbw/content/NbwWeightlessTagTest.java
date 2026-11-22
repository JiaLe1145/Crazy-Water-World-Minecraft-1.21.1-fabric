package dev.nbw.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
 * 功能方块必须<b>零重量</b>（用户 2026-11-22："需要设为0重量，避免重量导致木筏出现下陷或者翻船"）。
 *
 * <p><b>为什么这条必须是测试，而不是"记得写"：</b>
 * Sable 会给每个方块算质量（`data/sable/physics_block_properties/*.json`，普通方块 1.0），
 * 我们的方块不在任何 sable 标签里，于是**默认按 1.0 算**。实测（`/setblock` 写进 plot 存储坐标后读 `cww raft all`）：
 * <pre>
 * nbw:ballista 11.5 -> 12.5   nbw:repair_table 14.5 -> 15.5   nbw:raft_core 21.5 -> 22.5
 * </pre>
 * 也就是"每摆一件设施，筏子就重 1.0" —— 4×4 甲板本身才 8.0，摆 8 件设施就翻倍：
 * 筏子吃水加深（实测 Y 63.03 → 62.94，再摆几件就到 62.5）、重心抬高、单件偏置直接把筏子压歪
 * （实测在角上放一块 2.0 质量的石头，倾斜从 2.4° 跳到 13.3°）。
 *
 * <p>修法：在 `data/sable/tags/block/weightless.json` 里把这 12 个方块登记进 Sable 的
 * `#sable:weightless` 标签（该标签对应的属性文件把 `sable:mass` 设成 0.0）。
 *
 * <p>这条测试用<b>资源文件</b>做交叉校验：`assets/nbw/blockstates/*.json` 里有一个方块，
 * 就必须在 weightless 标签里出现一次 —— 以后新增功能方块忘了登记，这里直接红。
 */
class NbwWeightlessTagTest {

	private static Path resourceDir(String path) {
		URL url = Objects.requireNonNull(NbwWeightlessTagTest.class.getResource(path),
				"classpath 里没有 " + path + "（资源没打包？）");
		try {
			return Path.of(url.toURI());
		} catch (Exception e) {
			throw new AssertionError("资源目录不可遍历：" + url, e);
		}
	}

	/** 本模组所有方块 id：`assets/nbw/blockstates/<id>.json` 的文件名。 */
	private static List<String> blockIds() throws IOException {
		Set<String> ids = new LinkedHashSet<>();
		try (Stream<Path> s = Files.list(resourceDir("/assets/nbw/blockstates"))) {
			s.filter(Files::isRegularFile)
					.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".json"))
					.map(n -> n.substring(0, n.length() - ".json".length()))
					.sorted()
					.forEach(ids::add);
		}
		return new ArrayList<>(ids);
	}

	private static JsonObject weightlessTag() throws IOException {
		Path file = resourceDir("/data/sable/tags/block").resolve("weightless.json");
		assertTrue(Files.isRegularFile(file), "缺少 data/sable/tags/block/weightless.json");
		return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	@Test
	void everyNbwBlockIsDeclaredWeightless() throws IOException {
		List<String> blocks = blockIds();
		assertFalse(blocks.isEmpty(), "一个 blockstate 都没扫到，测试本身失效了");

		JsonObject tag = weightlessTag();
		// replace=false 才能与 Sable 自带的 weightless 条目合并（Sable 自己写的就是 false）
		assertTrue(tag.has("replace") && !tag.get("replace").getAsBoolean(),
				"sable:weightless 的覆盖文件必须 replace:false，否则会把 Sable 自带的条目顶掉");

		Set<String> declared = new LinkedHashSet<>();
		JsonArray values = tag.getAsJsonArray("values");
		assertNotNull(values, "weightless 标签缺 values 数组");
		for (JsonElement el : values) {
			String id = el.isJsonObject() ? el.getAsJsonObject().get("id").getAsString() : el.getAsString();
			assertTrue(declared.add(id), "weightless 标签里有重复条目：" + id);
		}

		for (String id : blocks) {
			String full = "nbw:" + id;
			assertTrue(declared.contains(full),
					"功能方块 " + full + " 没有登记进 #sable:weightless —— 它会被按 1.0 质量算，"
							+ "摆到筏上就会让筏子下陷/压歪（见类注释里的实测数字）");
		}
		assertEquals(blocks.size(), declared.size(),
				"weightless 标签里的条目数应与本模组方块数一致（多了说明登记了不存在的方块）："
						+ "方块=" + blocks + " 标签=" + declared);
	}
}
