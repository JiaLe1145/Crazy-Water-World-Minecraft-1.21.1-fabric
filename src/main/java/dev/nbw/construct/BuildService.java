package dev.nbw.construct;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.content.NbwContent;
import dev.nbw.domain.construct.BuildRules;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.round.RoundManager;
import dev.nbw.sable.SableRafts;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 建造规则的适配层（M2-6）：把玩家的一次右键/左键解析成 {@link BuildRules} 要的输入，
 * 再把判定结果落回世界（放行 / 取消 + 提示 + 留痕）。
 *
 * <p><b>判定不在这里。</b>该不该让他放、卡的是哪条线，全在 {@code domain/build/BuildRules}
 * 那个纯函数里；这里只做坐标解析、方块统计与消息。规则因此可以脱离服务端被测。
 *
 * <p><b>坐标系是这件事唯一的难点，必须写清楚。</b>玩家点到的筏体方块，服务端拿到的是
 * <b>筏的存储坐标</b>而不是眼前的世界坐标：Sable 的射线重写先拿筏的 {@code logicalPose}
 * 把射线反投影进筏自己的空间再求交，命中结果就留在那个空间里
 * （取证 {@code mixin/clip_overwrite/BlockGetterMixin.java:104-131}）。于是：
 * <ul>
 *   <li>归属判定用 {@code Sable.HELPER.getContaining(world, pos)}，与 Sable 自己的
 *       {@code BlockPlaceContextMixin} 同一个入口——筏子开出 200 格也照样认得出来；</li>
 *   <li>建造区 / 邻接 / 数量一律换算成 <b>plot 局部坐标</b>（{@code 存储坐标 − plot 中心块}），
 *       与 {@code SableRafts.setPlotBlock} 用同一个原点，不能自己另推；</li>
 *   <li>伸手距离不用我们管：Sable 在 {@code interaction_distance/PlayerMixin} 里已经把玩家眼睛
 *       反投影进筏空间比过了，所以我们不需要重复一遍距离校验。</li>
 * </ul>
 *
 * <p><b>为什么不缓存方块计数。</b>每次判定重扫一遍建造盒：{@code maxRaftArea=96} → 半径 4，
 * 加一格邻接余量是 11×11×9 ≈ 1.1k 次读取。
 * 缓存要处理"我们自己也在写 plot"（摆/拆帆桨、开局生成、沉筏破坏），任何一处漏标脏
 * 就是一个静默漂移的上限——玩家会感觉到，探针不一定查得出。事件频率的上界是玩家手速，
 * 这点开销换的是"永远不会数错"。改动请守住这条不变式：<b>扫描只发生在事件里，不进 tick</b>。
 */
public final class BuildService {

	/** 扫描盒的水平半径保险丝：图给了天大的 maxRaftArea 也不做无界扫描（12 → 25×25×9 = 5.6k 次读取）。 */
	private static final int MAX_SCAN_RADIUS = 12;

	/** 最近 N 次判定（最新在前），给运维复盘与探针取证用。 */
	private static final Deque<String> verdicts = new ArrayDeque<>();
	private static final int VERDICT_KEEP = 24;

	private static long placed;
	private static long broken;
	private static long deniedPlace;
	private static long deniedBreak;

	/** 玩家 → 上次给出提示的 tick。按住左键时每 tick 都会触发攻击事件，不节流就是屏闪 + 日志洪水。 */
	private static final Map<UUID, Long> lastNotice = new HashMap<>();

	private BuildService() {
	}

	/** 挂载三个事件；由 {@code NBW.onInitialize} 调用一次。 */
	public static void register() {
		// 这一版 Fabric API（events-interaction 0.7.14 / mc 1.21.1）没有 PlayerBlockPlacementEvents，
		// 放置只能在使用方块时拦截；破坏则在起手与真正破坏前各拦一次。
		UseBlockCallback.EVENT.register(BuildService::onUseBlock);
		AttackBlockCallback.EVENT.register(BuildService::onAttackBlock);
		PlayerBlockBreakEvents.BEFORE.register(BuildService::onBreakBefore);
		NBW.LOGGER.info("[build] 建造规则已挂载：放置=USE_BLOCK，破坏=ATTACK_BLOCK+BREAK_BEFORE");
	}

