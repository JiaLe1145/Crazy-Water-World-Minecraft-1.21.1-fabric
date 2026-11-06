package dev.nbw.cfg;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.nbw.domain.ZoneModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 配置加载与校验（03 §6）。三条硬规则：
 * 1. 校验失败一律拒绝加载并归档到 _invalid/，**绝不自动纠正**（03 §6.2）；
 * 2. 只允许固定子目录 + 白名单文件名，杜绝路径穿越（03 §6.3）；
 * 3. 写盘一律 .tmp -> rename，避免崩溃留下半截文件。
 */
public final class ConfigService {

	private static final Gson GSON = new Gson();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");
	private static final String NAME_PATTERN = "[a-z0-9_]{1,32}";

	public record Loaded(ConfigModels.Arena arena, ZoneModel zone, List<String> errors, Source source) {
		public boolean ok() {
			return errors.isEmpty();
		}

		/** 配置来自哪里。归档只对 WORLD 有意义：内置图在 jar 里，挪不动也不该挪。 */
		public enum Source {
			WORLD("world/cww/arenas 文件"),
			BUILTIN("jar 内置默认图");

			private final String label;

			Source(String label) {
				this.label = label;
			}

			public String label() {
				return label;
			}
		}
	}

	private ConfigService() {
	}

	/** 文件名白名单：只允许小写字母数字下划线，防 ../ 与绝对路径注入。 */
	public static boolean isSafeName(String name) {
		return name != null && name.matches(NAME_PATTERN);
	}

