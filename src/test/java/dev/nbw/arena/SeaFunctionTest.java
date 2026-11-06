package dev.nbw.arena;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.nbw.domain.arena.SeaAudit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随包发布的海域地形 function 必须与 arena JSON 同源。
 *
 * <p>JSON 由人手写、地形表由 {@code tools/gen_sea_function.py} 生成，两者一旦漂移，
 * 玩家就会被传到"配置说有海、世界上是石头"的坐标上。这里在单测层面先把这笔账算死，
 * 运行时的 {@code /cww arena terrain} 只是补一份现场读数。
 */
class SeaFunctionTest {

	private static final List<String> MAPS = List.of("default_sea", "test_sea");
	private static final int TICK_BUDGET = 4096;

	private record Entry(String kind, int x0, int x1, int z, int z1, int y0, int y1, String block) {
		int blocks() {
			return (x1 - x0 + 1) * (y1 - y0 + 1);
		}

		/** 下票/撤票的身份：区块的两个角，四个数都要参与比较。 */
		String footprint() {
			return x0 + ":" + z + ":" + x1 + ":" + z1;
		}
	}

	private static Path arenaJsonDir() {
		var url = Objects.requireNonNull(SeaFunctionTest.class.getResource("/data/nbw/arena"),
				"resources 没进 classpath");
		try {
			return Path.of(url.toURI());
		} catch (Exception e) {
			throw new AssertionError("内置 arena 目录不可遍历：" + url, e);
		}
	}

	private static Path funcDir(String id) {
		var url = Objects.requireNonNull(SeaFunctionTest.class.getResource("/data/nbw/function/arena/" + id),
				"找不到内置地形 function 目录 nbw:arena/" + id + " —— 跑 python tools/gen_sea_function.py");
		try {
			return Path.of(url.toURI());
		} catch (Exception e) {
			throw new AssertionError("地形 function 目录不可遍历：" + url, e);
		}
	}

	private static JsonObject arena(String id) {
		try {
			return new Gson().fromJson(Files.readString(arenaJsonDir().resolve(id + ".json")), JsonObject.class);
		} catch (IOException e) {
			throw new AssertionError("读不到内置 arena " + id, e);
		}
	}

	private static List<Entry> queue(String id, String file) {
		Pattern p = Pattern.compile(
				"^data modify storage nbw:sea_(\\S+) rows append value \\{(load|fill|unload):\\{(.*)\\}\\}$");
		List<Entry> out = new ArrayList<>();
		try {
			for (String line : Files.readAllLines(funcDir(id).resolve(file))) {
				if (line.startsWith("#") || line.isBlank()) {
					continue;
				}
				Matcher m = p.matcher(line);
				if (!m.matches()) {
					throw new AssertionError(id + "/" + file + " 出现看不懂的行：" + line);
				}
				assertEquals(id, m.group(1), "storage id 必须与图 id 一致，否则两张图会共用一条队列");
				out.add(parse(m.group(2), m.group(3)));
			}
		} catch (IOException e) {
			throw new AssertionError("读不到 " + id + "/" + file, e);
		}
		assertTrue(!out.isEmpty(), id + "/" + file + " 是空的 —— 空队列会让下面所有断言恒真");
		return out;
	}

	private static Entry parse(String kind, String body) {
		int x0 = 0, x1 = 0, z = 0, z1 = 0, y0 = 0, y1 = 0;
		String block = "";
		for (String kv : body.split(",")) {
			String[] parts = kv.split(":", 2);
			String v = parts[1].replace("\"", "");
			switch (parts[0]) {
				case "x0" -> x0 = Integer.parseInt(v);
				case "x1" -> x1 = Integer.parseInt(v);
				// 逐区块下票：load/unload 用区块的两个角（z0..z1），fill 用单一 z
				case "z" -> z = Integer.parseInt(v);
				case "z0" -> z = Integer.parseInt(v);
				case "z1" -> z1 = Integer.parseInt(v);
				case "y0" -> y0 = Integer.parseInt(v);
				case "y1" -> y1 = Integer.parseInt(v);
				case "block" -> block = v;
				default -> throw new AssertionError("意外的宏参数键：" + parts[0]);
			}
		}
		return new Entry(kind, x0, x1, z, z1, y0, y1, block);
	}

