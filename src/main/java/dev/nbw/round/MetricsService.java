package dev.nbw.round;

import dev.nbw.NBW;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 对局埋点与配平回路（M3-3，Gate G3）：每局记三件事——
 * <b>局时长</b>、<b>首战时刻</b>、<b>生产参与率</b>（用原版生产方块当代理，M4 的生产设施接上后换成本方的）。
 *
 * <p>Gate G3 判定：每局 15 分钟内结束且最后 3 分钟有交战；≥70% 对局使用过生产设施。
 * 这里只负责把数据留痕，判定与回炉由人看日志/命令决定（不自动改数值）。
 */
public final class MetricsService {

	private static MinecraftServer server;
	private static int startTick = -1;
	private static int firstBloodTick = -1;
	private static int participants;
	private static final Set<UUID> productionUsers = new LinkedHashSet<>();
	/** 每次「玩家打玩家」生效的 tick，用来数「最后 3 分钟交战次数」（Gate G3 判据）。 */
	private static final java.util.List<Integer> pvpHitTicks = new java.util.ArrayList<>();
	private static String lastSummary = "（本局还没结束）";

	private MetricsService() {
	}

	/** PLAY 开始：记起始 tick、参赛人数，清本局计数。 */
	public static void begin(MinecraftServer srv) {
		server = srv;
		startTick = srv.getTicks();
		firstBloodTick = -1;
		productionUsers.clear();
		pvpHitTicks.clear();
		participants = RoundManager.participantIds().size();
		lastSummary = "（本局进行中）";
		NBW.LOGGER.info("[metrics] 开局：participants={}", participants);
	}

	/** 每次「玩家打玩家」生效都记一笔：首战取第一条，末段交战数用整张表（被攻速闸门拒掉的不算）。 */
	public static void onPvpDamage(ServerPlayerEntity attacker, ServerPlayerEntity victim) {
		if (server == null || attacker == null || victim == null) {
			return;
		}
		if (attacker.getUuid().equals(victim.getUuid())) {
			return;
		}
		int now = server.getTicks();
		pvpHitTicks.add(now);
		if (firstBloodTick < 0) {
			firstBloodTick = now;
			NBW.LOGGER.info("[metrics] 首战：开局后 {}s", (firstBloodTick - startTick) / 20);
		}
	}

	/** 玩家右键了生产方块（合成台/熔炉/铁砧…）→ 记他参与过生产。 */
	public static void onProduction(ServerPlayerEntity player, BlockState block) {
		if (player == null || block == null || !isProductionBlock(block)) {
			return;
		}
		if (productionUsers.add(player.getUuid())) {
			NBW.LOGGER.info("[metrics] 生产参与：{} 首次使用 {}", player.getName().getString(), block.getBlock());
		}
	}

	/** SETTLE 时结算并留痕（不自动改数值，只给 Gate G3 判据）。 */
	public static void finish(MinecraftServer srv) {
		if (startTick < 0 || srv == null) {
			return;
		}
		int endTick = srv.getTicks();
		int duration = endTick - startTick;
		int firstBlood = firstBloodTick < 0 ? -1 : firstBloodTick - startTick;
		double rate = participants == 0 ? 0.0 : productionUsers.size() * 100.0 / participants;
		// Gate G3 的「最后 3 分钟有交战」：数末段窗口内的 PvP 次数
		int lateWindowStart = endTick - 3 * 60 * 20;
		int lateHits = 0;
		for (int t : pvpHitTicks) {
			if (t >= lateWindowStart) {
				lateHits++;
			}
		}
		lastSummary = String.format(Locale.ROOT,
				"[metrics] 局时长=%.1fs 首战=%s 末段3min交战=%d次 生产参与率=%.0f%%(%d/%d)",
				duration / 20.0,
				firstBlood < 0 ? "无" : String.format(Locale.ROOT, "%.1fs", firstBlood / 20.0),
				lateHits, rate, productionUsers.size(), participants);
		NBW.LOGGER.info("{}", lastSummary);
		appendJsonl(srv, duration, firstBlood, lateHits, rate);
	}

	/** 结构化落盘（02 §11.2）：追加一行 JSONL 到 {@code <world>/cww/metrics.jsonl}。 */
	private static void appendJsonl(MinecraftServer srv, int duration, int firstBlood, int lateHits, double rate) {
		try {
			var dir = RoundManager.worldRoot().resolve("cww");
			java.nio.file.Files.createDirectories(dir);
			var file = dir.resolve("metrics.jsonl");
			String line = String.format(Locale.ROOT,
					"{\"match\":%d,\"durationSec\":%.1f,\"firstBloodSec\":%.1f,\"lateHits\":%d,\"productionRate\":%.3f,\"participants\":%d,\"productionUsers\":%d}",
					RoundManager.machine().matchIndex(), duration / 20.0,
					firstBlood < 0 ? -1.0 : firstBlood / 20.0, lateHits, rate / 100.0,
					participants, productionUsers.size());
			java.nio.file.Files.writeString(file, line + System.lineSeparator(),
					java.nio.charset.StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (Exception e) {
			NBW.LOGGER.warn("[metrics] 落盘失败：{}", e.toString());
		}
	}

	/** 一行读数（进行中）：给 {@code /cww metrics}。 */
	public static String status() {
		if (startTick < 0 || server == null) {
			return "[metrics] 未开局｜上局：" + lastSummary;
		}
		int elapsed = (server.getTicks() - startTick) / 20;
		return String.format(Locale.ROOT,
				"[metrics] 进行 %ds participantes=%d 首战=%s 生产参与=%d/%d｜上局：%s",
				elapsed, participants,
				firstBloodTick < 0 ? "无" : (firstBloodTick - startTick) / 20 + "s",
				productionUsers.size(), participants, lastSummary);
	}

	/** 上一局的一行结论。 */
	public static String lastSummary() {
		return lastSummary;
	}

	/** 局后清理：本局计数清空，但 {@link #lastSummary} 保留到下一局开盘。 */
	public static void reset() {
		server = null;
		startTick = -1;
		firstBloodTick = -1;
		participants = 0;
		productionUsers.clear();
		pvpHitTicks.clear();
	}

	/** 原版生产方块代理（M4 的生产设施接上后换成本方判据）。 */
	private static boolean isProductionBlock(BlockState state) {
		return state.isOf(Blocks.CRAFTING_TABLE)
				|| state.isOf(Blocks.FURNACE)
				|| state.isOf(Blocks.SMOKER)
				|| state.isOf(Blocks.BLAST_FURNACE)
				|| state.isOf(Blocks.ANVIL)
				|| state.isOf(Blocks.BREWING_STAND)
				|| state.isOf(Blocks.STONECUTTER)
				|| state.isOf(Blocks.GRINDSTONE)
				|| state.isOf(Blocks.LOOM)
				|| state.isOf(Blocks.CARTOGRAPHY_TABLE);
	}
}
