package dev.nbw.round;

import dev.nbw.NBW;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 幸存者（M5-2，01 §9）：每队开局自带"与队伍人数相等"的幸存者，各自消耗口粮；
 * 断粮就离队（人数递减）。这是把"必须产淡水/食物"变成硬需求的设计杠杆。
 *
 * <p>本轮用<b>队级计数</b>而不是实体 NPC：先落"口粮压力 → 生产价值"这条闭环，
 * 实体化（会走动、能被招募、给岗位加成）留到后续。
 *
 * <p>口粮来源：海水过滤器/厨台/堆肥农场每产出一份，给本队 +1 口粮（见 {@link #addProvisions}）。
 */
public final class SurvivorService {

	/** 每名幸存者每分钟消耗的口粮。 */
	public static final double RATION_PER_MIN_PER_SURVIVOR = 1.0;

	/** 开局缓冲：每名幸存者先带这么多口粮（分钟数），避免开局就断粮。 */
	public static final double START_BUFFER_MINUTES = 6.0;

	/** 断粮后，每隔这么多 tick 离队 1 人。 */
	private static final int LEAVE_INTERVAL_TICKS = 30 * 20;

	private static final Map<Integer, Integer> survivors = new LinkedHashMap<>();
	private static final Map<Integer, Double> provisions = new LinkedHashMap<>();
	private static int tick;

	private SurvivorService() {
	}

	/** PLAY 开始：每队按人数发幸存者 + 缓冲口粮。 */
	public static void begin(MinecraftServer srv) {
		survivors.clear();
		provisions.clear();
		tick = 0;
		for (var team : RoundManager.machine().teams()) {
			int members = team.members().size();
			if (members <= 0) {
				continue;
			}
			survivors.put(team.index(), members);
			provisions.put(team.index(), members * START_BUFFER_MINUTES);
		}
		NBW.LOGGER.info("[survivor] 开局：{} 队，幸存者 {}", survivors.size(), survivors);
	}

	/** PLAY 每 tick：每秒扣口粮；断粮则每 30s 离队 1 人。 */
	public static void onEndTick(MinecraftServer srv) {
		if (survivors.isEmpty()) {
			return;
		}
		tick++;
		if (tick % 20 == 0) {
			for (Map.Entry<Integer, Integer> e : survivors.entrySet()) {
				int team = e.getKey();
				int count = e.getValue();
				if (count <= 0) {
					continue;
				}
				double drain = count * RATION_PER_MIN_PER_SURVIVOR / 60.0;
				double left = provisions.getOrDefault(team, 0.0) - drain;
				provisions.put(team, Math.max(0.0, left));
			}
		}
		if (tick % LEAVE_INTERVAL_TICKS == 0) {
			for (Map.Entry<Integer, Integer> e : survivors.entrySet()) {
				int team = e.getKey();
				int count = e.getValue();
				if (count > 0 && provisions.getOrDefault(team, 0.0) <= 0.0) {
					e.setValue(count - 1);
					announce(srv, team, "§c[幸存者] 队伍 N" + team + " 断粮，1 名幸存者离队（剩 " + (count - 1) + "）");
					NBW.LOGGER.info("[survivor] 队 {} 断粮离队：{} -> {}", team, count, count - 1);
				}
			}
		}
	}

	/** 生产出一份物资：给该玩家所在队 +1 口粮。 */
	public static void addProvisions(ServerPlayerEntity player, double amount) {
		var session = RoundManager.machine().session(player.getUuid());
		if (session == null || session.teamIndex() < 0) {
			return;
		}
		provisions.merge(session.teamIndex(), amount, Double::sum);
	}

	/** 某队的幸存者/口粮读数。 */
	public static String teamStatus(int team) {
		int count = survivors.getOrDefault(team, 0);
		double prov = provisions.getOrDefault(team, 0.0);
		return String.format(Locale.ROOT, "队%d 幸存者=%d 口粮=%.1f", team, count, prov);
	}

	/** 全局读数：给 {@code /cww survivor}。 */
	public static String status() {
		if (survivors.isEmpty()) {
			return "[survivor] 本局没有幸存者（未开局/无队伍）";
		}
		StringBuilder sb = new StringBuilder("[survivor] ");
		for (int team : survivors.keySet()) {
			sb.append(teamStatus(team)).append("｜");
		}
		return sb.toString().trim();
	}

	private static void announce(MinecraftServer srv, int team, String msg) {
		NBW.LOGGER.info("[survivor] {}", msg);
		for (UUID id : RoundManager.participantIds()) {
			ServerPlayerEntity p = srv.getPlayerManager().getPlayer(id);
			if (p != null) {
				p.sendMessage(Text.literal(msg), false);
			}
		}
	}

	/** 局后清理。 */
	public static void reset() {
		survivors.clear();
		provisions.clear();
		tick = 0;
	}
}
