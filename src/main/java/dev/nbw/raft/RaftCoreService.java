package dev.nbw.raft;

import dev.nbw.NBW;
import dev.nbw.domain.raft.ChannelRule;
import dev.nbw.domain.raft.ChannelRule.Channel;
import dev.nbw.domain.raft.ChannelRule.Inputs;
import dev.nbw.domain.raft.ChannelRule.Outcome;
import dev.nbw.domain.raft.ChannelRule.Result;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;

/**
 * 筏心与残骸修复的运行时（01 §4.3.2 已批准 A2 + A2-v2）。
 *
 * <p>判定全在 {@link ChannelRule}（纯函数）和 {@code SinkRule} 里，本类只做三件事：
 * 记住每队筏心的状态、每 tick 把事实喂给规则、把规则结论翻译成动作。
 *
 * <p><b>当前边界（筏心方块本体还没实现，属 M2 下一片）</b>：
 * <ul>
 *   <li>交互距离无法校验——没有方块坐标就没有"离残骸多远"。距离判定留到方块落地时补，
 *       现在 {@code interacting} 只包含"在线 + 活着 + 有 keepalive"。</li>
 *   <li>持续右键的<b>客户端输入</b>路径还没接（需要自定义包：客户端检测长按并周期发 keepalive）。
 *       为了不让这条规则只能等真人验证，下面提供了 {@link #hold} 作为 dev 侧的等价入口，
 *       由 {@code /cww raftcore hold} 驱动，服务端行为与客户端接入后完全一致。</li>
 * </ul>
 */
public final class RaftCoreService {

	public enum Phase { INTACT, WRECKAGE }

	/**
	 * 每局恢复次数上限。
	 * <p>沿用 {@code [A3]} 的"每局 1 次"额度——见 01 §4.3.2 待确认项，
	 * 用户若决定不限量，把这里改成 {@code Integer.MAX_VALUE} 并删掉 A3 的建材重修。
	 */
	public static final int MAX_RESTORES_PER_MATCH = 1;

	/** keepalive 的有效期（tick）。客户端应至少每这么多 tick 续一次。 */
	public static final int KEEPALIVE_TICKS = 4;

	private static final Map<Integer, Phase> phases = new LinkedHashMap<>();
	private static final Map<Integer, Channel> channels = new LinkedHashMap<>();
	private static final Map<Integer, Integer> restoresUsed = new LinkedHashMap<>();
	/** 队号 → 正在引导的玩家。同一残骸同时只认一个人。 */
	private static final Map<Integer, UUID> holder = new LinkedHashMap<>();
	private static final Map<Integer, Integer> keepaliveUntil = new LinkedHashMap<>();

	/** 已经报过"引导者查不到实体"的队，避免每 tick 刷屏。 */
	private static final Set<Integer> vanished = new java.util.HashSet<>();

	/**
	 * 方块坐标 → 归属队伍。BE 第一次被 Sable tick 时登记进来。
	 * 键用 {@code BlockPos.asLong()}：这张表活过整个对局，存 BlockPos 对象
	 * 等于存一个可能已经换世界的引用。
	 */
	private static final Map<Long, Integer> coreByPos = new LinkedHashMap<>();

	/** 相位→方块状态的回写次数，给状态与探针断言用。 */
	private static int stateSyncs;
	/** 本 tick 受过伤害的玩家，{@link #onEndTick} 用完即清。 */
	private static final Set<UUID> damagedThisTick = Collections.synchronizedSet(new java.util.HashSet<>());

	/**
	 * 模拟引导的截止 tick（自动化测试用）。
	 *
	 * <p>为什么不在命令里直接循环推进：命令跑在 RCON/服务端线程上，
	 * 自己 sleep 会把整个服务端卡住，自己调 step 又会与正常 tick 循环<b>重复推进</b>。
	 * 所以命令只登记"模拟到第 N tick"，推进仍然只由 {@link #onEndTick} 一处做。
	 */
	private static final Map<Integer, UUID> simulatedHolder = new LinkedHashMap<>();
	private static final Map<Integer, Integer> simulatedHoldUntil = new LinkedHashMap<>();