	@Test
	void buildQueueMatchesArenaGeometry() {
		for (String id : MAPS) {
			JsonObject a = arena(id);
			JsonObject sea = a.getAsJsonObject("sea");
			int cx = sea.get("centerX").getAsInt();
			int cz = sea.get("centerZ").getAsInt();
			int r = sea.get("radius").getAsInt();
			int water = sea.get("waterLevel").getAsInt();
			JsonObject b = a.getAsJsonObject("bounds");

			List<Entry> all = queue(id, "queue_build.mcfunction");
			List<Entry> loads = all.stream().filter(e -> e.kind().equals("load")).toList();
			List<Entry> fills = all.stream().filter(e -> e.kind().equals("fill")).toList();
			int rows = 2 * r + 1;
			int chunks = chunksAcross(cx, r) * chunksAcross(cz, r);
			assertEquals(chunks, loads.size(), id + " 下票必须逐区块（按行下票会一次拉起整行区块，实测拖住服务端 2157ms）");
			assertEquals(3 * rows, fills.size(), id + " 每行水/床/空三笔");

			for (Entry e : fills) {
				assertTrue(e.x0() >= b.get("minX").getAsInt() && e.x1() < b.get("maxX").getAsInt()
								&& e.z() >= b.get("minZ").getAsInt() && e.z() < b.get("maxZ").getAsInt(),
						id + " 地形超出 bounds：" + e);
				assertTrue(e.y0() >= b.get("minY").getAsInt() && e.y1() < b.get("maxY").getAsInt(),
						id + " 分层越过 bounds 的 y 范围：" + e);
				assertTrue(e.blocks() <= TICK_BUDGET, id + " 单 tick 写块超预算(" + e.blocks() + ")：" + e);
				assertTrue(e.blocks() <= 32768, id + " 单笔超过原版 fill 上限 32768：" + e);

				int dz = e.z() - cz;
				int half = (int) Math.floor(Math.sqrt((double) r * r - (double) dz * dz));
				assertEquals(cx - half, e.x0(), id + " 行宽与圆方程不符：" + e);
				assertEquals(cx + half, e.x1(), id + " 行宽与圆方程不符：" + e);

				switch (e.block()) {
					case "minecraft:water" -> {
						assertEquals(water, e.y1(), id + " 水面必须正好落在配置的 waterLevel");
						assertEquals(water - SeaAudit.WATER_DEPTH + 1, e.y0(), id + " 水层厚度与 SeaAudit 口径不符");
					}
					case "minecraft:stone" -> {
						assertEquals(water - SeaAudit.WATER_DEPTH, e.y1(), id + " 海床顶必须等于 SeaAudit 的海拔口径");
						assertEquals(e.y1() - SeaAudit.FLOOR_DEPTH + 1, e.y0(), id + " 海床厚度不符");
					}
					case "minecraft:air" -> {
						assertEquals(water + 1, e.y0(), id + " 清空必须从水面上方一格开始");
						assertEquals(water + SeaAudit.AIR_CLEAR, e.y1(), id + " 净空高度不符");
					}
					default -> throw new AssertionError(id + " 出现预期外的方块：" + e.block());
				}
			}
		}
	}

	@Test
	void releaseQueueUnforcesExactlyWhatBuildForced() {
		for (String id : MAPS) {
			List<Entry> loads = queue(id, "queue_build.mcfunction").stream()
					.filter(e -> e.kind().equals("load")).toList();
			List<Entry> unloads = queue(id, "queue_release.mcfunction").stream()
					.filter(e -> e.kind().equals("unload")).toList();
			Set<String> forced = new LinkedHashSet<>();
			for (Entry e : loads) {
				forced.add(e.footprint());
			}
			Set<String> released = new LinkedHashSet<>();
			for (Entry e : unloads) {
				released.add(e.footprint());
			}
			assertEquals(loads.size(), forced.size(), id + " 下票队列里有重复区块");
			// 撤票范围不等于下票范围 = 要么漏撤（区块永久钉住），要么多撤（把地图作者的票踩掉）
			assertEquals(forced, released, id + " 撤票行表与建海行表必须逐区块一一对应");
		}
	}

