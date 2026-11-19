package dev.nbw.round;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nbw.NBW;
import dev.nbw.domain.cosmetic.CosmeticRule;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 跨局战绩与外观（01 §A11 已批准：局外只累计外观与战绩，无数值）。
 *
 * <p>存 {@code <world>/cww/career.json}：每个玩家 UUID 一行，记 {@code wins / kills / title}。
 * 战绩来源：SETTLE 时把本局的胜者与击杀数累加进来（见 {@link #recordMatch}）。
 *
 * <p><b>为什么单独存而不是塞进 round.json</b>：round.json 是"当前这一局"的状态，
 * 局结束会被清；战绩要跨局、跨重启活着，生命周期不同，混在一起迟早互相踩。
 */
public final class CareerService {

	/** 一个玩家的累计战绩 + 当前称号。 */
	public static final class Career {
		public int wins;
		public int kills;
		public String title = CosmeticRule.DEFAULT_ID;

		Career copy() {
			Career c = new Career();
			c.wins = wins;
			c.kills = kills;
			c.title = title;
			return c;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, Career> careers = new LinkedHashMap<>();

	private CareerService() {
	}

	/** 开服时载入（幂等）。 */
	public static void load(Path worldRoot) {
		careers.clear();
		Path file = path(worldRoot);
		if (!Files.exists(file)) {
			return;
		}
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			for (String key : root.keySet()) {
				try {
					UUID id = UUID.fromString(key);
					JsonObject o = root.getAsJsonObject(key);
					Career c = new Career();
					c.wins = o.has("wins") ? o.get("wins").getAsInt() : 0;
					c.kills = o.has("kills") ? o.get("kills").getAsInt() : 0;
					c.title = o.has("title") ? o.get("title").getAsString() : CosmeticRule.DEFAULT_ID;
					careers.put(id, c);
				} catch (IllegalArgumentException ignored) {
					// 坏 key 跳过，不要因为一行坏数据整个文件读不出来
				}
			}
			NBW.LOGGER.info("[career] 载入 {} 名玩家的跨局战绩", careers.size());
		} catch (Exception e) {
			NBW.LOGGER.warn("[career] 载入失败（按空战绩继续）：{}", e.toString());
		}
	}

	/**
	 * 局结算：所有参战者登记、胜者队员各 +1 胜、按击杀数累加。
	 *
	 * @param participants 本局所有参战者（保证零战绩也建档，方便赛后查）
	 * @param winners      胜者队成员（平局传空集合）；每人 +1 胜
	 * @param killsByPlayer 本局每人击杀数
	 */
	public static void recordMatch(java.util.Collection<UUID> participants,
			java.util.Collection<UUID> winners, java.util.Map<UUID, Integer> killsByPlayer) {
		if (participants != null) {
			for (UUID id : participants) {
				if (id != null) {
					careers.computeIfAbsent(id, k -> new Career());
				}
			}
		}
		if (winners != null) {
			for (UUID id : winners) {
				if (id != null) {
					careers.computeIfAbsent(id, k -> new Career()).wins++;
				}
			}
		}
		if (killsByPlayer != null) {
			killsByPlayer.forEach((id, n) -> {
				if (id != null && n != null && n > 0) {
					careers.computeIfAbsent(id, k -> new Career()).kills += n;
				}
			});
		}
	}

	/** 读某人的累计战绩（没有就返回零值，不落库）。 */
	public static Career of(UUID id) {
		Career c = careers.get(id);
		return c == null ? new Career() : c.copy();
	}

	/** 选称号：已解锁才允许，返回 null 表示拒绝（由调用方给原因）。 */
	public static String selectTitle(UUID id, String titleId) {
		Career c = careers.computeIfAbsent(id, k -> new Career());
		var picked = CosmeticRule.pick(titleId, c.wins, c.kills);
		if (picked == null) {
			return null;
		}
		c.title = picked.id();
		return picked.id();
	}

	/** 某人的显示称号（未选/被清过就回默认）。 */
	public static CosmeticRule.Title titleOf(UUID id) {
		Career c = careers.get(id);
		var t = c == null ? null : CosmeticRule.byId(c.title);
		if (t == null || !CosmeticRule.isUnlocked(t.id(), c.wins, c.kills)) {
			t = CosmeticRule.byId(CosmeticRule.DEFAULT_ID);
		}
		return t;
	}

	/** 落盘（局结算与选手动改动后调用）。 */
	public static void save(Path worldRoot) {
		try {
			JsonObject root = new JsonObject();
			for (Map.Entry<UUID, Career> e : careers.entrySet()) {
				JsonObject o = new JsonObject();
				o.addProperty("wins", e.getValue().wins);
				o.addProperty("kills", e.getValue().kills);
				o.addProperty("title", e.getValue().title);
				root.add(e.getKey().toString(), o);
			}
			Path file = path(worldRoot);
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (Exception e) {
			NBW.LOGGER.warn("[career] 落盘失败：{}", e.toString());
		}
	}

	/** 一行读数：给 {@code /cww cosmetic status <玩家>}。 */
	public static String describe(UUID id) {
		Career c = of(id);
		var t = titleOf(id);
		return String.format(Locale.ROOT, "[career] 胜=%d 杀=%d 称号=%s（已解锁 %s）",
				c.wins, c.kills, t.display(),
				CosmeticRule.unlocked(c.wins, c.kills).stream().map(CosmeticRule.Title::id).toList());
	}

	private static Path path(Path worldRoot) {
		return worldRoot.resolve("cww").resolve("career.json");
	}
}