	private static int tick;

	private RaftCoreService() {
	}

	// ---------------------------------------------------------------- 筏心生命周期

	public static boolean isWreckage(int teamIndex) {
		return phases.get(teamIndex) == Phase.WRECKAGE;
	}

	/** 筏心被打掉 → 留下残骸，并开始 30s 沉筏倒计时（由 RaftSinking 每 tick 读这个事实）。 */
	public static void markDestroyed(int teamIndex) {
		if (phases.get(teamIndex) == Phase.WRECKAGE) {
			return;
		}
		phases.put(teamIndex, Phase.WRECKAGE);
		channels.put(teamIndex, Channel.none());
		holder.remove(teamIndex);
		keepaliveUntil.remove(teamIndex);
		NBW.LOGGER.warn("[raftcore] 队 {} 的筏心被摧毁，留下残骸；30s 沉筏倒计时开始，"
				+ "本队可在残骸上持续右键 {}s 恢复（每局 {} 次）",
				teamIndex, ChannelRule.CHANNEL_TICKS / 20, MAX_RESTORES_PER_MATCH);
	}

	/** 开局发筏 / 局后清理时把筏心摆回完好状态。 */
	public static void markIntact(int teamIndex) {
		phases.put(teamIndex, Phase.INTACT);
		channels.put(teamIndex, Channel.none());
		holder.remove(teamIndex);
		keepaliveUntil.remove(teamIndex);
	}

	// ---------------------------------------------------------------- 引导输入

	/** 客户端检测到最后一次有效右键时续一次；dev 侧走 {@link #hold}。 */
	public static void keepalive(UUID player, int teamIndex) {
		if (phases.get(teamIndex) != Phase.WRECKAGE) {
			return;
		}
		if (restoresUsed.getOrDefault(teamIndex, 0) >= MAX_RESTORES_PER_MATCH) {
			return;
		}
		if (holder.containsKey(teamIndex) && !holder.get(teamIndex).equals(player)) {
			return;   // 已经有人在引导了，第二个人挤不进来
		}
		holder.put(teamIndex, player);
		keepaliveUntil.put(teamIndex, tick + KEEPALIVE_TICKS);
	}

	/** 玩家主动松手。 */
	public static void release(UUID player) {
		holder.entrySet().removeIf(e -> e.getValue().equals(player));
		vanished.clear();   // 换人重新引导后，"查不到"要允许重新报一次
	}

	/** 伤害事件回调（由 ALLOW_DAMAGE 挂钩调用）。 */
	public static void notifyDamaged(UUID player) {
		damagedThisTick.add(player);
	}

	/**
	 * dev / 自动化测试入口：模拟某个玩家持续按住右键若干 tick。
	 *
	 * <p>与真实客户端的区别只有"输入从哪来"，服务端行为完全同一条路径，
	 * 所以 8 秒持续与受伤打断这两条规则可以不等真人就验完。
	 */
	public static String hold(MinecraftServer server, @Nullable UUID player, int teamIndex, int ticks) {
		if (player == null) {
			return "必须指定在线玩家";
		}
		if (phases.get(teamIndex) != Phase.WRECKAGE) {
			return "队 " + teamIndex + " 的筏心不是残骸状态，没有东西可修（先 /cww raftcore destroy " + teamIndex + "）";
		}
		if (restoresUsed.getOrDefault(teamIndex, 0) >= MAX_RESTORES_PER_MATCH) {
			return "队 " + teamIndex + " 本局修复额度已用完（" + MAX_RESTORES_PER_MATCH + " 次）";
		}
		simulatedHolder.put(teamIndex, player);
		simulatedHoldUntil.put(teamIndex, tick + ticks);
		return "已登记模拟引导 " + ticks + " tick（到 tick " + tick + "+" + ticks
				+ "；" + ChannelRule.CHANNEL_TICKS + " tick 才够修好）";
	}

	// ---------------------------------------------------------------- 方块侧登记

