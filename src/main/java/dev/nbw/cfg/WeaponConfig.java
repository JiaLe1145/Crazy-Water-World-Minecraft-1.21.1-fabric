package dev.nbw.cfg;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.nbw.domain.combat.CombatRule.Kind;
import dev.nbw.domain.combat.CombatRule.WeaponSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 武器表加载（09 §2 + §4）。两条来源，优先级与 arena 一致：
 * {@code <world>/cww/weapons.json} 存在就用它（运维的本地覆盖压过内置表），
 * 否则回退 jar 内置 {@code data/nbw/weapons.json}。
 * 两条路径走同一套校验；校验失败一律拒绝加载、绝不自动纠正（与 {@link ConfigService} 同一纪律）。
 */
public final class WeaponConfig {

	private static final Gson GSON = new Gson();

	public record Loaded(Map<String, WeaponSpec> byId, List<String> errors) {
		public boolean ok() {
			return errors.isEmpty();
		}

		public WeaponSpec spec(String id) {
			return byId.get(id);
		}

		public List<WeaponSpec> specs() {
			return new ArrayList<>(byId.values());
		}
	}

	private WeaponConfig() {
	}

	/**
	 * 读武器表并逐条校验。返回的 {@link Loaded} 里 {@code byId} 为空 + errors 非空表示拒绝。
	 */
	public static Loaded load(Path worldRoot, WeaponJsonSource builtin) {
		Path dir = worldRoot.resolve("cww");
		Path file = dir.resolve("weapons.json");
		if (!file.startsWith(dir)) {
			return rejected("路径越界，拒绝读取：" + file);
		}
		if (Files.isRegularFile(file)) {
			String text;
			try {
				text = Files.readString(file);
			} catch (IOException e) {
				return rejected("读取失败：" + e.getMessage());
			}
			return parse(text);
		}
		return builtin(builtin);
	}

	/**
	 * 只读 jar 内置表（不进 world 覆盖）。给 {@code content/NbwWeapons} 注册物品时取"默认数值"用——
	 * 物品的伤害/攻速 attribute modifier 必须在注册那一刻定死，而内置表正是那份随包发布的默认值。
	 */
	public static Loaded builtin(WeaponJsonSource source) {
		Optional<String> embedded = source.readBuiltin();
		if (embedded.isEmpty()) {
			return rejected("找不到 weapons.json（内置表里也没有）");
		}
		return parse(embedded.get());
	}

	private static Loaded rejected(String error) {
		return new Loaded(Map.of(), List.of(error));
	}

	private static Loaded parse(String text) {
		List<String> errors = new ArrayList<>();
		ConfigModels.WeaponsFile file;
		try {
			file = GSON.fromJson(text, ConfigModels.WeaponsFile.class);
		} catch (JsonParseException e) {
			return rejected("weapons.json 解析失败：" + e.getMessage());
		}
		if (file == null) {
			return rejected("weapons.json 内容为空");
		}
		if (file.schemaVersion != 1) {
			errors.add("schemaVersion 必须是 1，当前 " + file.schemaVersion);
		}
		Map<String, WeaponSpec> byId = new LinkedHashMap<>();
		if (file.weapons == null || file.weapons.isEmpty()) {
			errors.add("weapons 列表为空（至少要有一件武器）");
		} else {
			for (ConfigModels.Weapon w : file.weapons) {
				Kind kind = parseKind(w.kind);
				WeaponSpec spec = new WeaponSpec(w.id, kind, w.damage, w.cooldownTicks, w.knockback, w.coreMultiplier);
				errors.addAll(spec.errors());
				if (spec.id() != null && byId.putIfAbsent(spec.id(), spec) != null) {
					errors.add("武器 id 重复：" + spec.id());
				}
			}
		}
		return new Loaded(byId, errors);
	}

	private static Kind parseKind(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return Kind.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;   // null 会被 WeaponSpec.errors() 当成"缺 kind"报出来
		}
	}
}