	// ---------------------------------------------------------------- 事件入口

	private static ActionResult onUseBlock(PlayerEntity player, net.minecraft.world.World world,
			Hand hand, BlockHitResult hit) {
		if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) {
			return ActionResult.PASS;
		}
		ItemStack stack = sp.getStackInHand(hand);
		if (!(stack.getItem() instanceof BlockItem blockItem)) {
			// 手上不是方块，是在使用方块（桨台、筏心、箱子）——不归建造规则管
			return ActionResult.PASS;
		}

		BlockPos clicked = hit.getBlockPos();
		Context cc = resolve(sw, sp, clicked);
		BlockState clickedState = readState(sw, cc, clicked);
		// 可替换的目标（水、雪、草）落在被点那一格，否则落在点击面的外侧
		BlockPos target = clickedState != null && clickedState.isReplaceable()
				? clicked : clicked.offset(hit.getSide());

		String reason = gatePlace(sw, sp, target, blockItem);
		if (reason != null) {
			deniedPlace++;
			notice(sw, sp, reason);
			record(sw, sp.getName().getString(), "放置", target, reason);
			return ActionResult.FAIL;
		}
		placed++;
		record(sw, sp.getName().getString(), "放置", target, null);
		return ActionResult.PASS;   // 交给原版继续放，我们不替它写方块
	}

	private static ActionResult onAttackBlock(PlayerEntity player, net.minecraft.world.World world,
			Hand hand, BlockPos pos, Direction side) {
		if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) {
			return ActionResult.PASS;
		}
		String reason = gateBreak(sw, sp, pos);
		if (reason != null) {
			// 起手就拦：不出现挖掘进度条，玩家立刻知道"这格不归你动"。
			// 计数与留痕统一放在 BREAK_BEFORE（按住左键每 tick 都触发攻击事件，那里才是一次判定的边界）。
			notice(sw, sp, reason);
			return ActionResult.FAIL;
		}
		return ActionResult.PASS;
	}

	private static boolean onBreakBefore(net.minecraft.world.World world, PlayerEntity player,
			BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {
		if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) {
			return true;
		}
		String reason = gateBreak(sw, sp, pos);
		if (reason != null) {
			deniedBreak++;
			record(sw, sp.getName().getString(), "破坏", pos, reason);
			return false;
		}
		broken++;
		record(sw, sp.getName().getString(), "破坏", pos, null);
		return true;
	}

	// ---------------------------------------------------------------- 解析 → 判定

	/** 一次判定的全部解析结果。{@code located=false} 表示换算不出局部坐标（筏不在 / 读不到中心）。 */
	private record Context(int playerTeam, int raftTeam, @Nullable UUID raftId,
			int lx, int ly, int lz, boolean located) {
	}

	private static Context resolve(ServerWorld world, ServerPlayerEntity player, BlockPos global) {
		UUID raftId = SableRafts.raftAt(world, global);
		int raftTeam = raftId == null ? -1 : RaftSpawner.teamOf(raftId);
		var session = RoundManager.machine().session(player.getUuid());
		int playerTeam = session == null ? -1 : session.teamIndex();
		Context out;
		if (raftId == null) {
			// 不是筏：归属门会在纯函数里给出"公共海域"这句原因，局部坐标不参与判定，给 0 即可
			out = new Context(playerTeam, -1, null, 0, 0, 0, false);
		} else {
			BlockPos origin = SableRafts.accessorOrigin(world, raftId);
			if (origin == null) {
				out = new Context(playerTeam, raftTeam, raftId, 0, 0, 0, false);
			} else {
				out = new Context(playerTeam, raftTeam, raftId,
						global.getX() - origin.getX(), global.getY() - origin.getY(),
						global.getZ() - origin.getZ(), true);
			}
		}
		lastContext = out;
		return out;
	}

	/**
	 * 最近一次解析结果。只给判定记录行用，写在 {@link #resolve} 唯一出口、读在同一个调用栈里，
	 * 而方块交互事件只在服务端主线程跑，所以不需要并发保护。
	 */
	private static @Nullable Context lastContext;

	private static @Nullable String gatePlace(ServerWorld world, ServerPlayerEntity player,
			BlockPos target, BlockItem item) {
		Context c = resolve(world, player, target);
		return evaluatePlace(world, c, item.getBlock().getDefaultState());
	}

	/**
	 * 放置判定的后半段：归属门前置 → 扫描 → 邻接 → 纯函数。
	 *
	 * <p>事件路径（{@link #gatePlace}）与运维路径（{@link #check}）都走这里，
	 * 所以 {@code /cww build check} 报的结论与玩家真实点击的结论是同一套代码算出来的。
	 */
	private static @Nullable String evaluatePlace(ServerWorld world, Context c, BlockState toPlace) {
		String outside = BuildRules.checkOwnership(RoundManager.phase().allowsBuild(), c.playerTeam(), c.raftTeam());
		if (outside != null) {
			return outside;   // 越权请求不付扫描的钱
		}
		if (!c.located()) {
			return "读不到筏体方块（筏可能已沉没或刚被卸载）";
		}
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		if (arena == null || arena.limits == null) {
			return "arena 配置未加载，建造上限无法判定（/cww arena reload）";
		}
		ConfigModels.Arena.Limits limits = arena.limits;
		SableRafts.Scan scan = scanRaft(world, c.raftId());
		if (scan == null) {
			return "读不到筏体方块（扫描失败，筏可能已沉没或刚被卸载）";
		}
		int neighborMask = SableRafts.neighborMask(world, c.raftId(), c.lx(), c.ly(), c.lz());
		if (neighborMask < 0) {
			return "读不到筏体方块（邻接检查失败）";
		}
		return BuildRules.checkPlace(RoundManager.phase().allowsBuild(), c.playerTeam(), c.raftTeam(),
				c.lx(), c.ly(), c.lz(), limits.maxRaftArea, neighborMask,
				scan.blocks(), limits.maxBlocksPerRaft,
				isFacility(toPlace), scan.facilities(), limits.maxFacilities);
	}

	/**
	 * 筏体方块统计的唯一口径：判定（{@link #evaluatePlace}）、运维读数（{@link #status}）、
	 * 探针检查（{@link #check}）必须读到同一批数字，否则"上限卡住了"与"筏上有几格"会互相打脸。
	 *
	 * <p>扫描盒固定为 {@code ±MAX_SCAN_RADIUS × [MIN_LOCAL_Y-1, MAX_LOCAL_Y+1]}（25×25×9）。
	 * <b>已知边界：筏体比这个盒大时，方块数与设施数会少算，上限因此偏松。</b>
	 * 现在最大初始筏半径由 {@code limits.initialRaftRadius} 给（实配 3~9），盒足够罩住；
	 * 真要支持更大的筏，就把它换成 Sable 自己维护的 plot bounding box（MassTracker 用的就是它），
	 * 而不是再调这个常量。
	 */
	private static @Nullable SableRafts.Scan scanRaft(ServerWorld world, UUID raftId) {
		return SableRafts.scan(world, raftId, MAX_SCAN_RADIUS,
				BuildRules.MIN_LOCAL_Y - 1, BuildRules.MAX_LOCAL_Y + 1, BuildService::isFacility);
	}

	private static @Nullable String gateBreak(ServerWorld world, ServerPlayerEntity player, BlockPos target) {
		Context c = resolve(world, player, target);
		BlockState state = readState(world, c, target);
		boolean protectedBlock = state != null && state.isOf(NbwContent.raftCore);
		// 公共海域（raftTeam=-1）与别队筏的原因都由纯函数给，这里不替它决定措辞
		return BuildRules.checkBreak(RoundManager.phase().allowsBuild(), c.playerTeam(), c.raftTeam(), protectedBlock);
	}

	/**
	 * 运维与探针入口：把一次判定<b>按坐标</b>问出来，而不是靠假玩家去瞄。
	 *
	 * <p>存在的理由：建造区边界、悬空、上限这几条门，用真人或假玩家的瞄准去命中要么靠运气、
	 * 要么要摆几十格方块，而它们恰恰是最容易在改配置后悄悄失效的。这里用 {@code onTeam} 筏的
	 * plot 局部坐标合成目标格，再以 {@code asTeam} 的身份走<b>与真实点击完全相同</b>的
	 * {@link #evaluatePlace}，所以命令说"允许"而玩家点不动、或反过来，都算 bug。
	 *
	 * <p><b>只判定，不写方块。</b>它不改世界、不扣物品，因此也永远不会成为一条作弊通道。
	 *
	 * @param kind    {@code place} 或 {@code break}
	 * @param blockId 放置时的方块 id（如 {@code minecraft:oak_planks}、{@code nbw:sail}）；破坏时忽略
	 * @return 给人看的一行结论
	 */
	public static String check(ServerWorld world, int asTeam, int onTeam, String kind,
			int lx, int ly, int lz, @Nullable String blockId) {
		UUID raftId = RaftSpawner.raftOf(onTeam);
		if (raftId == null) {
			return "队 " + onTeam + " 这一局没有筏（还没生成或已被清场），无从判定";
		}
		BlockPos origin = SableRafts.accessorOrigin(world, raftId);
		if (origin == null) {
			return "队 " + onTeam + " 的筏读不到 plot 原点（可能已沉没或刚被卸载）";
		}
		int raftTeam = RaftSpawner.teamOf(raftId);
		Context c = new Context(asTeam, raftTeam, raftId, lx, ly, lz, true);
		lastContext = c;
		BlockPos global = origin.add(lx, ly, lz);
		String who = "检查(作为队" + asTeam + ")";

		if ("break".equalsIgnoreCase(kind)) {
			BlockState state = SableRafts.plotBlock(world, raftId, lx, ly, lz);
			String reason = BuildRules.checkBreak(RoundManager.phase().allowsBuild(), asTeam, raftTeam,
					state != null && state.isOf(NbwContent.raftCore));
			record(world, who, "破坏检查", global, reason);
			return "目标格现有方块=" + (state == null ? "读不到" : state.getBlock().getName().getString())
					+ " → " + (reason == null ? "允许" : "拒绝：" + reason);
		}
		if (!"place".equalsIgnoreCase(kind)) {
			return "只认 place / break，收到的是 " + kind;
		}
		String id = blockId == null ? "minecraft:oak_planks" : blockId;
		net.minecraft.util.Identifier identifier = net.minecraft.util.Identifier.tryParse(id);
		if (identifier == null) {
			return "不认识方块 id：" + id + "（要写成 minecraft:oak_planks 这样带命名空间的）";
		}
		BlockState toPlace = net.minecraft.registry.Registries.BLOCK.get(identifier).getDefaultState();
		if (toPlace.isAir() && !identifier.getPath().equals("air")) {
			return "方块 id " + id + " 解析成空气，说明这条 id 在这版本里不存在";
		}
		String reason = evaluatePlace(world, c, toPlace);
		record(world, who, "放置检查", global, reason);
		// 把判定当时看到的数量一起报出来：探针要能核对"上限算术"，而不是只核对关键字
		ConfigModels.Arena.Limits limits = RoundManager.arenaOrNull() == null
				? null : RoundManager.arenaOrNull().limits;
		SableRafts.Scan seen = scanRaft(world, raftId);
		String counts = (seen == null || limits == null) ? "" : String.format(Locale.ROOT,
				"｜筏体 方块=%d/%d 设施=%d/%d", seen.blocks(), limits.maxBlocksPerRaft,
				seen.facilities(), limits.maxFacilities);
		return "要放 " + id + " → " + (reason == null ? "允许" : "拒绝：" + reason) + counts;
	}

	/**
	 * 读"这一格到底是什么方块"。
	 *
	 * <p>筏内的方块以 <b>plot accessor 为准</b>：那是 {@code setPlotBlock} 写入的同一存储，
	 * 也是扫描与邻接判定读取的同一存储；三处口径必须一致，否则上限与邻接会互相打脸。
	 * 读不到（筏刚被卸载）才退回父世界。
	 */
	private static @Nullable BlockState readState(ServerWorld world, Context c, BlockPos global) {
		if (c.located()) {
			BlockState inPlot = SableRafts.plotBlock(world, c.raftId(), c.lx(), c.ly(), c.lz());
			if (inPlot != null) {
				return inPlot;
			}
		}
		return world.getBlockState(global);
	}

	private static boolean isFacility(BlockState state) {
		return state.isOf(NbwContent.sail) || state.isOf(NbwContent.oar) || state.isOf(NbwContent.raftCore);
	}

	// ---------------------------------------------------------------- 反馈与观测

	private static void notice(ServerWorld world, ServerPlayerEntity player, String reason) {
		long tick = world.getServer().getTicks();
		Long last = lastNotice.get(player.getUuid());
		if (last != null && tick - last < 10) {
			return;
		}
		lastNotice.put(player.getUuid(), tick);
		player.sendMessage(Text.literal("§c[建造] " + reason), true);
		NBW.LOGGER.info("[build] 拒绝 {}：{}", player.getName().getString(), reason);
	}

	private static void record(ServerWorld world, String who, String kind, BlockPos pos,
			@Nullable String reason) {
		while (verdicts.size() >= VERDICT_KEEP) {
			verdicts.removeLast();
		}
		Context c = lastContext;
		String resolved = c == null ? "局部=?" : String.format(Locale.ROOT, "局部=(%d,%d,%d) 筏队=%d%s",
				c.lx(), c.ly(), c.lz(), c.raftTeam(), c.located() ? "" : "（未定位）");
		verdicts.addFirst(String.format(Locale.ROOT, "t=%d %s %s %s %s → %s",
				world.getServer().getTicks(), kind, who, pos.toShortString(), resolved,
				reason == null ? "允许" : "拒绝：" + reason));
	}

	/** {@code /cww build status}：累计计数、当前上限，以及每队筏体的实测统计。 */
	public static String status(ServerWorld world) {
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
				"[build] 允许 放置=%d 破坏=%d｜拒绝 放置=%d 破坏=%d", placed, broken, deniedPlace, deniedBreak));
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		if (arena != null && arena.limits != null) {
			sb.append(String.format(Locale.ROOT, "｜上限 maxRaftArea=%d→半径%d maxBlocks=%d maxFacilities=%d",
					arena.limits.maxRaftArea, BuildRules.buildRadius(arena.limits.maxRaftArea),
					arena.limits.maxBlocksPerRaft, arena.limits.maxFacilities));
		} else {
			sb.append("｜arena 配置未加载（建造判定会全部拒绝）");
		}
		for (Map.Entry<Integer, UUID> e : RaftSpawner.current().entrySet()) {
			SableRafts.Scan scan = scanRaft(world, e.getValue());
			sb.append(String.format(Locale.ROOT, "｜队%d 方块=%d 列=%d 设施=%d 加载区块=%d",
					e.getKey(),
					scan == null ? -1 : scan.blocks(),
					scan == null ? -1 : scan.columns(),
					scan == null ? -1 : scan.facilities(),
					scan == null ? -1 : scan.loadedChunks()));
		}
		return sb.toString();
	}

	/** 最近若干次判定，给探针断言"这条规则真的生效了"。 */
	public static List<String> recent() {
		return new ArrayList<>(verdicts);
	}

	/** 局后清理：提示节流表清空（计数是跨局运维量，保留）。 */
	public static void reset() {
		lastNotice.clear();
	}
}