	@Test
	void stepTerminatesAndNeverReplaysTheLastEntry() {
		for (String id : MAPS) {
			String step = read(id, "step.mcfunction");
			assertTrue(step.contains("unless data storage nbw:sea_" + id + " rows[0] run function nbw:arena/"
							+ id + "/finish"),
					id + " 的收尾必须放在末尾判：队列跑完后循环不再排下一个 tick，放开头收尾永远触发不了");
			assertTrue(step.contains("execute if data storage nbw:sea_" + id + " job run data remove storage"),
					id + " 取完必须清 job，否则最后一笔会被下一个 tick 重放");
			assertTrue(step.contains("schedule function nbw:arena/" + id + "/step 1t replace"),
					id + " 必须用 replace 排队，避免重复触发 build 时同一个 tick 跑两笔");

			String build = read(id, "build.mcfunction");
			assertTrue(build.contains("run return fail"), id + " 队列非空时 build 不能清空重开");
		}
	}

	@Test
	void everyMapShipsTheWholeStateMachine() throws IOException {
		for (String id : MAPS) {
			for (String f : List.of("build", "step", "finish", "queue_build", "queue_release", "release",
					"resume", "abort", "status", "macro_load", "macro_fill", "macro_unload")) {
				Path p = funcDir(id).resolve(f + ".mcfunction");
				assertTrue(Files.isRegularFile(p), id + " 缺 " + f + ".mcfunction");
				String content = Files.readString(p);
				assertTrue(!content.isBlank(), id + "/" + f + " 是空文件");
				if (!f.startsWith("macro_")) {
					// 宏文件只吃参数、不认图；其余都必须指向自己的 storage，
					// 写成别人的 id 就是两张图共用一条队列 —— 建海建到一半去建另一张会互相踩
					assertTrue(content.contains("nbw:sea_" + id), id + "/" + f + " 没有引用自己的 storage id");
				}
			}
		}
	}

	@Test
	void everyTellrawComponentArrayParses() throws IOException {
		for (String id : MAPS) {
			for (String f : List.of("build", "step", "finish", "release", "resume", "abort", "status")) {
				for (String line : Files.readAllLines(funcDir(id).resolve(f + ".mcfunction"))) {
					if (line.startsWith("#") || !line.contains("tellraw")) {
						continue;
					}
					if (!line.contains("tellraw @a ")) {
						throw new AssertionError(id + "/" + f + " 的 tellraw 写法不符合预期：" + line);
					}
					// 锚在 "tellraw @a " 之后：组件文案里本身就带 [nbw]，用第一个左括号会取错片段
					String json = line.split("tellraw @a ", 2)[1];
					JsonElement parsed;
					try {
						parsed = new Gson().fromJson(json, JsonElement.class);
					} catch (Exception e) {
						throw new AssertionError(id + "/" + f + " tellraw JSON 解析失败：" + json, e);
					}
					assertTrue(parsed != null && parsed.isJsonArray(),
							() -> id + "/" + f + " 的 tellraw 不是一个组件数组：" + json);
					// 服务端在 1.21.1 只认裸 `return fail`；写成 return fail 0 会让整个函数加载失败
					assertFalse(line.contains("return fail 0"), () -> "非法 return 语法：" + line);
				}
			}
		}
	}

	@Test
	void everyCommandLineStartsWithKnownKeyword() throws IOException {
		// 静态能查的最硬一条：每条命令行要么以已知关键字开头，要么是注释 ——
		// datapack 的 "Whilst parsing command" 是在服务端加载时才炸，先在这里拦一层
		for (String id : MAPS) {
			try (Stream<Path> s = Files.list(funcDir(id))) {
				for (Path p : s.toList()) {
					for (String line : Files.readAllLines(p)) {
						if (line.isBlank() || line.startsWith("#")) {
							continue;
						}
						String head = line.startsWith("$") ? line.substring(1) : line;
						String first = head.split(" ")[0];
						assertTrue(List.of("data", "execute", "function", "schedule", "tellraw", "return",
								"fill", "forceload").contains(first), () -> p + " 出现无法识别的命令开头：" + line);
					}
				}
			}
		}
	}

	/** 一条轴 [center-r, center+r] 覆盖到多少个区块（floor 除法，负坐标也算对）。 */
	private static int chunksAcross(int center, int radius) {
		int lo = Math.floorDiv(center - radius, 16);
		int hi = Math.floorDiv(center + radius, 16);
		return hi - lo + 1;
	}

	private static String read(String id, String file) {
		try {
			return Files.readString(funcDir(id).resolve(file));
		} catch (IOException e) {
			throw new AssertionError(id + "/" + file + " 读不到", e);
		}
	}
}
