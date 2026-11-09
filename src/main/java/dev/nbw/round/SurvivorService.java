package dev.nbw.round;

import dev.nbw.NBW;
import net.minecraft.entity.EntityType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 幸存者（M5-2，01 §9）：每队开局自带"与队伍人数相等"的幸存者，各自消耗口粮；
 * 断粮就离队（人数递减）。这是把"必须产淡水/食物"变成硬需求的设计杠杆。
 *
 * <p><b>2026-11-09 实体化第一段</b>：开局在筏上放对应数量的村民占位（禁 AI、无敌、带名字），
 * 断粮离队时同步删实体。真正的"会走动/可招募/给岗位加成"仍留后续。
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
	/** 每队筏上的幸存者实体 UUID（视觉/占位；先不做 AI/招募）。 */
	private static final Map<Integer, List<UUID>> villagerIds = new LinkedHashMap<>();
	/** 海面上的漂流幸存者（可被钩竿拉上筏）。 */
	private static final List<UUID> floatingIds = new ArrayList<>();
	private static MinecraftServer server;
	private static int tick;

	private SurvivorService() {
	}

	/** PLAY 开始：每队按人数发幸存者 + 缓冲口粮，并在筏上放对应数量的实体。 */
	public static void begin(MinecraftServer srv) {
		server = srv;
		survivors.clear();
		provisions.clear();
		villagerIds.clear();
		tick = 0;
		for (var team : RoundManager.machine().teams()) {
			int members = team.members().size();
			if (members <= 0) {
				continue;
			}
			survivors.put(team.index(), members);
			provisions.put(team.index(), members * START_BUFFER_MINUTES);
			spawnVillagers(srv, team.index(), members);
		}
		NBW.LOGGER.info("[survivor] 开局：{} 队，幸存者 {}", survivors.size(), survivors);
	}

	/** 在队伍锚点旁放 N 个"站桩"幸存者（村民占位，禁 AI）。 */
	private static void spawnVillagers(MinecraftServer srv, int team, int count) {
		List<UUID> list = villagerIds.computeIfAbsent(team, k -> new ArrayList<>());
		for (int i = 0; i < count; i++) {
			UUID id = spawnOneVillager(srv, team, i, count);
			if (id != null) {
				list.add(id);
			}
		}
	}

	/** 放 1 个筏上幸存者，返回 UUID；失败返 null。 */
	private static UUID spawnOneVillager(MinecraftServer srv, int team, int index, int total) {
		var world = srv.getOverworld();
		var a = RoundManager.anchorAt(team);
		if (a == null) {
			return null;
		}
		var v = net.minecraft.entity.EntityType.VILLAGER.create(world);
		if (v == null) {
			return null;
		}
		double ang = Math.PI * 2.0 * index / Math.max(1, total);
		v.refreshPositionAndAngles(a.x() + 0.5 + Math.cos(ang) * 1.2, a.y(),
				a.z() + 0.5 + Math.sin(ang) * 1.2, 0.0f, 0.0f);
		v.setAiDisabled(true);   // 站桩：不 wander、不打人
		v.setInvulnerable(true);   // 不被黑海/流弹误杀（它是"口粮压力"的视觉，不是战斗单位）
		v.setCustomName(Text.literal("§a幸存者·队" + team));
		v.setCustomNameVisible(true);
		v.setPersistent();
		world.spawnEntity(v);
		return v.getUuid();
	}

	/** 幸存者人口上限（02 §1：quick/standard/big 2/4/5；这里按 teamSize+2 取简）。 */
	private static int maxSurvivors() {
		return Math.max(2, RoundManager.machine().config().teamSize() + 2);
	}

	/**
	 * 在海上随机位置放 N 个「漂流幸存者」（可被钩竿拉上筏）。
	 *
	 * @return 实际放出数量
	 */
	public static int spawnFloating(MinecraftServer srv, int count) {
		if (srv == null) {
			return 0;
		}
		var world = srv.getOverworld();
		var arena = RoundManager.arenaOrNull();
		if (arena == null || arena.sea == null) {
			return 0;
		}
		var rnd = new java.util.Random(world.getRandom().nextLong());
		int ok = 0;
		for (int i = 0; i < count; i++) {
			var v = net.minecraft.entity.EntityType.VILLAGER.create(world);
			if (v == null) {
				continue;
			}
			double ang = rnd.nextDouble() * Math.PI * 2.0;
			double r = 20.0 + rnd.nextDouble() * 50.0;
			v.refreshPositionAndAngles(arena.sea.centerX + Math.cos(ang) * r,
					arena.sea.waterLevel + 0.5, arena.sea.centerZ + Math.sin(ang) * r, 0.0f, 0.0f);
			v.setAiDisabled(true);
			v.setInvulnerable(true);
			v.setCustomName(Text.literal("§e漂流幸存者"));
			v.setCustomNameVisible(true);
			v.setPersistent();
			world.spawnEntity(v);
			floatingIds.add(v.getUuid());
			ok++;
		}
		NBW.LOGGER.info("[survivor] 海面漂出 {} 个幸存者（可钩竿招募）", ok);
		return ok;
	}

	/** 找距 center 最近的漂流幸存者（在 range 内），没有返 null。 */
	public static UUID nearestFloating(net.minecraft.server.world.ServerWorld world,
			net.minecraft.util.math.Vec3d center, double range) {
		UUID best = null;
		double bestD = range;
		for (UUID id : floatingIds) {
			var e = world.getEntity(id);
			if (e == null) {
				continue;
			}
			double d = e.getPos().distanceTo(center);
			if (d < bestD) {
				bestD = d;
				best = id;
			}
		}
		return best;
	}

	/** 招募：把一个漂流幸存者加入队伍（+1 人 + 放 1 个筏上实体）。 */
	public static boolean recruitSurvivor(int team, UUID survivorId) {
		if (server == null) {
			return false;
		}
		if (survivors.getOrDefault(team, 0) >= maxSurvivors()) {
			return false;
		}
		floatingIds.remove(survivorId);
		var e = server.getOverworld().getEntity(survivorId);
		if (e != null) {
			e.discard();
		}
		survivors.merge(team, 1, Integer::sum);
		UUID id = spawnOneVillager(server, team, 0, 1);
		if (id != null) {
			villagerIds.computeIfAbsent(team, k -> new ArrayList<>()).add(id);
		}
		announce(server, team, "§a[幸存者] 队伍 N" + team + " 招募了 1 名漂流幸存者（现 "
				+ survivors.get(team) + "）");
		return true;
	}

	private static void removeVillager(int team) {
		if (server == null) {			return;
		}
		List<UUID> list = villagerIds.get(team);
		if (list == null || list.isEmpty()) {
			return;
		}
		UUID id = list.remove(list.size() - 1);
		var e = server.getOverworld().getEntity(id);
		if (e != null) {
			e.discard();
		}
	}

	private static void clearVillagers() {
		if (server == null) {
			return;
		}
		for (List<UUID> list : villagerIds.values()) {
			for (UUID id : list) {
				var e = server.getOverworld().getEntity(id);
				if (e != null) {
					e.discard();
				}
			}
		}
		villagerIds.clear();
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
					removeVillager(team);   // 实体也跟着走
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

	/**
	 * 生产岗位效率（02 §9）：有幸存者的队，设施冷却按此倍率缩短（−30% 周期）。
	 *
	 * @return 0.7 = 有幸存者、1.0 = 没有（无队伍也按 1.0）
	 */
	public static double productionSpeedFor(ServerPlayerEntity player) {
		var session = RoundManager.machine().session(player.getUuid());
		if (session == null || session.teamIndex() < 0) {
			return 1.0;
		}
		return survivors.getOrDefault(session.teamIndex(), 0) > 0 ? 0.7 : 1.0;
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
		clearVillagers();
		survivors.clear();
		provisions.clear();
		tick = 0;
		server = null;
	}
}