	/**
	 * BE 首次被 tick 时登记自己。返回 false 表示这个位置已经属于别的队——
	 * 那是配置或生成逻辑出错，必须吵出来，不能让两队共用一个筏心。
	 */
	public static boolean registerCore(net.minecraft.server.MinecraftServer server,
			net.minecraft.util.math.BlockPos pos, int teamIndex) {
		long key = pos.asLong();
		Integer existing = coreByPos.get(key);
		if (existing != null && existing != teamIndex) {
			NBW.LOGGER.error("[raftcore] pos={} 已登记给队 {}，队 {} 想再登记同一个筏心", pos, existing, teamIndex);
			return false;
		}
		if (teamIndex < 0) {
			return false;   // 手工摆的筏心没有归属，先不登记，等分配
		}
		coreByPos.put(key, teamIndex);
		if (phases.get(teamIndex) == null) {
			phases.put(teamIndex, Phase.INTACT);
		}
		return true;
	}

	/** 由方块坐标反查归属队伍；查不到返回 -1。 */
	public static int teamOfCore(net.minecraft.server.MinecraftServer server,
			net.minecraft.util.math.BlockPos pos) {
		Integer team = coreByPos.get(pos.asLong());
		return team == null ? -1 : team;
	}

	/**
	 * 把服务端的权威相位回写到方块状态上。
	 * 不这么做就会出现"倒计时已经在走、方块看着还是好的"——玩家完全无法判断该不该去修。
	 */
	public static void syncState(net.minecraft.world.World world,
			net.minecraft.util.math.BlockPos pos, int teamIndex) {
		if (world == null || world.isClient() || teamIndex < 0) {
			return;
		}
		var state = world.getBlockState(pos);
		if (!(state.getBlock() instanceof dev.nbw.content.RaftCoreBlock core)) {
			return;
		}
		boolean shouldWrecked = phases.get(teamIndex) == Phase.WRECKAGE;
		if (state.get(dev.nbw.content.RaftCoreBlock.WRECKAGE) != shouldWrecked) {
			world.setBlockState(pos, state.with(dev.nbw.content.RaftCoreBlock.WRECKAGE, shouldWrecked),
					net.minecraft.block.Block.NOTIFY_ALL);
			// 这是"服务端相位真的写进了方块状态"的唯一可断言证据。
			// 没有它，玩家看不到残骸、而日志上什么都看不出来。
			stateSyncs++;
			NBW.LOGGER.info("[raftcore] 队 {} 的筏心方块状态回写为 {}（累计 {} 次）",
					teamIndex, shouldWrecked ? "残骸" : "完好", stateSyncs);
		}
	}

	// ---------------------------------------------------------------- tick

	public static void onEndTick(MinecraftServer server) {
		tick++;
		step(server);
		damagedThisTick.clear();
	}

