package dev.nbw.cfg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 内置 arena JSON 的读取口。
 * cfg/ 不许依赖 Minecraft，所以 jar 内资源由外部（arena/BuiltinArenaJson）注入实现；
 * 单测用 {@link #fromDirectory} 指向 src/main/resources，直接校验随包发布的那两份真文件。
 */
@FunctionalInterface
public interface ArenaJsonSource {

	/** 缺内容=没有这张图；读到但坏了要返回 {@code Optional.of("")}，让上层报"内容为空"而不是"不存在"。 */
	Optional<String> readBuiltin(String id);

	ArenaJsonSource NONE = id -> Optional.empty();

	/** 从目录读 {@code <id>.json}。id 由调用方先过白名单，这里再复查一次，防目录穿越。 */
	static ArenaJsonSource fromDirectory(Path dir) {
		return id -> {
			if (!ConfigService.isSafeName(id)) {
				return Optional.empty();
			}
			Path file = dir.resolve(id + ".json");
			if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
				return Optional.empty();
			}
			try {
				return Optional.of(Files.readString(file));
			} catch (IOException e) {
				return Optional.of("");
			}
		};
	}
}
