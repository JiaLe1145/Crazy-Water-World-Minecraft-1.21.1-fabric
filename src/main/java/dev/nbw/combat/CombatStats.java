package dev.nbw.combat;

import com.google.gson.Gson;
import dev.nbw.NBW;
import dev.nbw.domain.round.RoundMachine;
import dev.nbw.round.RoundManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 战绩统计（M5-3）：每人击杀 / 阵亡 / 造成伤害，结算面板据此出"击杀王 / MVP"。
 *
 * <p>与 {@link dev.nbw.round.MetricsService}（局级配平埋点）分工：那边看"这一局的节奏"，
 * 这边看"每个人打了多少"。局内数据在 `reset` 清空；SETTLE 时另写一份
 * {@code <world>/cww/state/matches/<match>.json}，给赛后复盘与 Gate G5 留下证据。
 */
public final class CombatStats {

	private static final Gson GSON = new Gson();

	private static final Map<UUID, Integer> kills = new LinkedHashMap<>();
	private static final Map<UUID, Integer> deaths = new LinkedHashMap<>();
	private static final Map<UUID, Double> damageDealt = new LinkedHashMap<>();
	/**
	 * 名字快照：玩家死亡后实体会被移除（Carpet 假玩家尤其如此），SETTLE 时
	 * {@code getPlayerManager().getPlayer(uuid)} 已经查不到人；不缓存名字就会把阵亡者写成 UUID 前缀。
	 */
	private static final Map<UUID, String> names = new LinkedHashMap<>();

	private CombatStats() {
	}

