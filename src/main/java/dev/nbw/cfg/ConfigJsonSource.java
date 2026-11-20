package dev.nbw.cfg;

import java.util.Optional;

/**
 * 内置配置 JSON 的读取口（lobby / format 等）。
 *
 * <p>与 {@link ArenaJsonSource} 同一套设计：{@code cfg/} 不许依赖 Minecraft，
 * 所以 jar 内资源由外部（{@code arena/BuiltinConfigJson}）注入实现。
 *
 * <p><b>为什么需要这个兜底</b>：{@code world/cww} 是"只读目录"，模组不往里写默认文件。
 * arena 与 weapons 早就有 jar 兜底，lobby 与 format 漏了 —— 结果是<b>全新安装的服起不来</b>
 * （2026-11-21 用干净世界首启实测：{@code format 'standard_12min' 读取失败}）。
 */
@FunctionalInterface
public interface ConfigJsonSource {

	/**
	 * @param relativePath 相对 {@code data/nbw/} 的路径，例如 {@code lobby.json}、{@code format/test_3min.json}
	 * @return 缺内容 → empty；读到但坏了 → {@code Optional.of("")}，让上层报"内容为空"而不是"不存在"
	 */
	Optional<String> readBuiltin(String relativePath);

	ConfigJsonSource NONE = path -> Optional.empty();
}
