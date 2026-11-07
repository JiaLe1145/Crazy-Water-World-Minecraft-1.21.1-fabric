package dev.nbw.cfg;

import java.util.Optional;

/**
 * 内置 weapons.json 的读取口。
 * cfg/ 不许依赖 Minecraft，所以 jar 内资源由外部（arena/BuiltinWeaponsJson）注入实现；
 * 与 {@link ArenaJsonSource} 同一思路，但武器表文件名固定、没有 id，只需一个读口。
 */
@FunctionalInterface
public interface WeaponJsonSource {

	/** 缺内容 = 没有内置表；读到但坏了要返回 {@code Optional.of("")}，让上层报"内容为空"而不是"不存在"。 */
	Optional<String> readBuiltin();

	WeaponJsonSource NONE = Optional::empty;
}
