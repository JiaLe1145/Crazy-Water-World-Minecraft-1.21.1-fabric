package dev.nbw.arena;

import dev.nbw.NBW;
import dev.nbw.cfg.ArenaJsonSource;
import dev.nbw.cfg.ConfigService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 随 jar 发布的内置默认海域配置（03 §2 的目录约定里，模组对 world/cww 只读，所以默认图不能落盘、只能内置）。
 *
 * <p>用 classloader 而不是 {@code ModContainer::getPath}：后者在开发环境是目录、打进 jar 是压缩路径，
 * {@code resolve} 的行为两边不一致；{@code getResourceAsStream} 在两种运行形态下走同一条路。
 */
public final class BuiltinArenaJson {

	private static final String BASE = "/data/nbw/arena/";

	/**
	 * 全部内置图 id（与 {@code src/main/resources/data/nbw/arena/*.json} 一一对应）。
	 *
	 * <p>给 {@code /cww arena list} 与 {@link dev.nbw.arena.ArenaPool} 用。
	 * <b>2026-11-21 修</b>：这里原来只写了 {@code default_sea / test_sea} 两张，
	 * 而 resources 下其实有 6 张 —— 于是 {@code /cww arena list} 漏报 4 张、
	 * 也永远报不出"这 4 张被 world/cww 同名文件覆盖"，注释里声称的"对着 resources
	 * 兜底的单测"当时并不存在。现在补了 {@code BuiltinArenaJsonTest} 兜这条（漏一个 id 就红）。
	 *
	 * <p>注意：进不进随机池由各图自己的 {@code canSelect} / {@code weight} 决定，
	 * 与这张表无关 —— 测试图都写了 {@code canSelect:false, weight:0}。
	 */
	public static final List<String> IDS = List.of(
			"default_sea", "flat_test", "test_build", "test_sail7", "test_sea", "test_side7");

	public static final ArenaJsonSource SOURCE = BuiltinArenaJson::read;

	private BuiltinArenaJson() {
	}

	private static Optional<String> read(String id) {
		if (!ConfigService.isSafeName(id)) {
			return Optional.empty();
		}
		try (InputStream in = BuiltinArenaJson.class.getResourceAsStream(BASE + id + ".json")) {
			return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			NBW.LOGGER.warn("[arena] 内置默认图 '{}' 读取失败：{}", id, e.getMessage());
			// 空内容而不是 empty()：让上层报"内容为空"，别把"读坏了"说成"这张图不存在"
			return Optional.of("");
		}
	}
}