	private static void step(MinecraftServer server) {
		// 模拟引导每 tick 自动续 keepalive，与真实客户端发包的净效果一致
		for (Map.Entry<Integer, Integer> entry : new LinkedHashMap<>(simulatedHoldUntil).entrySet()) {
			int teamIndex = entry.getKey();
			if (entry.getValue() <= tick) {
				simulatedHoldUntil.remove(teamIndex);
				simulatedHolder.remove(teamIndex);
				continue;
			}
			keepalive(simulatedHolder.get(teamIndex), teamIndex);
		}
		for (Map.Entry<Integer, UUID> entry : new LinkedHashMap<>(holder).entrySet()) {
			int teamIndex = entry.getKey();
			UUID who = entry.getValue();
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(who);

			// 绑不到人要说清楚，不能静默反复重来。
			// 真实玩家重生不换 UUID，所以查不到就等于真的离线了——
			// 打断是正确行为，但必须留一句话，否则现场只剩"进度每秒归零"这种无法解释的现象
			// （实测就是这样绕了四步才定位到 Carpet 的 kill+spawn 会换 UUID，是夹具问题不是产品问题）。
			boolean gone = p == null
					&& channels.getOrDefault(teamIndex, Channel.none()).active();
			if (gone && vanished.add(teamIndex)) {
				NBW.LOGGER.warn("[raftcore] 队 {} 的引导者 {} 已查不到实体，引导终止。"
								+ "真人重生不换 UUID，所以这里通常是真离线；"
								+ "若在用 Carpet 假玩家测试，注意 kill+spawn 会换 UUID",
						teamIndex, who);
			}
			boolean interacting = p != null && p.isAlive()
					&& keepaliveUntil.getOrDefault(teamIndex, 0) >= tick;
			boolean damaged = damagedThisTick.contains(who);

			Channel current = channels.getOrDefault(teamIndex, Channel.none());
			Result r = ChannelRule.tick(current, new Inputs(interacting ? who : null, interacting, damaged));
			channels.put(teamIndex, r.channel());

			switch (r.outcome()) {
				case COMPLETED -> onRestored(server, teamIndex, who);
				case INTERRUPTED -> {
					holder.remove(teamIndex);
					keepaliveUntil.remove(teamIndex);
					if (current.progressTicks() > 0) {
						NBW.LOGGER.info("[raftcore] 队 {} 的修复引导被打断（进度 {}/{}，已归零）",
								teamIndex, current.progressTicks(), ChannelRule.CHANNEL_TICKS);
					}
				}
				case IDLE -> holder.remove(teamIndex);
				case ADVANCED -> {
					// 每 2 秒报一次进度，既不刷屏又能证明引导确实在走
					int progress = r.channel().progressTicks();
					if (progress % 40 == 0) {
						NBW.LOGGER.info("[raftcore] 队 {} 修复进度 {}/{} tick",
								teamIndex, progress, ChannelRule.CHANNEL_TICKS);
					}
				}
			}
		}
	}

	private static void onRestored(MinecraftServer server, int teamIndex, UUID who) {
		phases.put(teamIndex, Phase.INTACT);
		holder.remove(teamIndex);
		keepaliveUntil.remove(teamIndex);
		simulatedHolder.remove(teamIndex);
		simulatedHoldUntil.remove(teamIndex);
		int used = restoresUsed.merge(teamIndex, 1, Integer::sum);
		NBW.LOGGER.info("[raftcore] 队 {} 的筏心已由 {} 修复（本局第 {}/{} 次），沉筏倒计时解除",
				teamIndex, server.getPlayerManager().getPlayer(who) == null ? who
						: server.getPlayerManager().getPlayer(who).getNameForScoreboard(),
				used, MAX_RESTORES_PER_MATCH);
		RaftSinking.cancelByCoreRestore(teamIndex);
	}

	// ---------------------------------------------------------------- 杂项

	public static void reset() {
		phases.clear();
		channels.clear();
		restoresUsed.clear();
		holder.clear();
		keepaliveUntil.clear();
		simulatedHolder.clear();
		simulatedHoldUntil.clear();
		damagedThisTick.clear();
		tick = 0;
	}

	public static String status() {
		StringBuilder sb = new StringBuilder("[raftcore] ");
		if (phases.isEmpty()) {
			sb.append("本局还没有筏心记录");
		}
		for (Map.Entry<Integer, Phase> e : phases.entrySet()) {
			Channel c = channels.getOrDefault(e.getKey(), Channel.none());
			sb.append("队").append(e.getKey()).append('=').append(e.getValue());
			if (c.active()) {
				sb.append("(引导 ").append(c.progressTicks()).append('/').append(ChannelRule.CHANNEL_TICKS)
						.append("，剩 ").append(c.remainingSeconds()).append("s)");
			}
			sb.append(" 已用额度 ").append(restoresUsed.getOrDefault(e.getKey(), 0))
					.append('/').append(MAX_RESTORES_PER_MATCH).append("  ");
		}
		sb.append(" 状态回写 ").append(stateSyncs).append(" 次");
		return sb.toString().trim();
	}

	/** 给命令层用：把玩家名解析成 UUID，避免命令层碰 MC 的查找细节。 */
	public static @Nullable UUID playerByName(MinecraftServer server, String name) {
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
		return p == null ? null : p.getUuid();
	}

	public static String phaseName(int teamIndex) {
		return String.format(Locale.ROOT, "%s", phases.getOrDefault(teamIndex, Phase.INTACT));
	}
}
