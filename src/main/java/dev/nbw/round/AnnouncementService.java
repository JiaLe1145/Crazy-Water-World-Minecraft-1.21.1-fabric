package dev.nbw.round;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nbw.NBW;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 公告（服务端权威，参照 RicordRailExpress 的 AnnouncementManager）。
 *
 * <p>存 {@code <world>/cww/announcements.json}，跨重启保持。发布时：
 * 追加 → 落盘 → 全体聊天广播 → 屏幕 actionbar 提示。
 *
 * <p><b>为什么公告要持久化</b>：维护公告、赛事公告这类东西，运维发完就去干别的了，
 * 重启后还得重发一遍的话，实际没人会发第二次——最后就变成"公告系统很好但没人用"。
 */
public final class AnnouncementService {

	/** 一条公告。 */
	public record Entry(String title, String body, String publisher, long timestamp) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<Entry> entries = new ArrayList<>();
	/** 最多保留多少条：公告是"最近的几件事"，不是流水账，堆太多反而没人翻。 */
	public static final int MAX_ENTRIES = 50;

	private AnnouncementService() {
	}

	/** 开服载入（幂等）。 */
	public static void load(Path worldRoot) {
		entries.clear();
		Path file = path(worldRoot);
		if (!Files.exists(file)) {
			return;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (!root.isJsonArray()) {
				return;
			}
			for (JsonElement el : root.getAsJsonArray()) {
				JsonObject o = el.getAsJsonObject();
				entries.add(new Entry(str(o, "title"), str(o, "body"), str(o, "publisher"),
						o.has("timestamp") ? o.get("timestamp").getAsLong() : 0L));
			}
			NBW.LOGGER.info("[announce] 载入 {} 条公告", entries.size());
		} catch (Exception e) {
			NBW.LOGGER.warn("[announce] 载入失败（按空处理）：{}", e.toString());
		}
	}

	/**
	 * 发布一条公告：追加 → 落盘 → 广播。
	 *
	 * @return 一行结果（给命令回话）
	 */
	public static String publish(MinecraftServer server, String title, String body, String publisher) {
		String t = title == null ? "" : title.trim();
		String b = body == null ? "" : body.trim();
		if (t.isEmpty() && b.isEmpty()) {
			return "标题和内容都是空的，没发出去";
		}
		entries.add(new Entry(t.isEmpty() ? truncate(b, 24) : t, b, publisher, System.currentTimeMillis()));
		while (entries.size() > MAX_ENTRIES) {
			entries.remove(0);
		}
		save(server);
		broadcast(server, entries.get(entries.size() - 1));
		return "已发布公告（当前共 " + entries.size() + " 条）";
	}

	/** 删除第 index 条（从 1 开始，对应 {@link #describe} 的序号）。 */
	public static String remove(MinecraftServer server, int index) {
		if (index < 1 || index > entries.size()) {
			return "序号超出范围（1.." + entries.size() + "）";
		}
		Entry removed = entries.remove(index - 1);
		save(server);
		return "已删除公告「" + removed.title() + "」";
	}

	/** 清空全部。 */
	public static String clear(MinecraftServer server) {
		int n = entries.size();
		entries.clear();
		save(server);
		return "已清空 " + n + " 条公告";
	}

	/** 多行读数：序号 / 标题 / 发布人与时间。 */
	public static List<String> describe() {
		List<String> lines = new ArrayList<>();
		if (entries.isEmpty()) {
			lines.add("[announce] 还没有任何公告");
			return lines;
		}
		lines.add(String.format(Locale.ROOT, "[announce] 共 %d 条（最新的在最后）：", entries.size()));
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			String when = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
					.format(new java.util.Date(e.timestamp()));
			lines.add(String.format(Locale.ROOT, "  #%d [%s] %s — %s%s", i + 1, when, e.publisher(),
					e.title(), e.body().isEmpty() ? "" : "：" + e.body()));
		}
		return lines;
	}

	/** 广播：追加入场时也补一遍最新几条，避免"刚进来什么都没看到"。 */
	public static void syncTo(ServerPlayerEntity player) {
		if (entries.isEmpty()) {
			return;
		}
		int from = Math.max(0, entries.size() - 3);
		for (int i = from; i < entries.size(); i++) {
			Entry e = entries.get(i);
			player.sendMessage(Text.literal("§7[公告] §f" + e.title()
					+ (e.body().isEmpty() ? "" : " §7— §f" + e.body())), false);
		}
	}

	private static void broadcast(MinecraftServer server, Entry e) {
		String line = "§6[公告] §f" + e.title() + (e.body().isEmpty() ? "" : " §7— §f" + e.body());
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			p.sendMessage(Text.literal(line), false);
			// actionbar 再闪一下：正在打架的人不一定看聊天栏
			p.sendMessage(Text.literal("§6" + e.title()), true);
		}
		NBW.LOGGER.info("[announce] {} 发布公告「{}」", e.publisher(), e.title());
	}

	private static void save(MinecraftServer server) {
		try {
			JsonArray arr = new JsonArray();
			for (Entry e : entries) {
				JsonObject o = new JsonObject();
				o.addProperty("title", e.title());
				o.addProperty("body", e.body());
				o.addProperty("publisher", e.publisher());
				o.addProperty("timestamp", e.timestamp());
				arr.add(o);
			}
			Path file = path(server);
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(arr), StandardCharsets.UTF_8);
		} catch (Exception e) {
			NBW.LOGGER.warn("[announce] 落盘失败：{}", e.toString());
		}
	}

	private static Path path(MinecraftServer server) {
		return RoundManager.worldRoot().resolve("cww").resolve("announcements.json");
	}

	private static Path path(Path worldRoot) {
		return worldRoot.resolve("cww").resolve("announcements.json");
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	private static String truncate(String s, int n) {
		return s.length() <= n ? s : s.substring(0, n) + "…";
	}
}