	/** 挂死亡事件：阵亡 +1，击杀者 +1。由 {@code NBW.onInitialize} 调用。 */
	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof ServerPlayerEntity victim)) {
				return;
			}
			names.put(victim.getUuid(), victim.getName().getString());
			deaths.merge(victim.getUuid(), 1, Integer::sum);
			if (source.getAttacker() instanceof ServerPlayerEntity killer && !killer.equals(victim)) {
				names.put(killer.getUuid(), killer.getName().getString());
				kills.merge(killer.getUuid(), 1, Integer::sum);
			}
		});
		NBW.LOGGER.info("[stats] 战绩统计已挂载（击杀/阵亡/伤害）");
	}

	/** 一次生效伤害：攻击者累计"造成伤害"。 */
	public static void onDamage(ServerPlayerEntity attacker, ServerPlayerEntity victim, double amount) {
		if (attacker == null || victim == null || attacker.equals(victim) || amount <= 0) {
			return;
		}
		names.put(attacker.getUuid(), attacker.getName().getString());
		names.put(victim.getUuid(), victim.getName().getString());
		damageDealt.merge(attacker.getUuid(), amount, Double::sum);
	}

	/** 一局结束时的多行面板（给 SETTLE 阶段广播）。 */
	public static List<String> summary(MinecraftServer server) {
		List<String> lines = new ArrayList<>();
		if (kills.isEmpty() && damageDealt.isEmpty()) {
			lines.add("§7本局没有发生战斗");
			return lines;
		}
		List<Map.Entry<UUID, Integer>> top = new ArrayList<>(kills.entrySet());
		top.sort(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder()));
		lines.add("§6—— 本局战绩 ——");
		int limit = Math.min(3, top.size());
		for (int i = 0; i < limit; i++) {
			Map.Entry<UUID, Integer> e = top.get(i);
			String name = nameOf(server, e.getKey());
			int dmg = (int) Math.round(damageDealt.getOrDefault(e.getKey(), 0.0));
			lines.add(String.format(Locale.ROOT, "§e击杀王 %d. %s：击杀 %d｜伤害 %d", i + 1, name, e.getValue(), dmg));
		}
		if (limit == 0) {
			lines.add("§7本局没有击杀");
		}
		return lines;
	}

	/** 一行读数：给 {@code /cww stats}。击杀/阵亡/伤害各列前 5，按数值降序。 */
	public static String status(MinecraftServer server) {
		if (kills.isEmpty() && deaths.isEmpty() && damageDealt.isEmpty()) {
			return "[stats] 本局暂无战绩";
		}
		StringBuilder sb = new StringBuilder("[stats] 击杀 ");
		int shown = 0;
		for (Map.Entry<UUID, Integer> e : kills.entrySet()) {
			if (shown++ >= 5) {
				break;
			}
			sb.append(nameOf(server, e.getKey())).append('=').append(e.getValue()).append(' ');
		}
		sb.append("｜阵亡 ");
		shown = 0;
		for (Map.Entry<UUID, Integer> e : deaths.entrySet()) {
			if (shown++ >= 5) {
				break;
			}
			sb.append(nameOf(server, e.getKey())).append('=').append(e.getValue()).append(' ');
		}
		sb.append("｜伤害 ");
		List<Map.Entry<UUID, Double>> dmg = new ArrayList<>(damageDealt.entrySet());
		dmg.sort(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()));
		shown = 0;
		for (Map.Entry<UUID, Double> e : dmg) {
			if (shown++ >= 5) {
				break;
			}
			sb.append(nameOf(server, e.getKey())).append('=')
					.append(String.format(Locale.ROOT, "%.1f", e.getValue())).append(' ');
		}
		return sb.toString().trim();
	}

	private static String nameOf(MinecraftServer server, UUID id) {
		String cached = names.get(id);
		if (cached != null) {
			return cached;
		}
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
		return p == null ? id.toString().substring(0, 8) : p.getName().getString();
	}

	/**
	 * SETTLE 时把本局战绩写成结构化 JSON（M5-3 / 02 §11.2）。
	 *
	 * <p>路径 {@code <world>/cww/state/matches/<match>.json}；每名有战绩的玩家一行，
	 * 带 UUID/名字/队伍/击杀/阵亡/伤害，另附本局结果（胜者、原因、时长、存活数）。
	 * 落盘失败只写 WARN，不阻断结算——战绩文件是复盘证据，不是对局状态。
	 *
	 * @return 一行可读结论（给日志/命令用）
	 */
	public static String persist(MinecraftServer server) {
		if (server == null) {
			return "没有服务端，跳过战绩落盘";
		}
		if (kills.isEmpty() && deaths.isEmpty() && damageDealt.isEmpty()) {
			return "本局没有战绩，跳过落盘";
		}
		try {
			int match = RoundManager.machine().matchIndex();
			// 与 RoundStateStore 同一套目录约定：<world>/cww/state/...（不是 <world>/state）。
			Path dir = RoundManager.worldRoot().resolve("cww").resolve("state").resolve("matches");
			Files.createDirectories(dir);
			Path file = dir.resolve(match + ".json");

			Map<String, Object> root = new LinkedHashMap<>();
			root.put("match", match);
			root.put("recordedAt", Instant.now().toString());
			RoundMachine.Result result = RoundManager.machine().lastResult();
			if (result != null) {
				root.put("winner", result.isDraw() ? null : result.winnerName());
				root.put("cause", result.cause());
				root.put("durationSec", result.durationSeconds());
				root.put("survivors", result.survivors());
				root.put("participants", result.participants());
			}

			Set<UUID> ids = new LinkedHashSet<>();
			ids.addAll(kills.keySet());
			ids.addAll(deaths.keySet());
			ids.addAll(damageDealt.keySet());
			List<Map<String, Object>> rows = new ArrayList<>();
			for (UUID id : ids) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("uuid", id.toString());
				row.put("name", nameOf(server, id));
				var session = RoundManager.machine().session(id);
				row.put("team", session == null ? -1 : session.teamIndex());
				row.put("kills", kills.getOrDefault(id, 0));
				row.put("deaths", deaths.getOrDefault(id, 0));
				// 保留一位小数：伤害来自武器表，浮点累加后不需要更细的精度。
				row.put("damage", Math.round(damageDealt.getOrDefault(id, 0.0) * 10.0) / 10.0);
				rows.add(row);
			}
			root.put("players", rows);

			Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
			NBW.LOGGER.info("[stats] 战绩已落盘：{}", file);
			return "已落盘 " + file;
		} catch (Exception e) {
			NBW.LOGGER.warn("[stats] 战绩落盘失败：{}", e.toString());
			return "落盘失败：" + e;
		}
	}

	/** 局后清理。 */
	public static void reset() {
		kills.clear();
		deaths.clear();
		damageDealt.clear();
		names.clear();
	}
}
