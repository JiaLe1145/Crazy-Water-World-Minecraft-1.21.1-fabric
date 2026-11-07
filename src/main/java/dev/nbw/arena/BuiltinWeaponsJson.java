package dev.nbw.arena;

import dev.nbw.NBW;
import dev.nbw.cfg.WeaponJsonSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 随 jar 发布的内置武器表（09 §2 + §4：模组对 world/cww 只读，所以默认表不落盘、只能内置）。
 *
 * <p>用 classloader 而不是 {@code ModContainer::getPath}，理由与 {@link BuiltinArenaJson} 相同：
 * {@code getResourceAsStream} 在开发环境与打进 jar 两种形态下走同一条路。
 */
public final class BuiltinWeaponsJson {

	private static final String PATH = "/data/nbw/weapons.json";

	public static final WeaponJsonSource SOURCE = BuiltinWeaponsJson::read;

	private BuiltinWeaponsJson() {
	}

	private static Optional<String> read() {
		try (InputStream in = BuiltinWeaponsJson.class.getResourceAsStream(PATH)) {
			return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			NBW.LOGGER.warn("[weapons] 内置武器表读取失败：{}", e.getMessage());
			return Optional.of("");
		}
	}
}
