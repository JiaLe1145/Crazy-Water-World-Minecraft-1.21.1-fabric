package dev.nbw.rescue;

import dev.nbw.NBW;
import dev.nbw.domain.raft.RaftLayout;
import dev.nbw.domain.rescue.ClingRule;
import dev.nbw.domain.rescue.ClingRule.State;
import dev.nbw.domain.rescue.ReachRule;
import dev.nbw.domain.round.Phase;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.round.RoundManager;
import dev.nbw.sable.SableRafts;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 落水、扒边与伸手救援的适配层（M2-8，01 §8）。
 *
 * <p><b>判定不在这里。</b>时序、距离、打断条件全在 {@code domain/rescue} 那两个纯规则里：
 * {@link ClingRule}（自救：在水里 + 扒自家船壳 + 长按 2 秒）与 {@link ReachRule}
 * （队友救援：<b>动作在救援方</b>，1 秒把人拉回）。这里只把玩家换算成
 * "筏的局部坐标 + 在水里吗 + 松手了吗 + 这 tick 挨打了吗"。
 *
 * <p><b>换算只有一条正确的路。</b>实体用 {@link SableRafts#worldToLocal}（位姿逆变换），
 * 落点用 {@link SableRafts#localToWorld}（同一个位姿的正变换）。
 * 不能用"筏的存储区块"或世界坐标比距离：筏子会开走，那样算出来的间隙在筏子动起来之后就是假的，
 * 玩家会看到"明明贴着船壳却扒不住"。这条与建造规则同源（{@code dev/M2-RESULTS.md} §3.12、§3.15）。
 *
 * <p><b>抬升用"局部插值 → 世界"，不用施力，也不用"每 tick 抬固定格数"，更不能只抬 Y。</b>
 * 三个坑都是实测或推演确认的：施力会被水的阻尼吃掉；"每 tick 抬 1.3/40 格"在水里 2.6 秒只净涨
 * 0.016 格，"2 秒回筏"就变成由水的参数决定；而只抬 Y 会让在离壳两格半处扒住的人
 * 爬到顶时站在筏子旁边的半空里，下一步掉回海里——01 §8 说的"爬回筏"根本没兑现。
 * 现在起爬／伸手那一拍钉住起点局部坐标，终点一律是 {@code landingLocal} 夹进甲板内的那一格，
 * 每拍再换算回世界坐标：泡得多深、筏子开多远都不影响"终点一定是甲板面"。
 *
 * <p><b>一趟里先看清全场，再判配对，最后才写状态与位置。</b>这样"被队友拉着的人"与
 * "自己在爬的人"不会在同一 tick 里被两处写入顶掉：{@link #reachVictim} 决定谁归救援方管，
 * 其余归自己管（{@code pulled} 那张表就是这个用途）。
 * 代价是这是服务端权威位移，真客户端手感必须实机确认（§6 真人清单）。
 */
public final class RescueService {

	/** 长按/命令续包的有效窗口：断输入 4 tick 就算松手（与桨台同一口径，#37 验过这条有效）。 */
	private static final int KEEPALIVE_WINDOW = 4;

	/** 自救状态，键=正在扒边的人。 */
	private static final Map<UUID, State> states = new HashMap<>();
	/** 伸手救援状态，键=<b>救援方</b>；被救者由 {@link #reachVictim} 记。 */
	private static final Map<UUID, ReachRule.State> reachStates = new HashMap<>();
	/** 救援方 → 被救者。同一个人一 tick 只许被一个人拉，否则两处写入互相顶。 */
	private static final Map<UUID, UUID> reachVictim = new HashMap<>();
	/** 起点与落点都存在甲板局部帧里：起点=开始那一拍人的位置，落点=夹进甲板内的最近一格。 */
	private static final Map<UUID, double[]> climbBase = new HashMap<>();
	private static final Map<UUID, double[]> climbTarget = new HashMap<>();
	/** 营养 <20 时攀爬失败过一次的人（02 §8：只失败 1 次，第二次照常爬上来）。 */
	private static final Set<UUID> failedOnce = new HashSet<>();

	private static final Map<UUID, Long> lastHold = new HashMap<>();
	private static final Set<UUID> damagedThisTick = new HashSet<>();
	private static final Deque<String> events = new ArrayDeque<>();
	private static final int EVENT_KEEP = 24;

	private static long grabs;
	private static long landings;
	private static long knockedOff;
	private static long pulls;
	private static long pullAborts;

	private RescueService() {
	}

	/** 一名玩家这一拍的全部观察结果；玩法判定只读这张表，不回头改世界。 */
	private record Obs(ServerPlayerEntity player, UUID id, int team, @Nullable UUID raftId, double[] local,
			boolean located, double gap, boolean inWater, boolean holding, boolean damaged) {
	}

	/** 伤害打断的监听：这版事件只能"告知打了多少"，正好够打断用（改数值做不到，见 ClingRule 注释）。 */
	public static void register() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (entity instanceof ServerPlayerEntity sp && damageTaken > 0.0f && !blocked) {
				damagedThisTick.add(sp.getUuid());
			}
		});
		NBW.LOGGER.info("[rescue] 落水与救援已挂载：打断=AFTER_DAMAGE，自救={} tick（2 秒），伸手={} tick（1 秒），"
				+ "伸手距离≤{} 格，落点内缩 {} 格", ClingRule.CLIMB_TICKS, ReachRule.REACH_TICKS,
				ReachRule.HAND_REACH, ClingRule.LANDING_INSET);
	}

	// ---------------------------------------------------------------- 输入

	/** 客户端长按包（对着筏体外壳或甲板方块时）与 {@code /cww rescue hold|reach} 都走这里。 */
	public static void keepalive(UUID player) {
		lastHold.put(player, currentTick());
	}

	/** 客户端松手：清掉续包窗口，下一拍状态机自己把人放回水里（不直接改状态，免得两路写入打架）。 */
	public static void release(UUID player) {
		lastHold.remove(player);
	}

	/**
	 * 当前 tick 由 {@link #onEndTick} 每 tick 刷新（这版 yarn 没有 {@code MinecraftServer.getServer()}，
	 * 而且"续包窗口"本来就该跟服务端心跳走同一个时钟：用系统时间会出现服务端卡住时窗口先到期）。
	 * 只在服务端线程读写，不需要并发保护。
	 */
	private static long tickNow;

	private static long currentTick() {
		return tickNow;
	}

	/**
	 * 探针与运维用的"模拟扒边"：不依赖客户端输入。
	 *
	 * @return 一行结论（含拒绝原因，方便断言"越权扒筏"确实被拒）
	 */
	public static String hold(MinecraftServer server, @Nullable String playerName, int ticks) {
		ServerPlayerEntity player = playerByName(server, playerName);
		if (player == null) {
			return "找不到在线玩家 " + playerName;
		}
		var session = RoundManager.machine().session(player.getUuid());
		int team = session == null ? -1 : session.teamIndex();
		if (team < 0) {
			return "你没有分队，扒不了任何筏（状态未改动）";
		}
		UUID raftId = RaftSpawner.raftOf(team);
		if (raftId == null) {
			return "队 " + team + " 这一局没有筏，无从扒起（状态未改动）";
		}
		double[] local = SableRafts.worldToLocal(server.getOverworld(), raftId,
				player.getX(), player.getY(), player.getZ());
		if (Double.isNaN(local[0])) {
			return "读不到筏的位姿（筏可能已沉没），扒边取消（状态未改动）";
		}
		double gap = ClingRule.hullGap(local[0], local[2], half());
		if (gap > ClingRule.HULL_REACH) {
			return String.format(Locale.ROOT, "离船壳 %.2f 格，伸手够不着（上限 %.2f）：得靠队友救",
					gap, ClingRule.HULL_REACH);
		}
		lastHold.put(player.getUuid(), server.getTicks() + (long) ticks);
		record(String.format(Locale.ROOT, "%s 开始扒边（队 %d，间隙 %.2f 格，本地 (%.2f,%.2f,%.2f)）",
				player.getName().getString(), team, gap, local[0], local[1], local[2]));
		return String.format(Locale.ROOT, "已登记扒边 %d tick（间隙 %.2f 格）", ticks, gap);
	}

	/**
	 * 探针与运维用的"模拟伸手"：<b>登记在救援方身上</b>，被救者不需要任何输入
	 * （2026-11-06 用户裁决：动作在救援方那一侧）。
	 *
	 * <p>挑人用的就是 tick 里那一条 {@link #pickVictim}，所以这一行返回的不只是"成不成"，
	 * 还带上了配对了谁、距离几格——探针要断言"拉的是不是那个人"。
	 *
	 * @return 一行结论（含拒绝原因）
	 */
	public static String reach(MinecraftServer server, @Nullable String rescuerName, int ticks) {
		ServerWorld world = server.getOverworld();
		if (world == null) {
			return "服务端未就绪";
		}
		ServerPlayerEntity rescuer = playerByName(server, rescuerName);
		if (rescuer == null) {
			return "找不到在线玩家 " + rescuerName;
		}
		final boolean play = RoundManager.phase() == Phase.PLAY;
		Collection<Obs> everyone = observeAll(world);
		Obs r = null;
		for (Obs o : everyone) {
			if (o.id.equals(rescuer.getUuid())) {
				r = o;
			}
		}
		if (r == null) {
			return "读不到救援方的位置";
		}
		if (r.raftId == null) {
			return "救援方没有分队或这一局没筏，往哪儿拉人（状态未改动）";
		}
		Pick pick = pickVictim(r, everyone, null, play);
		if (pick.victim() == null) {
			return "没有可拉的人：" + pick.denied() + "（状态未改动）";
		}
		Obs victim = pick.victim();
		double d = ReachRule.handDistance(r.local[0], r.local[2], victim.local[0], victim.local[2]);
		lastHold.put(r.id, server.getTicks() + (long) ticks);
		reachVictim.put(r.id, victim.id);
		climbBase.put(victim.id, victim.local.clone());
		climbTarget.put(victim.id, ClingRule.landingLocal(victim.local[0], victim.local[2], half()));
		record(String.format(Locale.ROOT, "%s 伸手去拉 %s（队 %d，距离 %.2f 格，登记 %d tick）",
				r.player.getName().getString(), victim.player.getName().getString(), r.team, d, ticks));
		return String.format(Locale.ROOT, "已登记伸手 %d tick：救援方=%s 被救者=%s 距离 %.2f 格",
				ticks, r.player.getName().getString(), victim.player.getName().getString(), d);
	}

	// ---------------------------------------------------------------- 每 tick

	/** 只在 PLAY 阶段调用（回合系统那边统一控权限，04 §2 不变式 4）。 */
	public static void onEndTick(MinecraftServer server) {
		tickNow = server.getTicks();
		ServerWorld world = server.getOverworld();
		if (world == null) {
			return;
		}
		final boolean play = RoundManager.phase() == Phase.PLAY;
		final int half = half();

		// 1) 先看清场上每个人：这一步一行玩法都不改
		Collection<Obs> everyone = observeAll(world);

		// 2) 救援配对先走：谁正在被拉，决定第 3 步里哪些人不归自己管
		Set<UUID> pulled = new HashSet<>();
		for (Obs r : everyone) {
			if (!r.holding && !isReaching(r.id)) {
				continue;   // 没按着、也没有进行中的救援：不必找目标
			}
			stepReach(r, everyone, pulled, play, half);
		}

		// 3) 自救状态机；被队友拉着的人这一拍由救援方驱动位置，这里跳过他，避免两处写入
		for (Obs o : everyone) {
			if (pulled.contains(o.id)) {
				continue;
			}
			stepCling(o, play, half);
		}
		damagedThisTick.clear();
	}

	private static int half() {
		return RaftLayout.halfWidth(RaftSpawner.deckSide());
	}

	private static Collection<Obs> observeAll(ServerWorld world) {
		int half = half();
		Map<UUID, Obs> out = new LinkedHashMap<>();
		for (ServerPlayerEntity p : world.getPlayers()) {
			Obs o = observe(world, p, half);
			out.put(o.id(), o);
		}
		return out.values();
	}

	/** 把一名玩家换算成一条观察记录。 */
	private static Obs observe(ServerWorld world, ServerPlayerEntity player, int half) {
		UUID id = player.getUuid();
		Long held = lastHold.get(id);
		boolean holding = held != null && tickNow - held < KEEPALIVE_WINDOW;
		var session = RoundManager.machine().session(id);
		int team = session == null ? -1 : session.teamIndex();
		UUID raftId = team < 0 ? null : RaftSpawner.raftOf(team);
		boolean raftPresent = raftId != null && SableRafts.present(world, raftId);
		double[] local = raftPresent
				? SableRafts.worldToLocal(world, raftId, player.getX(), player.getY(), player.getZ())
				: new double[]{Double.NaN, Double.NaN, Double.NaN};
		boolean located = !Double.isNaN(local[0]);
		double gap = located ? ClingRule.hullGap(local[0], local[2], half) : Double.MAX_VALUE;
		return new Obs(player, id, team, raftPresent ? raftId : null, local, located, gap,
				player.isTouchingWater(), holding, damagedThisTick.contains(id));
	}

	/** 自救那一趟：推进状态机 + 该抬人就抬。 */
	private static void stepCling(Obs o, boolean play, int half) {
		State prev = states.getOrDefault(o.id, State.off());
		State next = ClingRule.next(prev, play, o.raftId != null, o.raftId != null, o.holding,
				o.inWater, o.gap, o.damaged);

		if (next.stage() == ClingRule.Stage.GRAB && prev.stage() != ClingRule.Stage.GRAB) {
			grabs++;
			record(String.format(Locale.ROOT, "%s 扒住船壳（队 %d，间隙 %.2f 格，本地 (%.2f,%.2f,%.2f)）",
					o.player.getName().getString(), o.team, o.gap, o.local[0], o.local[1], o.local[2]));
			climbBase.put(o.id, o.local.clone());
			climbTarget.put(o.id, ClingRule.landingLocal(o.local[0], o.local[2], half));
		}
		if (o.damaged && prev.climbing()) {
			knockedOff++;
			record(String.format(Locale.ROOT, "%s 攀爬中被打断，掉回水里（已爬 %d tick）",
					o.player.getName().getString(), prev.tick()));
		}
		if (next.stage() == ClingRule.Stage.CLIMB) {
			liftTo(o, ClingRule.CLIMB_TICKS, next.tick(), "攀爬");
		}
		if (next.stage() == ClingRule.Stage.LANDED) {
			// 02 §8：体力（营养）< 20 时攀爬失败 1 次（第二次照常爬上来）
			if (dev.nbw.survival.SurvivalService.isNutritionLow(o.id) && !failedOnce.contains(o.id)) {
				failedOnce.add(o.id);
				states.remove(o.id);
				lastHold.remove(o.id);
				climbBase.remove(o.id);
				climbTarget.remove(o.id);
				record(String.format(Locale.ROOT, "%s 营养太低，攀爬失败 1 次（掉回水里）", o.player.getName().getString()));
				o.player.sendMessage(Text.literal("§c[cww] 体力不足，攀爬失败（再试一次）"), true);
				return;
			}
			landings++;
			double[] landing = climbTarget.getOrDefault(o.id, o.local);
			finishAtTarget(o);
			record(String.format(Locale.ROOT, "%s 扒边回筏成功（%d tick，落点本地 (%.2f,%.2f,%.2f)）",
					o.player.getName().getString(), next.tick(), landing[0], landing[1], landing[2]));
			o.player.sendMessage(Text.literal("§a[cww] 你爬回了木筏"), true);
		}
		if (next.stage() == State.off().stage() && prev.stage() != next.stage()) {
			states.remove(o.id);
			lastHold.remove(o.id);
			climbBase.remove(o.id);
			climbTarget.remove(o.id);
		} else {
			states.put(o.id, next);
		}
	}

	/**
	 * 伸手那一趟：找目标、推进、把被救者往落点上抬。
	 *
	 * <p>认领表与 {@code pulled} 的登记都在写入之前：一个被救者被两个救援方同时拉着，
	 * 位置就会被两处来回顶成抖动的怪东西——而那正是 #39 里"两处各写一半"那一类病。
	 */
	private static void stepReach(Obs r, Collection<Obs> everyone, Set<UUID> pulled, boolean play, int half) {
		ReachRule.State prev = reachStates.getOrDefault(r.id, ReachRule.State.off());
		UUID keep = prev.reaching() ? reachVictim.get(r.id) : null;
		Pick pick = pickVictim(r, everyone, keep, play);
		Obs victim = pick.victim();
		boolean eligible = victim != null;

		ReachRule.State next = ReachRule.next(prev, r.holding, eligible, r.damaged);
		if (next.reaching() && !prev.reaching() && victim != null) {
			reachVictim.put(r.id, victim.id);
			climbBase.put(victim.id, victim.local.clone());
			climbTarget.put(victim.id, ClingRule.landingLocal(victim.local[0], victim.local[2], half));
			record(String.format(Locale.ROOT, "%s 开始伸手拉 %s（队 %d，距离 %.2f 格）",
					r.player.getName().getString(), victim.player.getName().getString(), r.team,
					ReachRule.handDistance(r.local[0], r.local[2], victim.local[0], victim.local[2])));
		}
		if (next.reaching() && victim != null) {
			pulled.add(victim.id);   // 这一拍的位置归救援方管，自救那一趟就不许再写同一个人
			liftTo(victim, ReachRule.REACH_TICKS, next.tick(), "被拉回");
		}
		if (next.stage() == ReachRule.Stage.PULLED && victim != null) {
			pulls++;
			pulled.add(victim.id);
			double[] landing = climbTarget.getOrDefault(victim.id, victim.local);
			finishAtTarget(victim);
			record(String.format(Locale.ROOT, "%s 把 %s 拉回筏上（%d tick，落点本地 (%.2f,%.2f,%.2f)）",
					r.player.getName().getString(), victim.player.getName().getString(), next.tick(),
					landing[0], landing[1], landing[2]));
			victim.player.sendMessage(Text.literal("§a[cww] 队友把你拉上了木筏"), true);
			r.player.sendMessage(Text.literal("§a[cww] 你把队友拉上了木筏"), true);
			// 被救者自己的扒边状态一并交掉：他已经上筏，留着会在下一拍跟普通移动打架。
			// 起点/落点也当场回收——救援已经结束，留着就是每次成功泄漏一对表项
			states.remove(victim.id);
			climbBase.remove(victim.id);
			climbTarget.remove(victim.id);
		}
		if (prev.reaching() && !next.reaching() && next.stage() != ReachRule.Stage.PULLED) {
			pullAborts++;
			record(String.format(Locale.ROOT, "%s 的伸手作废（%s），已拉 %d tick",
					r.player.getName().getString(), abortReason(r, pick), prev.tick()));
			if (keep != null) {
				climbBase.remove(keep);
				climbTarget.remove(keep);
			}
		}
		if (next.stage() == ReachRule.State.off().stage()) {
			reachStates.remove(r.id);
			reachVictim.remove(r.id);
		} else {
			reachStates.put(r.id, next);
		}
	}

	private static boolean isReaching(UUID id) {
		ReachRule.State s = reachStates.get(id);
		return s != null && s.reaching();
	}

	private static String abortReason(Obs r, Pick pick) {
		if (r.damaged) {
			return "救援方被打，手一抖";
		}
		if (!r.holding) {
			return "救援方松手";
		}
		// 把纯闸门给出的那一句原样带出来：排查"为什么每次都在半路作废"时，
		// 一句笼统的"目标不再合格"等于让人重新把六条门全查一遍（#41 就是这么走弯的）
		return pick.denied() == null ? "原因未定（候选人判定通过了却被打断）" : pick.denied();
	}

	/** 一次配对的结论：拉到的人（可以为 null）与"为什么不行"的原因串。 */
	private record Pick(@Nullable Obs victim, @Nullable String denied) {
	}

	/**
	 * 挑被救者，并把落选的原因一起带回去。
	 *
	 * <p><b>结构条件在这里，玩法数值一律交给 {@link ReachRule} 那两扇门。</b>
	 * 伸手距离、扒壳距离、在水里、阶段、筏在不在，全部只在纯函数里判一次；
	 * 这里如果再自己比一次距离，就会出现"挑人用 8 格、判定用 3.5 格"这种谁都没错、
	 * 合起来不对的裂缝。
	 *
	 * <p><b>开始与继续用的是两扇不同的门</b>：起拉那一拍要求被救者在水里，
	 * 而人一旦被抬起来就必然离开水面，继续还用同一扇门会在半路自己判自己作废
	 * （2026-11-06 实测就是这个形状：拉回不涨、伸手作废一路涨）。
	 *
	 * <p><b>进行中的救援只认原来那一个</b>（{@code keep}）：中途换人会把已经拉的 tick 记到新人头上，
	 * 变成"拉着拉着凭空把另一个人抬起来"。原目标不再合格就当场作废，下一拍重新开始。
	 *
	 * @param keep 已经在拉的人；null 表示这一趟还没配对人
	 */
	private static Pick pickVictim(Obs rescuer, Collection<Obs> everyone, @Nullable UUID keep, boolean play) {
		Obs best = null;
		double closest = Double.MAX_VALUE;
		String bestDenied = "场上没有第二个同队、能定位、又没被别人抢先认领的人";
		double nearestDist = Double.MAX_VALUE;
		String nearestDenied = bestDenied;
		for (Obs o : everyone) {
			if (!isCandidate(rescuer, o)) {
				continue;
			}
			double d = ReachRule.handDistance(rescuer.local[0], rescuer.local[2], o.local[0], o.local[2]);
			String denied = keep == null
					? ReachRule.denyReason(play, rescuer.raftId != null, o.team == rescuer.team,
							!o.id.equals(rescuer.id), o.inWater, o.gap, d)
					// 进行中不再查"在水里"和"伸手上限"：这两条都会被拉回这个动作本身否定
					: ReachRule.denyOngoing(play, rescuer.raftId != null, o.team == rescuer.team, true, o.gap);
			if (keep != null && keep.equals(o.id)) {
				// 认死人：这一趟只认他一个，别人再近也不换；他不合格就是这一整趟作废
				return new Pick(denied == null ? o : null, denied == null ? null : "原目标：" + denied);
			}
			if (keep != null) {
				continue;
			}
			if (d < nearestDist) {
				nearestDist = d;
				nearestDenied = denied == null ? "判定通过" : denied;
			}
			if (denied != null) {
				continue;
			}
			if (d < closest) {
				closest = d;
				best = o;
			}
		}
		// keep 那一支没在循环里命中（被抢先、换队、或位置读不到）也必须有原因，
		// 不然日志只会回一句"原因未定"，排查的人只能重新把六条门全查一遍
		if (keep != null) {
			return new Pick(null, "原目标已经不算候选人（换队了、被别人抢先了，或位置读不到）");
		}
		return new Pick(best, best == null ? nearestDenied : null);
	}

	/**
	 * 只问"这人有没有可能被拉"：不是救援方自己、同队、能定位、没被<b>别的</b>救援方抢先认领。
	 * 这里的每一条都是结构性的，换成别的口径（比如以后允许拉敌队）只改这一处。
	 *
	 * <p>"没被别人抢先"必须放行自己认领的那一个，否则进行中的救援下一拍就找不到原目标，
	 * 每次都会重新开始——1 秒根本拉不完。
	 */
	private static boolean isCandidate(Obs rescuer, Obs o) {
		if (o.id.equals(rescuer.id) || o.team != rescuer.team || !o.located) {
			return false;
		}
		UUID owner = reachVictimValueOf(o.id);
		return owner == null || owner.equals(rescuer.id);
	}

	/** 这个人已经被哪个救援方认领着；没有返回 null。 */
	private static @Nullable UUID reachVictimValueOf(UUID victimId) {
		for (Map.Entry<UUID, UUID> e : reachVictim.entrySet()) {
			if (victimId.equals(e.getValue())) {
				return e.getKey();
			}
		}
		return null;
	}

	/**
	 * 把这个人抬到"局部插值点"对应的世界坐标。
	 *
	 * <p>位置同步交给 {@code requestTeleport}：对玩家只 {@code setPosition} 不会把新位置告诉客户端，
	 * 人会立刻被橡皮筋拉回去。逐拍"局部插值→世界"而不是记世界偏移，筏子开走时人才跟得上（类注释）。
	 */
	private static void liftTo(Obs o, int climbTicks, int tick, String what) {
		double[] base = climbBase.get(o.id);
		double[] target = climbTarget.get(o.id);
		if (base == null || target == null || o.raftId == null) {
			return;   // 起点或落点没钉住（筏刚沉、状态被别处清掉）：下一拍的闸门负责收尾，这里不猜高度
		}
		double[] p = ClingRule.climbLocal(base, target, tick, climbTicks);
		double[] world = SableRafts.localToWorld(o.player.getServerWorld(), o.raftId, p[0], p[1], p[2]);
		if (Double.isNaN(world[0])) {
			return;
		}
		o.player.requestTeleport(world[0], world[1], world[2]);
		if (tick % 10 == 0) {
			// 每 10 拍留一行，探针才能把"局部点单调趋近落点"这条画出来
			NBW.LOGGER.info("[rescue] {} {} tick={} 局部=({},{},{}) 落点=({},{},{})",
					o.player.getName().getString(), what, tick, fmt(p[0]), fmt(p[1]), fmt(p[2]),
					fmt(target[0]), fmt(target[1]), fmt(target[2]));
		}
	}

	/** 收尾：直接放到落点上，并把速度清掉（带着一秒的入水速度站上甲板很难看）。 */
	private static void finishAtTarget(Obs o) {
		o.player.setVelocity(0.0, 0.0, 0.0);
		double[] target = climbTarget.get(o.id);
		if (target == null || o.raftId == null) {
			return;
		}
		double[] world = SableRafts.localToWorld(o.player.getServerWorld(), o.raftId,
				target[0], target[1], target[2]);
		if (!Double.isNaN(world[0])) {
			o.player.requestTeleport(world[0], world[1], world[2]);
		}
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.3f", v);
	}

	// ---------------------------------------------------------------- 观测

	private static void record(String line) {
		while (events.size() >= EVENT_KEEP) {
			events.removeLast();
		}
		events.addFirst(String.format(Locale.ROOT, "t=%d %s", currentTick(), line));
	}

	/** {@code /cww rescue status}：累计计数 + 每个在扒／在拉的人当前状态。 */
	public static String status(MinecraftServer server) {
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
				"[rescue] 扒住=%d 上筏=%d 被打落=%d 拉回=%d 伸手作废=%d｜窗口=%d tick 爬完=%d tick 伸手=%d tick"
						+ "｜伸手≤%.2f 格 落点内缩=%.2f 格 甲板面偏移=%.2f 扒壳≤%.2f 格",
				grabs, landings, knockedOff, pulls, pullAborts, KEEPALIVE_WINDOW, ClingRule.CLIMB_TICKS,
				ReachRule.REACH_TICKS, ReachRule.HAND_REACH, ClingRule.LANDING_INSET,
				ClingRule.DECK_SURFACE_OFFSET, ClingRule.HULL_REACH));
		ServerWorld world = server.getOverworld();
		if (world == null) {
			return sb.append("｜服务端未就绪").toString();
		}
		Map<UUID, Obs> byId = new LinkedHashMap<>();
		for (Obs o : observeAll(world)) {
			byId.put(o.id, o);
			State s = states.get(o.id);
			if (s != null && s.stage() != ClingRule.Stage.OFF) {
				sb.append(line("扒边", o, s.stage().toString(), s.tick()));
			}
			ReachRule.State r = reachStates.get(o.id);
			if (r != null && r.reaching()) {
				sb.append(line("伸手", o, "→" + nameOf(world, reachVictim.get(o.id)), r.tick()));
			}
		}
		// 被拉的人自己没有任何状态（动作在救援方那一侧），但他才是"有没有真的站上甲板"的证据：
		// 这一行的局部坐标与间隙就是探针断言落点夹进 footprint 的那个读数
		for (Map.Entry<UUID, UUID> e : reachVictim.entrySet()) {
			ReachRule.State r = reachStates.get(e.getKey());
			Obs victim = byId.get(e.getValue());
			if (r == null || !r.reaching() || victim == null) {
				continue;
			}
			sb.append(line("被拉", victim, "由" + nameOf(world, e.getKey()), r.tick()));
		}
		return sb.toString();
	}

	/** 一行个人读数：间隙与局部坐标都是甲板局部帧的数，筏子开走也不变。 */
	private static String line(String what, Obs o, String target, int tick) {
		return String.format(Locale.ROOT, "｜%s %s 队%d %s tick=%d 间隙=%.2f 水=%s 局部=(%.2f,%.2f,%.2f) Y=%.2f",
				o.player.getName().getString(), what, o.team, target, tick, o.gap, o.inWater,
				o.local[0], o.local[1], o.local[2], o.player.getY());
	}

	private static String nameOf(ServerWorld world, @Nullable UUID id) {
		if (id == null) {
			return "?";
		}
		ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(id);
		return p == null ? id.toString().substring(0, 8) : p.getName().getString();
	}

	/** 最近若干条救援事件，给探针取证。 */
	public static List<String> recent() {
		return new ArrayList<>(events);
	}

	private static @Nullable ServerPlayerEntity playerByName(MinecraftServer server, @Nullable String name) {
		if (name == null) {
			return null;
		}
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
		return p != null ? p : server.getPlayerManager().getPlayer(name.toLowerCase(Locale.ROOT));
	}

	/** 局后清理：状态与续包窗口都是局内状态，残留=下一局开局就有人在爬或被白送一次上筏。 */
	public static void reset() {
		states.clear();
		reachStates.clear();
		reachVictim.clear();
		climbBase.clear();
		climbTarget.clear();
		lastHold.clear();
		damagedThisTick.clear();
		failedOnce.clear();
	}
}
