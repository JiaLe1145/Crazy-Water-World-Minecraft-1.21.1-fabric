package dev.nbw.net;

import dev.nbw.NBW;
import dev.nbw.content.RaftCoreBlockEntity;
import dev.nbw.domain.net.HoldPolicy;
import dev.nbw.domain.net.PacketRateLimit;
import dev.nbw.raft.RaftCoreService;
import dev.nbw.raft.RemoteService;
import dev.nbw.rescue.RescueService;
import dev.nbw.sable.SableRafts;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 长按输入通道的服务端一侧。
 *
 * <p>只做三件事：<b>判距离、认方块、续期或解除</b>。包里没有任何进度或动力字段，
 * 它只表达"我正按着哪个方块"；进度仍然由 {@link RaftCoreService#onEndTick} 数 tick，
 * 动力仍然由 {@link OarService#onEndTick} 每 tick 重新算 —— 两条各数各的，客户端一句都不算。
 *
 * <p>同一个包按<b>点到的方块是什么</b>分派：筏心残骸=修复引导、桨台=推进、
 * 认不出归属的（船壳、甲板方块）交给 {@link RescueService} 按几何判是"在水里扒边"还是"伸手救队友"。
 * 与其加一个"操作类型"字段让客户端自报干什么，不如直接认方块：客户端能撒谎，方块不能。
 *
 * <p><b>判距离必须站在正确的坐标空间里，认方块则一直是父世界直读。</b>包里那个 {@code BlockPos}
 * 是 sub-level 的<b>存储坐标</b>（实测量级 2×10^7）——注意这些方块<b>物理上就存在父世界里</b>
 * （{@code ServerSubLevelContainer.java:152} 把父 {@code ServerLevel} 直接交给了 sub-level），
 * 所以 {@code getWorld().getBlockEntity(pos)} 认得出桨台与筏心，这条分派一直是对的
 * （{@code content/OarBlock.onUse} 用的就是同一个读法）。
 * <b>错的只有距离</b>：父世界的玩家坐标（量级几百）与存储坐标直算恒得两千万格，
 * {@link HoldPolicy} 于是把每一个筏上长按判成越界。现在距离一律先换算进甲板局部帧再比
 * （{@link #reachDistance}，与 Sable 自己的 {@code interaction_distance/PlayerMixin} 同一口径）。
 * 这类错位的表现不是报错，而是"怎么按都没反应"，日志里只躺一行 REJECT 的 warn。
 *
 * <p>这个类<b>不许出现客户端依赖</b>：它由 {@code NBW.onInitialize} 调用，而模组要能在专用服务端跑。
 * 客户端那一半在 {@code dev.nbw.client.HoldInputClient}。
 *
 * <p>yarn 1.21.1 没有 {@code PayloadTypeRegistry}，注册表在 fabric 自己包里；
 * <b>codec 注册与接收器注册是两件事</b>，只注册接收器会让服务端解不出这个包。
 */
public final class NbwNetworking {

	/** 每玩家输入包计数（hold/move 共用一条限速，正常客户端合计约 10/s，上限 40/s）。 */
	private static final Map<UUID, PacketRateLimit.State> packetRate = new HashMap<>();
	private static final Map<UUID, Long> lastRateWarn = new HashMap<>();

	private NbwNetworking() {
	}

	/** 注册 codec 与接收器。顺序不能反。 */
	public static void register() {
		PayloadTypeRegistry.playC2S().register(HoldPayload.ID, HoldPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(HoldPayload.ID, (payload, context) -> {
			final ServerPlayerEntity player = context.player();
			final MinecraftServer server = context.server();
			// 自定义包不一定在主线程到达；世界与 BE 只能在服务端线程动。慢一帧远比崩一次好。
			server.execute(() -> {
				if (!admit(server, player)) {
					return;   // 超频包直接丢，不读方块、不续期任何窗口
				}
				handle(player, payload);
			});
		});
		PayloadTypeRegistry.playC2S().register(MovePayload.ID, MovePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(MovePayload.ID, (payload, context) -> {
			final ServerPlayerEntity player = context.player();
			final MinecraftServer server = context.server();
			server.execute(() -> {
				if (!admit(server, player)) {
					return;   // 同上：恶意客户端刷 move 包时不让它进服务端线程
				}
				RemoteService.onMove(player,
						payload.forward(), payload.backward(), payload.left(), payload.right());
			});
		});
		// S2C：生存状态（营养/水分）推给客户端 HUD（M4-1）。只注册 codec，接收器在客户端那一半。
		PayloadTypeRegistry.playS2C().register(StatusPayload.ID, StatusPayload.CODEC);
		NBW.LOGGER.info("[net] 已注册 nbw:hold（长按：筏心修复/救援）+ nbw:move（遥控器 WASD）+ nbw:status（S2C 生存状态）");
	}

	/**
	 * 每玩家输入包限速（Gate G5）：正常客户端约 10 包/秒，上限 40 包/秒。
	 * 超限只丢弃、不回包（不给探测器任何反馈）；日志每秒最多一行。
	 */
	private static boolean admit(MinecraftServer server, ServerPlayerEntity player) {
		long now = server.getTicks();
		PacketRateLimit.State next = PacketRateLimit.admit(packetRate.get(player.getUuid()), now,
				PacketRateLimit.DEFAULT_MAX_PER_SECOND);
		if (next == null) {
			Long last = lastRateWarn.get(player.getUuid());
			if (last == null || now - last >= 20) {
				lastRateWarn.put(player.getUuid(), now);
				NBW.LOGGER.warn("[net] {} 输入包超频，丢弃（上限 {}/s）",
						player.getNameForScoreboard(), PacketRateLimit.DEFAULT_MAX_PER_SECOND);
			}
			return false;
		}
		packetRate.put(player.getUuid(), next);
		return true;
	}

	private static void handle(ServerPlayerEntity player, HoldPayload payload) {
		final BlockPos pos = payload.pos();
		final ServerWorld world = player.getServerWorld();
		// 包里的 pos 是筏的存储坐标（见下面的 reachDistance），先问它属于哪艘筏，
		// 后面"量距离"和"认方块"都要用同一个答案，不能一处按存储坐标、另一处按父世界坐标。
		final UUID raftId = SableRafts.raftAt(world, pos);
		final double dist = reachDistance(world, player, pos, raftId);
		final double reach = HoldPolicy.reachFor(player.isSpectator());
		switch (HoldPolicy.decide(payload.active(), dist, reach)) {
			case REJECT -> {
				// 不回应、不改状态：给探测者反馈等于告诉他怎么绕
				NBW.LOGGER.warn("[net] 丢弃越界长按包 player={} 目标={} 局部帧距离={}格 上限={}格（父世界直算={}格）",
						player.getNameForScoreboard(), pos,
						String.format(Locale.ROOT, "%.1f", dist), reach,
						String.format(Locale.ROOT, "%.1f", player.getPos().distanceTo(pos.toCenterPos())));
			}
			case RELEASE -> {
				// 松手就是"我不再按着了"，不区分对象：两边都清，漏一边会留下过期动力或修复进度
				RaftCoreService.release(player.getUuid());
				RescueService.release(player.getUuid());   // 松手就断扒边续包，窗口过期后人会落回水里
			}
			case KEEPALIVE -> {
				// 同一个包按"点到的方块是什么"分派；身份仍然只从 BE 读，不按坐标查表。
				// sub-level 的方块物理上就在父世界的存储坐标里，所以这里直接读父世界是对的
				// （与 content/OarBlock.onUse 同一个读法）。
				switch (world.getBlockEntity(pos)) {
					case RaftCoreBlockEntity core when core.teamIndex() >= 0 ->
							RaftCoreService.keepalive(player.getUuid(), core.teamIndex());
					// 必须显式接 null：getBlockEntity 对空气与普通方块返回 null，
					// 而 Java 21 的 switch 碰上 null 选择值会直接 NPE —— 真人长按任意方块就能打崩服务端线程。
					case null, default -> {
						// 点到的不是有归属的设施：可能是筏体外壳（水里长按=扒边），
						// 也可能是"救援方按住甲板方块"（=伸手）。两种都由 RescueService 自己按几何判，
						// 别的东西自然判不出来（04 §2 不变式 4：客户端说的话一句都不算）
						RescueService.keepalive(player.getUuid());
					}
				}
			}
		}
	}

	/**
	 * 长按可达距离。<b>筏上方块必须在甲板局部帧里量，父世界直算是错的。</b>
	 *
	 * <p>Sable 交给交互事件与客户端准星的 {@code BlockPos} 是 sub-level 的<b>存储坐标</b>（实测量级
	 * 2×10^7），而 {@code player.getPos()} 是父世界的显示坐标（量级几百）。两者直接相减恒得
	 * 千万格开外，于是 {@link HoldPolicy} 会把<b>每一个</b>筏上的长按判成越界——症状是
	 * "长按桨台、长按筏心残骸、长按船壳扒边全都没反应"，而日志只在 REJECT 里躺一行 warn。
	 * 这一条 2026-11-06 才查出来，之前所有长按路径的验证都是走 {@code cww ... hold} 模拟入口，
	 * 那条不经过这里，所以缺陷一直藏着。
	 *
	 * <p>换算之后两侧落在同一帧（甲板中心=0）：玩家眼睛走 {@code worldToLocal}（位姿逆变换），
	 * 目标方块走 {@code storageToLocal}（减 plot 中心块，不旋转）。这一帧不跟着筏子转，
	 * 所以筏子开着、歪着，同一个动作的距离不变。
	 *
	 * <p>不是筏的方块（海床、岛岩、公共地形）本来就活在父世界里，退回直算。
	 */
	private static double reachDistance(ServerWorld world, ServerPlayerEntity player, BlockPos pos, UUID raftId) {
		if (raftId == null) {
			return player.getPos().distanceTo(pos.toCenterPos());
		}
		final double eyeY = player.getEyeY();
		double[] eyeLocal = SableRafts.worldToLocal(world, raftId, player.getX(), eyeY, player.getZ());
		double[] targetLocal = SableRafts.storageToLocal(world, raftId, pos);
		return HoldPolicy.localDistance(eyeLocal, targetLocal);
	}

	/**
	 * {@code /cww net probe <玩家> deck|oar|at ...}：把"这个包如果真从客户端发上来会怎么判"当场打出来。
	 *
	 * <p>RCON 与 Carpet 假玩家都造不出真客户端的长按包（{@code crosshairTarget} 只有真客户端有），
	 * 所以长按通道上的坐标口径一直没被端到端验过。这条命令是那个缺口的替代取证面：
	 * 直接喂一个<b>存储坐标</b>（桨台、筏心、船壳都算），看两种口径各自得到什么判定。
	 * 探针拿它的输出断言"父世界直算必须远到被判越界、甲板局部帧必须落在可达窗内"，
	 * 这样修的是哪一条、有没有修反，都不靠肉眼。
	 *
	 * @param playerName 用来算距离的玩家；离线时相关字段回一句说明，其余照样给
	 */
	public static String probe(MinecraftServer server, String playerName, BlockPos pos) {
		ServerWorld world = server.getOverworld();
		if (world == null) {
			return "[net] 服务端未就绪，读不到海域维度";
		}
		UUID raftId = SableRafts.raftAt(world, pos);
		StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "[net] 目标 %s 归属筏=%s", pos, raftId == null ? "无（公共地形）" : raftId));
		sb.append(String.format(Locale.ROOT, "｜父世界BE=%s", className(world.getBlockEntity(pos))));
		if (raftId != null) {
			double[] corner = SableRafts.storageToLocal(world, raftId, pos);
			sb.append(String.format(Locale.ROOT, "｜局部角=(%.2f,%.2f,%.2f)", corner[0], corner[1], corner[2]));
		}
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
		if (player == null || raftId == null) {
			sb.append("｜玩家不在场或目标不属筏，距离两项都不适用");
			return sb.toString();
		}
		double worldDist = player.getPos().distanceTo(pos.toCenterPos());
		double[] eyeLocal = SableRafts.worldToLocal(world, raftId, player.getX(), player.getEyeY(), player.getZ());
		double[] targetLocal = SableRafts.storageToLocal(world, raftId, pos);
		double localDist = HoldPolicy.localDistance(eyeLocal, targetLocal);
		double reach = HoldPolicy.reachFor(player.isSpectator());
		sb.append(String.format(Locale.ROOT, "｜眼睛局部=(%.2f,%.2f,%.2f)", eyeLocal[0], eyeLocal[1], eyeLocal[2]));
		sb.append(String.format(Locale.ROOT, "｜父世界直算=%.1f格→%s｜甲板局部帧=%.2f格→%s｜上限=%.1f格",
				worldDist, HoldPolicy.decide(true, worldDist, reach),
				localDist, HoldPolicy.decide(true, localDist, reach), reach));
		return sb.toString();
	}

	private static String className(@Nullable Object be) {
		return be == null ? "null" : be.getClass().getSimpleName();
	}
}
