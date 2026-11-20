package dev.nbw.arena;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigJsonSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 随 jar 发布的内置配置（大厅 / 赛制），供 {@link dev.nbw.cfg.ConfigService} 在
 * {@code world/cww} 缺文件时兜底 —— 保证"全新安装的服也能直接 /cww start"。
 *
 * <p>用 classloader 而不是 {@code ModContainer::getPath}：后者在开发环境是目录、
 * 打包 jar 是压缩路径，{@code resolve} 的行为两边不一致；{@code getResourceAsStream} 两种情况同一条路。
 */
public final class BuiltinConfigJson {

	private static final String BASE = "/data/nbw/";

	public static final ConfigJsonSource SOURCE = BuiltinConfigJson::read;

	private BuiltinConfigJson() {
	}

	private static Optional<String> read(String relativePath) {
		if (relativePath == null || relativePath.isBlank() || relativePath.contains("..")) {
			return Optional.empty();
		}
		try (InputStream in = BuiltinConfigJson.class.getResourceAsStream(BASE + relativePath)) {
			return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			NBW.LOGGER.warn("[cfg] 内置配置 '{}' 读取失败：{}", relativePath, e.getMessage());
			// 空内容而不是 empty()：让上层报"内容为空"，别把"读坏了"说成"这个文件不存在"
			return Optional.of("");
		}
	}
}
