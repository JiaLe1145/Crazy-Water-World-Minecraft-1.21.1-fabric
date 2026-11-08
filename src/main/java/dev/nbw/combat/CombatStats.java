package dev.nbw.combat;

import dev.nbw.NBW;
import dev.nbw.round.RoundManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 战绩统计（M5-3）：每人击杀 / 阵亡 / 造成伤害，结算面板据此出"击杀王 / MVP"。
 *
 * <p>与 {@link dev.nbw.round.MetricsService}（局级配平埋点）分工：那边看"这一局的节奏"，
 * 这边看"每个人打了多少"。数据都只在局内有效，`reset` 清空。
 */
public final class CombatStats {

	private static final Map<UUID, Integer> kills = new LinkedHashMap<>();
	private static final Map<UUID, Integer> deaths = new LinkedHashMap<>();
	private static final Map<UUID, Double> damageDealt = new LinkedHashMap<>();

	private CombatStats() {
	}

	/** 挂死亡事件：阵亡 +1，击杀者 +1。由 {@code NBW.onInitialize} 调用。 */
	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof ServerPlayerEntity victim)) {
				return;
			}
			deaths.merge(victim.getUuid(), 1, Integer::sum);
			if (source.getAttacker() instanceof ServerPlayerEntity killer && !killer.equals(victim)) {
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

	/** 一行读数：给 {@code /cww stats}。 */
	public static String status(MinecraftServer server) {
		if (kills.isEmpty() && deaths.isEmpty()) {
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
		return sb.toString().trim();
	}

	private static String nameOf(MinecraftServer server, UUID id) {
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
		return p == null ? id.toString().substring(0, 8) : p.getName().getString();
	}

	/** 局后清理。 */
	public static void reset() {
		kills.clear();
		deaths.clear();
		damageDealt.clear();
	}
}
