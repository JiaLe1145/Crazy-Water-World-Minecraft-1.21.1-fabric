package dev.nbw.arena;

import dev.nbw.cfg.ConfigModels;
import dev.nbw.cfg.ConfigService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 地图随机池（2026-11-08，参照 RicordRailExpress 的地图注册表 + 随机抽取）。
 *
 * <p>与参考项目的区别：它是两层（注册表 + 实例），我们只有一层 arena JSON——
 * 注册表的元数据（displayName / canSelect / weight / minPlayers / maxPlayers / formats）
 * 直接写进 arena JSON 顶层。这样既保留了"随机池 + 管理员可指定"，又不用多一份注册表文件。
 *
 * <p>数据来源：{@link BuiltinArenaJson#IDS} 列出的内置图 + {@code world/cww/arenas/} 的本地覆盖，
 * 走同一个 {@link ConfigService#loadArena}（world 覆盖优先、只读、拒绝即不加载）。
 */
public final class ArenaPool {

	/** 一条池条目（从 arena JSON 的元数据提取）。 */
	public record Entry(String id, String displayName, String description,
			int weight, int minPlayers, int maxPlayers, boolean canSelect, List<String> formats) {

		public boolean supportsFormat(String formatId) {
			return formats == null || formats.isEmpty() || formats.contains(formatId);
		}
	}

	private ArenaPool() {
	}

	/** 读取全部内置图 + 元数据；坏图直接跳过（不能因为一张坏图让整池不可用）。 */
	public static List<Entry> load(Path worldRoot) {
		List<Entry> out = new ArrayList<>();
		for (String id : BuiltinArenaJson.IDS) {
			ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot, id, false, BuiltinArenaJson.SOURCE);
			if (!loaded.ok() || loaded.arena() == null) {
				continue;
			}
			ConfigModels.Arena a = loaded.arena();
			String display = a.displayName == null || a.displayName.isBlank() ? id : a.displayName;
			int weight = a.weight == null ? 1 : a.weight;
			int minP = a.minPlayers == null ? 0 : a.minPlayers;
			int maxP = a.maxPlayers == null ? -1 : a.maxPlayers;
			boolean canSelect = a.canSelect == null || a.canSelect;
			out.add(new Entry(id, display, a.description, weight, minP, maxP, canSelect, a.formats));
		}
		return out;
	}

	/**
	 * 从池里随机挑一张图。
	 *
	 * @param formatId    本局赛制（用于过滤 {@code formats}）
	 * @param playerCount 本局报名人数（0 = 不按人数过滤）
	 * @return 选中的 arena id；没有可用图时返回 null
	 */
	public static String random(Path worldRoot, String formatId, int playerCount, Random random) {
		return pick(load(worldRoot), formatId, playerCount, random);
	}

	/**
	 * 纯函数版：从一个已知条目列表里按权重抽一张。单独抽出来是为了单测（不需要真世界）。
	 *
	 * @return 选中的 arena id；没有可用图时返回 null
	 */
	public static String pick(List<Entry> entries, String formatId, int playerCount, Random random) {
		List<Entry> pool = new ArrayList<>();
		int totalWeight = 0;
		for (Entry e : entries) {
			if (!e.canSelect() || e.weight() <= 0) {
				continue;
			}
			if (!e.supportsFormat(formatId)) {
				continue;
			}
			if (playerCount > 0 && e.minPlayers() > 0 && e.minPlayers() > playerCount) {
				continue;
			}
			if (playerCount > 0 && e.maxPlayers() > 0 && playerCount > e.maxPlayers()) {
				continue;
			}
			pool.add(e);
			totalWeight += e.weight();
		}
		if (pool.isEmpty()) {
			return null;
		}
		int roll = random.nextInt(totalWeight);
		for (Entry e : pool) {
			roll -= e.weight();
			if (roll < 0) {
				return e.id();
			}
		}
		return pool.get(pool.size() - 1).id();
	}

	/** 一行可读的池清单（给 {@code /cww arena list}）。 */
	public static String describe(Path worldRoot) {
		List<Entry> all = load(worldRoot);
		if (all.isEmpty()) {
			return "[arena] 地图池为空";
		}
		StringBuilder sb = new StringBuilder("[arena] 地图池 ").append(all.size()).append(" 张：");
		for (Entry e : all) {
			sb.append(String.format(Locale.ROOT, "\n  %s（%s）权重=%d 人数=%s 可选=%s 赛制=%s",
					e.id(), e.displayName(), e.weight(),
					e.minPlayers() + ".." + (e.maxPlayers() < 0 ? "∞" : e.maxPlayers()),
					e.canSelect(), (e.formats() == null || e.formats().isEmpty()) ? "全部" : e.formats()));
		}
		return sb.toString();
	}
}
