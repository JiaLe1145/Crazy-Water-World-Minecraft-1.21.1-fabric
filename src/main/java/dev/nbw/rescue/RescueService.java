package dev.nbw.rescue;

import dev.nbw.NBW;
import dev.nbw.domain.raft.RaftLayout;
import dev.nbw.domain.rescue.ClingRule;
import dev.nbw.domain.rescue.ClingRule.State;
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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 落水与扒边的适配层（M2-8 第一步，01 §8）。
 *
 * <p><b>判定不在这里。</b>时序、距离、打断条件全在 {@code domain/rescue/ClingRule}；
 * 这里只把玩家换算成"筏的局部坐标 + 在水里吗 + 松手了吗 + 这 tick 挨打了吗"。
 *
 * <p><b>换算只有一条正确的路。</b>用 {@link SableRafts#worldToLocal}（位姿逆变换）。
 * 不能用"筏的存储区块"或世界坐标比距离：筏子会开走，那样算出来的间隙在筏子动起来之后就是假的，
 * 玩家会看到"明明贴着船壳却扒不住"。这条跟建造规则同源（{@code dev/M2-RESULTS.md} §3.12）。
 *
 * <p><b>攀爬用绝对高度插值，不用施力，也不用"每 tick 抬固定格数"。</b>
 * 两个坑都实测踩过：施力会被水的阻尼吃掉；而"每 tick 抬 1.3/40 格"在水里 2.6 秒只净涨 0.016 格，
 * 因为下沉几乎抵消了抬升——那样"2 秒回筏"就是由水的参数决定，不是由 01 §8 决定。
 * 现在 GRAB 那拍钉住起点 Y，之后每一拍把人放到 {@code climbY(baseY, 甲板面, tick)}：
 * 泡得多深只影响起点，终点一定是甲板面，时序只由 {@code CLIMB_TICKS} 说话。
 * 代价是这是服务端权威位移，真客户端手感必须实机确认（§6 真人清单）。
 */
public final class RescueService {

	/** 长按/命令续包的有效窗口：断输入 4 tick 就算松手（与桨台同一口径，#37 验过这条有效）。 */
	private static final int KEEPALIVE_WINDOW = 4;

	private static final Map<UUID, State> states = new HashMap<>();
	/** 开始扒边时人所在的 Y：攀爬是"从这里插值到甲板面"，所以起点必须在 GRAB 那拍钉住。 */
	private static final Map<UUID, Double> climbBaseY = new HashMap<>();
	private static final Map<UUID, Long> lastHold = new HashMap<>();
	private static final Set<UUID> damagedThisTick = new HashSet<>();
	private static final Deque<String> events = new ArrayDeque<>();
	private static final int EVENT_KEEP = 24;

	private static long grabs;
	private static long landings;
	private static long knockedOff;

	private RescueService() {
	}

	/** 伤害打断的监听：这版事件只能"告知打了多少"，正好够打断用（改数值做不到，见 ClingRule 注释）。 */
	public static void register() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (entity instanceof ServerPlayerEntity sp && damageTaken > 0.0f && !blocked) {
				damagedThisTick.add(sp.getUuid());
			}
		});
		NBW.LOGGER.info("[rescue] 落水与扒边已挂载：打断=AFTER_DAMAGE，时序={} tick（2 秒）", ClingRule.CLIMB_TICKS);
	}

	// ---------------------------------------------------------------- 输入

	/** 客户端长按包（对着筏体外壳时）与 {@code /cww rescue hold} 都走这里。 */
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
		double gap = ClingRule.hullGap(local[0], local[2], RaftLayout.halfWidth(RaftSpawner.deckSide()));
		if (gap > ClingRule.HULL_REACH) {
			return String.format(Locale.ROOT, "离船壳 %.2f 格，伸手够不着（上限 %.2f）：得靠队友救",
					gap, ClingRule.HULL_REACH);
		}
		long tick = server.getTicks();
		for (int i = 0; i < ticks; i++) {
			lastHold.put(player.getUuid(), tick + i);
		}
		lastHold.put(player.getUuid(), tick + ticks);
		record(String.format(Locale.ROOT, "%s 开始扒边（队 %d，间隙 %.2f 格，本地 y=%.2f）",
				player.getName().getString(), team, gap, local[1]));
		return String.format(Locale.ROOT, "已登记扒边 %d tick（间隙 %.2f 格）", ticks, gap);
	}

	// ---------------------------------------------------------------- 每 tick

	/** 只在 PLAY 阶段调用（回合系统那边统一控权限，04 §2 不变式 4）。 */
	public static void onEndTick(MinecraftServer server) {
		tickNow = server.getTicks();
		ServerWorld world = server.getOverworld();
		if (world == null) {
			return;
		}
		long tick = server.getTicks();
		int side = RaftSpawner.deckSide();
		int half = RaftLayout.halfWidth(side);

		for (ServerPlayerEntity player : world.getPlayers()) {
			UUID id = player.getUuid();
			State prev = states.getOrDefault(id, State.off());
			Long held = lastHold.get(id);
			boolean holding = held != null && tick - held < KEEPALIVE_WINDOW;
			var session = RoundManager.machine().session(id);
			int team = session == null ? -1 : session.teamIndex();
			UUID raftId = team < 0 ? null : RaftSpawner.raftOf(team);
			boolean raftPresent = raftId != null && SableRafts.present(world, raftId);
			double[] local = raftPresent
					? SableRafts.worldToLocal(world, raftId, player.getX(), player.getY(), player.getZ())
					: new double[]{Double.NaN, Double.NaN, Double.NaN};
			boolean located = !Double.isNaN(local[0]);
			double gap = located ? ClingRule.hullGap(local[0], local[2], half) : Double.MAX_VALUE;
			boolean damaged = damagedThisTick.contains(id);

			State next = ClingRule.next(prev, RoundManager.phase() == Phase.PLAY, raftPresent && located,
					raftPresent, holding, player.isTouchingWater(), gap, damaged);

			if (next.stage() == ClingRule.Stage.GRAB && prev.stage() != ClingRule.Stage.GRAB) {
				grabs++;
				record(String.format(Locale.ROOT, "%s 扒住船壳（队 %d，间隙 %.2f 格，本地 (%.2f,%.2f,%.2f)）",
						player.getName().getString(), team, gap, local[0], local[1], local[2]));
			}
			if (damaged && prev.climbing()) {
				knockedOff++;
				record(String.format(Locale.ROOT, "%s 攀爬中被打断，掉回水里（已爬 %d tick）",
						player.getName().getString(), prev.tick()));
			}
			if (next.stage() == ClingRule.Stage.GRAB && prev.stage() != ClingRule.Stage.GRAB) {
				climbBaseY.put(id, player.getY());   // 起点在这一拍钉住，之后不再跟着下沉漂走
			}
			if (next.stage() == ClingRule.Stage.CLIMB) {
				lift(player, next.tick(), raftId);
			}
			if (next.stage() == ClingRule.Stage.LANDED) {
				landings++;
				player.setVelocity(0.0, 0.0, 0.0);
				double deck = ClingRule.deckSurfaceY(SableRafts.position(world, raftId)[1]);
				record(String.format(Locale.ROOT, "%s 扒边回筏成功（%d tick，Y %.2f → %.2f，甲板面 %.2f）",
						player.getName().getString(), next.tick(), climbBaseY.getOrDefault(id, 0.0),
						deck, deck));
				player.sendMessage(Text.literal("§a[cww] 你爬回了木筏"), true);
			}
			if (next.stage() == State.off().stage() && prev.stage() != next.stage()) {
				states.remove(id);
				lastHold.remove(id);
			} else {
				states.put(id, next);
			}
		}
		damagedThisTick.clear();
	}

	/**
	 * 抬升一格的一小步。用定点位移而不是施力：施力会被水的阻尼与重力吃掉一部分，
	 * "2 秒能不能回筏"就变成由水的参数决定。位置同步交给 requestTeleport（对玩家只 setPosition
	 * 不会把新位置告诉客户端，人会立刻被橡皮筋拉回去）。
	 */
	private static void lift(ServerPlayerEntity player, int tick, UUID raftId) {
		if (!ClingRule.liftsThisTick(ClingRule.Stage.CLIMB)) {
			return;
		}
		ServerWorld world = player.getServerWorld();
		double poseY = SableRafts.position(world, raftId)[1];
		if (Double.isNaN(poseY)) {
			return;   // 筏没了：下一拍的 denyReason 会把状态清掉，这里不猜高度
		}
		double base = climbBaseY.getOrDefault(player.getUuid(), player.getY());
		double ny = ClingRule.climbY(base, ClingRule.deckSurfaceY(poseY), tick);
		player.requestTeleport(player.getX(), ny, player.getZ());
		if (tick % 10 == 0) {
			// 每 10 拍留一行，探针才能把"高度随 tick 线性上升、终点落在甲板面"这条画出来
			NBW.LOGGER.info("[rescue] {} 攀爬中 tick={} Y={} 甲板面={}", player.getName().getString(), tick,
					String.format(Locale.ROOT, "%.3f", ny),
					String.format(Locale.ROOT, "%.3f", ClingRule.deckSurfaceY(poseY)));
		}
	}

	// ---------------------------------------------------------------- 观测

	private static void record(String line) {
		while (events.size() >= EVENT_KEEP) {
			events.removeLast();
		}
		events.addFirst(String.format(Locale.ROOT, "t=%d %s", currentTick(), line));
	}

	/** {@code /cww rescue status}：累计计数 + 每个在扒的人当前状态。 */
	public static String status(MinecraftServer server) {
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
				"[rescue] 扒住=%d 上筏=%d 被打落=%d｜窗口=%d tick 爬完=%d tick 甲板面偏移=%.2f 伸手=%.2f 格",
				grabs, landings, knockedOff, KEEPALIVE_WINDOW, ClingRule.CLIMB_TICKS,
				ClingRule.DECK_SURFACE_OFFSET, ClingRule.HULL_REACH));
		ServerWorld world = server.getOverworld();
		if (world == null) {
			return sb.append("｜服务端未就绪").toString();
		}
		int side = RaftSpawner.deckSide();
		int half = RaftLayout.halfWidth(side);
		for (ServerPlayerEntity player : world.getPlayers()) {
			State s = states.get(player.getUuid());
			if (s == null || s.stage() == ClingRule.Stage.OFF) {
				continue;
			}
			var session = RoundManager.machine().session(player.getUuid());
			int team = session == null ? -1 : session.teamIndex();
			UUID raftId = team < 0 ? null : RaftSpawner.raftOf(team);
			double[] local = raftId == null ? new double[]{Double.NaN, Double.NaN, Double.NaN}
					: SableRafts.worldToLocal(world, raftId, player.getX(), player.getY(), player.getZ());
			sb.append(String.format(Locale.ROOT, "｜%s 队%d %s tick=%d 间隙=%.2f 水=%s Y=%.2f",
					player.getName().getString(), team, s.stage(), s.tick(),
					Double.isNaN(local[0]) ? -1 : ClingRule.hullGap(local[0], local[2], half),
					player.isTouchingWater(), player.getY()));
		}
		return sb.toString();
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

	/** 局后清理：状态与续包窗口都是局内状态，残留=下一局开局就有人在爬或白送一次上筏。 */
	public static void reset() {
		states.clear();
		climbBaseY.clear();
		lastHold.clear();
		damagedThisTick.clear();
	}
}
