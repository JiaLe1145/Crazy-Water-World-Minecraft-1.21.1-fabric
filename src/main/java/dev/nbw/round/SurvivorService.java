package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.domain.survivor.SurvivorFormation;
import dev.nbw.domain.survivor.SurvivorJob;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.sable.SableRafts;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.EnumMap;
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
 * <p><b>2026-11-14 跟随收口</b>：村民不再留在出生点，而是按
 * {@link SurvivorFormation} 的甲板局部阵位每 tick 贴回筏面（{@code SableRafts.localToWorld}），
 * 所以筏子移动、转向、下沉时人都跟着。这里刻意<b>不</b>依赖"实体随 sub-level"——
 * 那条能力在 M0 没有完全验证，先用确定的服务端重定位把行为做对，后续再决定是否换成原生承载。
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
	/** 筏上实体 UUID → 职业；招募/离队时同步维护。 */
	private static final Map<UUID, SurvivorJob> jobs = new LinkedHashMap<>();
	/** 海面上的漂流幸存者（可被钩竿拉上筏）。 */
	private static final List<UUID> floatingIds = new ArrayList<>();
	private static MinecraftServer server;
	private static int tick;

	private SurvivorService() {
	}

	/** PLAY 开始：每队按人数发幸存者 + 缓冲口粮，并在筏上放对应数量的实体。 */
	public static void begin(MinecraftServer srv) {
		server = srv;
		int stale = sweepStaleSurvivors(srv);
		if (stale > 0) {
			NBW.LOGGER.info("[survivor] 开局清掉 {} 个上一局/重启残留的幸存者实体", stale);
		}
		survivors.clear();
		provisions.clear();
		villagerIds.clear();
		jobs.clear();
		floatingIds.clear();
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
			SurvivorJob job = SurvivorJob.initialByIndex(i);
			UUID id = spawnOneVillager(srv, team, i, count, job);
			if (id != null) {
				list.add(id);
				jobs.put(id, job);
			}
		}
	}

	/** 放 1 个筏上幸存者，返回 UUID；失败返 null。 */
	private static UUID spawnOneVillager(MinecraftServer srv, int team, int index, int total, SurvivorJob job) {
		ServerWorld world = srv.getOverworld();
		var a = RoundManager.anchorAt(team);
		if (a == null) {
			return null;
		}
		var v = EntityType.VILLAGER.create(world);
		if (v == null) {
			return null;
		}
		double[] local = SurvivorFormation.localOffset(index, total);
		double[] pos = survivorWorldPos(world, team, local);
		if (pos == null) {
			// 发筏失败/已被清场：先退回锚点，下一 tick 若能找到筏会重新贴回甲板。
			pos = new double[]{a.x() + 0.5 + local[0], a.y() + local[1], a.z() + 0.5 + local[2]};
		}
		v.refreshPositionAndAngles(pos[0], pos[1], pos[2], 0.0f, 0.0f);
		v.setAiDisabled(true);   // 站桩：不 wander、不打人
		// 不再 setInvulnerable：01 §A3-v2 要求"必须先打光守筏的幸存者"才能解除反偷家 ×0.25，
		// 无敌会让这条战术永远不成立（2026-11-19 用户裁决：允许被击杀）。
		v.setNoGravity(true);      // 位置由 followRafts 每 tick 贴回甲板，不交给重力/地形
		v.setCustomName(Text.literal("§a幸存者·队" + team + "·" + job.displayName()));
		v.setCustomNameVisible(true);
		v.setPersistent();
		world.spawnEntity(v);
		NBW.LOGGER.info("[survivor] 队 {} 幸存者({}) 落位 local=({}, {}, {}) world=({}, {}, {})",
				team, job.displayName(), local[0], local[1], local[2], pos[0], pos[1], pos[2]);
		return v.getUuid();
	}

	/**
	 * 把甲板局部阵位换算成世界坐标；筏不在场/读不到位姿时返回 null。
	 *
	 * <p>这是"幸存者跟着筏走"的唯一坐标口径：绝不能拿锚点世界坐标加偏移——
	 * 筏子开走之后那样算出来的是出生点旁边的水。
	 */
	private static double[] survivorWorldPos(ServerWorld world, int team, double[] local) {
		UUID raft = RaftSpawner.raftOf(team);
		if (raft == null) {
			return null;
		}
		double[] pos = SableRafts.localToWorld(world, raft, local[0], local[1], local[2]);
		return Double.isNaN(pos[0]) ? null : pos;
	}

	/** 每 tick 把每队幸存者贴回当前筏面的局部阵位（跟随移动/转向/下沉）。 */
	private static void followRafts(MinecraftServer srv) {
		ServerWorld world = srv.getOverworld();
		for (Map.Entry<Integer, List<UUID>> entry : villagerIds.entrySet()) {
			int team = entry.getKey();
			List<UUID> list = entry.getValue();
			if (list == null || list.isEmpty()) {
				continue;
			}
			int total = list.size();
			for (int i = 0; i < list.size(); i++) {
				Entity entity = world.getEntity(list.get(i));
				if (entity == null) {
					continue;   // 区块未加载/实体已被清理：下一 tick 再试
				}
				double[] local = SurvivorFormation.localOffset(i, total);
				double[] pos = survivorWorldPos(world, team, local);
				if (pos == null) {
					continue;
				}
				entity.setPosition(pos[0], pos[1], pos[2]);
				entity.setVelocity(0.0, 0.0, 0.0);
			}
		}
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
			// 漂流幸存者同样不无敌：可以被抢/被误杀，这是"海面资源有风险"的一部分
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
		SurvivorJob job = SurvivorJob.leastRepresented(jobCounts(team));
		UUID id = spawnOneVillager(server, team, 0, 1, job);
		if (id != null) {
			villagerIds.computeIfAbsent(team, k -> new ArrayList<>()).add(id);
			jobs.put(id, job);
		}
		announce(server, team, "§a[幸存者] 队伍 N" + team + " 招募了 1 名漂流幸存者·" + job.displayName()
				+ "（现 " + survivors.get(team) + "）");
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
		jobs.remove(id);
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
		jobs.clear();
	}

	/**
	 * 清掉上一局/重启残留的幸存者实体。
	 *
	 * <p>为什么需要：村民是 {@code setPersistent()}，但 {@link #reset()} 只能删内存表里
	 * 记着的 UUID；重启后内存表为空，上一局的村民会留在世界里并累积。
	 * （2026-11-14 探针实测：同一队 0 出现两只同名村民。）
	 * 只按我们自己的名字前缀清，避免误伤地图作者放的装饰村民。
	 *
	 * @return 实际清掉的实体数
	 */
	private static int sweepStaleSurvivors(MinecraftServer srv) {
		ServerWorld world = srv.getOverworld();
		int removed = 0;
		for (Entity e : world.getEntitiesByType(EntityType.VILLAGER, v -> {
			Text name = v.getCustomName();
			if (name == null) {
				return false;
			}
			String s = name.getString();
			return s.startsWith("§a幸存者·队") || "§e漂流幸存者".equals(s);
		})) {
			e.discard();
			removed++;
		}
		return removed;
	}

	/** 局后清掉海面漂流幸存者；它们也是持久实体，不能只清 UUID 列表。 */
	private static void clearFloating() {
		if (server == null) {
			floatingIds.clear();
			return;
		}
		for (UUID id : floatingIds) {
			Entity e = server.getOverworld().getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		floatingIds.clear();
	}

	/** PLAY 每 tick：每秒扣口粮；断粮则每 30s 离队 1 人。 */
	public static void onEndTick(MinecraftServer srv) {
		if (survivors.isEmpty()) {
			return;
		}
		tick++;
		syncCounts();   // 被击杀的幸存者先从计数表里去掉，再算口粮压力
		followRafts(srv);   // 每 tick 贴回筏面；放在口粮结算之前，位置与生存逻辑互不依赖
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
	 * 生产岗位效率（02 §9）：队伍里有<b>对应职业</b>的幸存者时，设施周期 −30%。
	 *
	 * @param job 该设施对应的岗位（过滤器=水手、厨台/堆肥=厨师、医疗箱=医护）
	 * @return 0.7 = 有该职业、1.0 = 没有（无队伍也按 1.0）
	 */
	public static double productionSpeedFor(ServerPlayerEntity player, SurvivorJob job) {
		var session = RoundManager.machine().session(player.getUuid());
		if (session == null || session.teamIndex() < 0) {
			return 1.0;
		}
		return hasJob(session.teamIndex(), job) ? 0.7 : 1.0;
	}

	/** 本队还在筏上的卫兵实体 UUID（守筏 AI 用；顺序与阵位无关）。 */
	public static List<UUID> guards(int team) {
		List<UUID> out = new ArrayList<>();
		List<UUID> list = villagerIds.get(team);
		if (list != null) {
			for (UUID id : list) {
				if (jobs.get(id) == SurvivorJob.GUARD) {
					out.add(id);
				}
			}
		}
		return out;
	}

	/** 本队是否有该职业的幸存者（只算活着的：被杀掉的职业不能再提供加成）。 */
	public static boolean hasJob(int team, SurvivorJob job) {
		List<UUID> list = villagerIds.get(team);
		if (list == null || server == null) {
			return false;
		}
		var world = server.getOverworld();
		for (UUID id : list) {
			if (jobs.get(id) != job) {
				continue;
			}
			var ent = world.getEntity(id);
			if (ent != null && ent.isAlive()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * dev/探针夹具：把某队第 {@code index} 名筏上幸存者的职业改成 {@code job}。
	 *
	 * <p>不改人数、口粮与阵位，只改职业表与实体名。自动化探针要验"舵手/工匠/卫兵"
	 * 这类职业效果，不能让开局分配顺序（生产职业优先）挡住——真人招募到第 5 人才会
	 * 自然出现舵手。命令入口是 {@code /cww survivor job <队> <序号> <职业>}。
	 *
	 * @return 可读结果；索引越界时说明现有几名，不抛异常
	 */
	public static String setJob(int team, int index, SurvivorJob job) {
		List<UUID> list = villagerIds.get(team);
		int have = list == null ? 0 : list.size();
		if (list == null || index < 0 || index >= have) {
			return "队 " + team + " 没有第 " + index + " 名幸存者（现有 " + have + " 名）";
		}
		UUID id = list.get(index);
		jobs.put(id, job);
		if (server != null) {
			Entity entity = server.getOverworld().getEntity(id);
			if (entity != null) {
				entity.setCustomName(Text.literal("§a幸存者·队" + team + "·" + job.displayName()));
			}
		}
		NBW.LOGGER.info("[survivor] 队 {} 第 {} 名幸存者职业改为 {}", team, index, job.name());
		return "队 " + team + " 第 " + index + " 名幸存者已改为 " + job.displayName();
	}

	/** 本队当前各职业人数（只统计仍在筏上的实体）。 */
	public static Map<SurvivorJob, Integer> jobCounts(int team) {
		Map<SurvivorJob, Integer> counts = new EnumMap<>(SurvivorJob.class);
		List<UUID> list = villagerIds.get(team);
		if (list != null) {
			for (UUID id : list) {
				SurvivorJob job = jobs.get(id);
				if (job != null) {
					counts.merge(job, 1, Integer::sum);
				}
			}
		}
		return counts;
	}

	/**
	 * 本队还有幸存者吗（反偷家 ×0.25 的判定用，01 §A3-v2）。
	 *
	 * <p><b>必须查"活着的实体"，不能只看计数表</b>：幸存者被击杀后计数表不会自动减，
	 * 只看计数会让反偷家永远解除不了（2026-11-19 放开击杀后踩到）。
	 * 计数表只用于口粮压力，与"还有没有人守筏"是两件事。
	 */
	public static boolean hasSurvivors(int team) {
		var list = villagerIds.get(team);
		if (list == null || list.isEmpty() || server == null) {
			return false;
		}
		var world = server.getOverworld();
		for (UUID id : list) {
			var e = world.getEntity(id);
			if (e != null && e.isAlive()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 把"人数 / 阵位 / 职业"三张表同步成实际活着的实体。
	 *
	 * <p>被击杀的幸存者必须从 {@link #villagerIds} 和 {@link #jobs} 里一起摘掉，否则：
	 * <ul>
	 *   <li>{@link #hasJob} 还会认为工匠/卫兵/舵手在场，击杀后再造/守卫仍然享受加成；</li>
	 *   <li>{@link #jobCounts} 会把死人的职业算进读数，运维看到的人数对不上。</li>
	 * </ul>
	 * 只降不升，避免实体数与计数表短暂不一致时把人数算多。
	 * （2026-11-20 复查时由"击杀幸存者"场景发现：计数归零但职业列表还挂着水手。）
	 */
	private static void syncCounts() {
		if (server == null) {
			return;
		}
		var world = server.getOverworld();
		for (Map.Entry<Integer, List<UUID>> entry : villagerIds.entrySet()) {
			List<UUID> list = entry.getValue();
			int alive = 0;
			java.util.Iterator<UUID> it = list.iterator();
			while (it.hasNext()) {
				UUID id = it.next();
				var ent = world.getEntity(id);
				if (ent != null && ent.isAlive()) {
					alive++;
				} else {
					it.remove();      // 死掉的从阵位表摘掉
					jobs.remove(id);  // 职业也一并摘掉，否则加成还在
				}
			}
			Integer cur = survivors.get(entry.getKey());
			if (cur != null && alive < cur) {
				NBW.LOGGER.info("[survivor] 队 {} 存活数下降：{} -> {}（被击杀）",
						entry.getKey(), cur, alive);
				survivors.put(entry.getKey(), alive);
			}
		}
	}

	/** 某队的幸存者/口粮读数。 */
	public static String teamStatus(int team) {
		int count = survivors.getOrDefault(team, 0);
		double prov = provisions.getOrDefault(team, 0.0);
		StringBuilder jobsText = new StringBuilder();
		for (Map.Entry<SurvivorJob, Integer> e : jobCounts(team).entrySet()) {
			if (jobsText.length() > 0) {
				jobsText.append(',');
			}
			jobsText.append(e.getKey().displayName()).append(e.getValue());
		}
		return String.format(Locale.ROOT, "队%d 幸存者=%d 口粮=%.1f 职业[%s]",
				team, count, prov, jobsText);
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
		clearFloating();
		survivors.clear();
		provisions.clear();
		jobs.clear();
		tick = 0;
		server = null;
	}
}