	/** 方块 id 允许命名空间：{@code oak_planks} 或 {@code minecraft:oak_planks}；空段与多段冒号一律拒绝。 */
	public static boolean isSafeBlockId(String raw) {
		if (raw == null || raw.isEmpty()) {
			return false;
		}
		String[] parts = raw.split(":");
		if (parts.length == 0 || parts.length > 2) {
			return false;
		}
		for (String part : parts) {
			if (!isSafeName(part)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 解析并校验单个 arena。两条来源，优先级是刻意的：
	 * {@code <world>/cww/arenas/<id>.json} 存在就用它（地图作者的本地覆盖永远压过内置图），
	 * 只有缺文件时才回退读 jar 内置默认图。
	 * 两条路径走同一套 {@link #validateArena}，内置图不享受"宽松"——否则默认图里的错误会随包分发到每台服。
	 * 失败时把文件移入 _invalid/ 并返回字段级错误；内置图不归档（在 jar 里挪不动）。
	 */
	public static Loaded loadArena(Path worldRoot, String id, boolean quarantineOnFailure, ArenaJsonSource builtin) {
		if (!isSafeName(id)) {
			return rejected("arena id 不合法（只允许 [a-z0-9_]{1,32}）：" + id);
		}
		Path dir = worldRoot.resolve("cww").resolve("arenas");
		Path file = dir.resolve(id + ".json");
		if (!file.startsWith(dir)) {
			return rejected("路径越界，拒绝读取：" + file);
		}
		if (Files.isRegularFile(file)) {
			String text;
			try {
				text = Files.readString(file);
			} catch (IOException e) {
				if (quarantineOnFailure) {
					quarantine(file);
				}
				return new Loaded(null, null, List.of("读取失败：" + e.getMessage()), Loaded.Source.WORLD);
			}
			return parse(text, file, quarantineOnFailure, Loaded.Source.WORLD);
		}
		// 本地没有这张图：用内置默认图顶上，这样新装的服不会因为缺配置而起不来。
		// quarantine 传 false —— 把"内置图坏了"伪装成"已归档"只会让人往错误方向找。
		Optional<String> embedded = builtin.readBuiltin(id);
		if (embedded.isPresent()) {
			return parse(embedded.get(), file, false, Loaded.Source.BUILTIN);
		}
		return rejected("找不到 arena 文件：" + file + "（内置默认图里也没有 '" + id + "'）");
	}

	private static Loaded rejected(String error) {
		return new Loaded(null, null, List.of(error), null);
	}

	/** 一条文本 → arena + 环带模型 + 字段级错误；两种来源共用，保证校验口径完全一致。 */
	private static Loaded parse(String text, Path file, boolean quarantineOnFailure, Loaded.Source source) {
		List<String> errors = new ArrayList<>();
		ConfigModels.Arena arena;
		try {
			arena = GSON.fromJson(text, ConfigModels.Arena.class);
		} catch (JsonParseException e) {
			errors.add("JSON 解析失败：" + e.getMessage());
			if (quarantineOnFailure) {
				quarantine(file);
			}
			return new Loaded(null, null, errors, source);
		}
		if (arena == null) {
			errors.add("文件内容为空");
			if (quarantineOnFailure) {
				quarantine(file);
			}
			return new Loaded(null, null, errors, source);
		}

		errors.addAll(validateArena(arena));
		// 此处只有 arena（format 未到），因此只校验环带；圈阶段在 loadFormat 侧校验。
		ZoneModel zone = new ZoneModel(List.of(), ringsOf(arena), arena.sea == null ? 0 : arena.sea.radius);
		errors.addAll(zone.validateRings());
		if (!errors.isEmpty() && quarantineOnFailure) {
			quarantine(file);
		}
		return new Loaded(arena, zone, errors, source);
	}

	/** 03 §6.1 的结构性校验（圈层与环带的数学部分在 ZoneModel.validate）。 */
	public static List<String> validateArena(ConfigModels.Arena a) {
		List<String> errors = new ArrayList<>();
		if (a.schemaVersion != 1) {
			errors.add("schemaVersion 必须是 1，当前 " + a.schemaVersion + "（不认识的版本直接拒绝，不猜测）");
		}
		if (!isSafeName(a.id)) {
			errors.add("id 不合法：" + a.id);
		}
		if (a.bounds == null || a.sea == null) {
			errors.add("bounds / sea 必填");
			return errors;
		}
		ConfigModels.Arena.Bounds b = a.bounds;
		if (b.minX >= b.maxX || b.minY >= b.maxY || b.minZ >= b.maxZ) {
			errors.add("bounds 必须满足 min < max（min 含、max 不含）");
		}
		if (a.sea.radius <= 0) {
			errors.add("sea.radius 必须 > 0");
		}
		int halfX = Math.min(b.maxX - a.sea.centerX, a.sea.centerX - b.minX);
		int halfZ = Math.min(b.maxZ - a.sea.centerZ, a.sea.centerZ - b.minZ);
		if (a.sea.radius > Math.min(halfX, halfZ)) {
			errors.add("sea.radius(" + a.sea.radius + ") 超出 bounds 可容纳半径(" + Math.min(halfX, halfZ) + ")，圆必须完整落在矩形内");
		}
		if (a.sea.generateY < b.minY || a.sea.generateY >= b.maxY) {
			errors.add("sea.generateY 必须落在 [minY, maxY)");
		}
		if (Math.abs(a.sea.generateY - a.sea.waterLevel) > 2) {
			errors.add("sea.generateY 与 waterLevel 相差不得超过 2，当前 " + a.sea.generateY + " vs " + a.sea.waterLevel);
		}
		if (a.loot != null && a.loot.exclude != null && b.maxX > b.minX) {
			long area = 0L;
			for (ConfigModels.Arena.Loot.Exclude e : a.loot.exclude) {
				area += (long) Math.max(0, e.maxX - e.minX) * Math.max(0, e.maxZ - e.minZ);
			}
			long total = (long) (b.maxX - b.minX) * (b.maxZ - b.minZ);
			if (total > 0 && area > total * 0.3) {
				errors.add("loot.exclude 覆盖超过可刷面积 30%，物资密度无法保证");
			}
		}
		if (a.limits == null || a.limits.maxRaftArea <= 0 || a.limits.maxBlocksPerRaft < 100
				|| a.limits.maxFacilities <= 0) {
			errors.add("limits 必填且 maxRaftArea>0、maxBlocksPerRaft>=100、maxFacilities>0");
		}
		if (a.limits != null) {
			// 只在"给了值"时约束：这两个字段是后加的，缺省必须继续能用
			if (a.limits.initialRaftRadius < 0 || a.limits.initialRaftRadius > 8) {
				errors.add("limits.initialRaftRadius 必须在 0..8（0=用默认值），实际 "
						+ a.limits.initialRaftRadius);
			}
			if (a.limits.initialRaftBlock != null && !a.limits.initialRaftBlock.isEmpty()
					&& !isSafeBlockId(a.limits.initialRaftBlock)) {
				errors.add("limits.initialRaftBlock 不是合法的方块 id：" + a.limits.initialRaftBlock);
			}
		}
		if (a.spawns != null && a.spawns.teams != null) {
			for (ConfigModels.Arena.Spawns.TeamSpawn t : a.spawns.teams) {
				double dist = Math.hypot(t.x - a.sea.centerX, t.z - a.sea.centerZ);
				if (dist > a.sea.radius) {
					errors.add("spawns.teams[teamIndex=" + t.teamIndex + "] 落在海域圆外");
				}
				if (dist < a.sea.radius * 0.5) {
					errors.add("spawns.teams[teamIndex=" + t.teamIndex + "] 距圈心过近（应 >= radius*0.5）");
				}
			}
			for (int i = 0; i < a.spawns.teams.size(); i++) {
				for (int j = i + 1; j < a.spawns.teams.size(); j++) {
					var p = a.spawns.teams.get(i);
					var q = a.spawns.teams.get(j);
					double d = Math.hypot(p.x - q.x, p.z - q.z);
					int min = a.spawns.auto == null ? 60 : Math.max(60, a.spawns.auto.minSeparation);
					if (d < min) {
						errors.add("出生点 " + p.teamIndex + "/" + q.teamIndex + " 间距 " + (int) d + " < " + min);
					}
				}
			}
		}
		return errors;
	}

	/** 由 arena + format 组装 domain 层的圈模型。 */
	public static ZoneModel toZone(ConfigModels.Arena a) {
		return null;
	}

	private static List<ZoneModel.Ring> ringsOf(ConfigModels.Arena a) {
		List<ZoneModel.Ring> rings = new ArrayList<>();
		if (a != null && a.rings != null) {
			for (ConfigModels.Arena.RingJson r : a.rings) {
				rings.add(new ZoneModel.Ring(r.fromRadius, r.toRadius, r.lootMultiplier, r.tier));
			}
		}
		return rings;
	}

	public static ZoneModel toZone(ConfigModels.Arena a, ConfigModels.Format f) {
		List<ZoneModel.Stage> stages = new ArrayList<>();
		if (f != null && f.border != null && f.border.stages != null) {
			for (int i = 0; i < f.border.stages.size(); i++) {
				ConfigModels.Format.Border.StageJson s = f.border.stages.get(i);
				int over = (f.duration != null && f.duration.phases != null && i < f.duration.phases.size())
							? f.duration.phases.get(i) : 45;
					stages.add(new ZoneModel.Stage(i, s.radiusScale, s.at, over, s.damage, s.warn));
			}
		}
		List<ZoneModel.Ring> rings = new ArrayList<>();
		if (a != null && a.rings != null) {
			for (ConfigModels.Arena.RingJson r : a.rings) {
				rings.add(new ZoneModel.Ring(r.fromRadius, r.toRadius, r.lootMultiplier, r.tier));
			}
		}
		return new ZoneModel(List.copyOf(stages), List.copyOf(rings), a == null ? 0 : a.sea.radius);
	}

	/** 大厅载入结果（03 §5）。校验失败只拒绝、不猜测纠正。 */
	public record LobbyLoaded(ConfigModels.Lobby lobby, List<String> errors) {
		public boolean ok() {
			return errors.isEmpty();
		}
	}

	/**
	 * 读取 &lt;world&gt;/cww/lobby.json。文件名固定，不做拼接，因此不存在路径穿越面；
	 * 但目录仍然要 startsWith 复查，防止以后有人把它改成带参数的命令。
	 */
	public static LobbyLoaded loadLobby(Path worldRoot, boolean quarantineOnFailure) {
		Path dir = worldRoot.resolve("cww");
		Path file = dir.resolve("lobby.json");
		if (!file.startsWith(dir)) {
			return new LobbyLoaded(null, List.of("路径越界，拒绝读取：" + file));
		}
		if (!Files.isRegularFile(file)) {
			return new LobbyLoaded(null, List.of("找不到大厅配置：" + file + "（03 §5 要求 world/cww/lobby.json）"));
		}
		ConfigModels.Lobby lobby;
		try {
			lobby = GSON.fromJson(Files.readString(file), ConfigModels.Lobby.class);
		} catch (JsonParseException | IOException e) {
			if (quarantineOnFailure) {
				quarantine(file);
			}
			return new LobbyLoaded(null, List.of("JSON 解析失败：" + e.getMessage()));
		}
		if (lobby == null) {
			return new LobbyLoaded(null, List.of("lobby.json 内容为空"));
		}
		List<String> errors = validateLobby(lobby);
		if (!errors.isEmpty() && quarantineOnFailure) {
			quarantine(file);
		}
		return new LobbyLoaded(lobby, errors);
	}

	/** 03 §5 + §6.1的大厅侧校验：点位必须落在大厅区域内，否则传送会把人甩到虚空。 */
	public static List<String> validateLobby(ConfigModels.Lobby l) {
		List<String> errors = new ArrayList<>();
		if (l.schemaVersion != 1) {
			errors.add("lobby.schemaVersion 必须是 1，当前 " + l.schemaVersion + "（不认识的版本直接拒绝）");
		}
		if (l.spawn == null) {
			errors.add("lobby.spawn 必填（x/y/z）");
		}
		if (l.bounds == null) {
			errors.add("lobby.bounds 必填（大厅是受控区域，不是一个点）");
			return errors;
		}
		ConfigModels.Arena.Bounds b = l.bounds;
		if (b.minX >= b.maxX || b.minY >= b.maxY || b.minZ >= b.maxZ) {
			errors.add("lobby.bounds 必须满足 min < max（min 含、max 不含）");
			return errors;
		}
		if (l.spawn != null && !inside(l.spawn, b)) {
			errors.add("lobby.spawn 必须落在 bounds 内：" + l.spawn.x + "," + l.spawn.y + "," + l.spawn.z);
		}
		if (l.enrollNpc != null && !inside(l.enrollNpc, b)) {
			errors.add("lobby.enrollNpc 必须落在 bounds 内");
		}
		if (l.teamFlags != null) {
			for (ConfigModels.Lobby.TeamFlag f : l.teamFlags) {
				if (f.teamIndex < 0) {
					errors.add("lobby.teamFlags[].teamIndex 不得为负");
				}
				ConfigModels.Lobby.Point p = new ConfigModels.Lobby.Point();
				p.x = f.x;
				p.y = f.y;
				p.z = f.z;
				if (!inside(p, b)) {
					errors.add("lobby.teamFlags[teamIndex=" + f.teamIndex + "] 落在大厅外");
				}
			}
		}
		if (l.dimension != null && !l.dimension.isEmpty() && !l.dimension.startsWith("minecraft:")) {
			errors.add("lobby.dimension 必须是命名空间 id（如 minecraft:overworld），当前 " + l.dimension);
		}
		return errors;
	}

	private static boolean inside(ConfigModels.Lobby.Point p, ConfigModels.Arena.Bounds b) {
		return p.x >= b.minX && p.x < b.maxX && p.y >= b.minY && p.y < b.maxY && p.z >= b.minZ && p.z < b.maxZ;
	}

	/** 读 format 文件（不做 quarantine，格式错误由调用方决定怎么处理）。 */
	public static ConfigModels.Format loadFormat(Path worldRoot, String id) throws IOException {
		if (!isSafeName(id)) {
			throw new IOException("format id 不合法：" + id);
		}
		Path dir = worldRoot.resolve("cww").resolve("formats");
		Path file = dir.resolve(id + ".json");
		if (!file.startsWith(dir)) {
			throw new IOException("路径越界：" + file);
		}
		return GSON.fromJson(Files.readString(file), ConfigModels.Format.class);
	}

	/** 原子写：先 .tmp 再 rename（03 §6.3）。 */
	public static void atomicWrite(Path target, String content) throws IOException {
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.createDirectories(target.getParent());
		Files.writeString(tmp, content);
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static void quarantine(Path file) {
		try {
			Path invalid = file.getParent().getParent().resolve("_invalid");
			Files.createDirectories(invalid);
			Path target = invalid.resolve(STAMP.format(LocalDateTime.now()) + "_" + file.getFileName());
			Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			// 归档失败也不能崩：只影响下次重复报错
		}
	}
}
