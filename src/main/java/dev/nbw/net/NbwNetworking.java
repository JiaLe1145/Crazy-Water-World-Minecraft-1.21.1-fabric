package dev.nbw.net;

import dev.nbw.NBW;
import dev.nbw.content.RaftCoreBlockEntity;
import dev.nbw.domain.net.HoldPolicy;
import dev.nbw.raft.RaftCoreService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;

/**
 * 长按输入通道的服务端一侧。
 *
 * <p>只做三件事：<b>查归属、判距离、续期或解除</b>。进度仍然由
 * {@link RaftCoreService#onEndTick} 自己数 tick，包里没有任何进度字段可读。
 *
 * <p>归属从"玩家实际点到的那个 BE"读，不按坐标查表——理由和 {@code RaftCoreBlock.onUse} 一样：
 * BE 在自己 tick 里看到的世界坐标是 plot 局部量（实测 x=20481032 这种量级），
 * 两套坐标空间一旦错位，表现就是"怎么按都没反应"，而日志里什么都不报。
 *
 * <p>这个类<b>不许出现客户端依赖</b>：它由 {@code NBW.onInitialize} 调用，而模组要能在专用服务端跑。
 * 客户端那一半在 {@code dev.nbw.client.HoldInputClient}。
 *
 * <p>yarn 1.21.1 没有 {@code PayloadTypeRegistry}，注册表在 fabric 自己包里；
 * <b>codec 注册与接收器注册是两件事</b>，只注册接收器会让服务端解不出这个包。
 */
public final class NbwNetworking {

	private NbwNetworking() {
	}

	/** 注册 codec 与接收器。顺序不能反。 */
	public static void register() {
		PayloadTypeRegistry.playC2S().register(HoldPayload.ID, HoldPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(HoldPayload.ID, (payload, context) -> {
			final ServerPlayerEntity player = context.player();
			final MinecraftServer server = context.server();
			// 自定义包不一定在主线程到达；世界与 BE 只能在服务端线程动。慢一帧远比崩一次好。
			server.execute(() -> handle(player, payload));
		});
		NBW.LOGGER.info("[net] 长按输入通道已注册：{}（可达上限 {} 格，窗口 {} tick）",
				HoldPayload.CHANNEL_ID, HoldPolicy.REACH_BLOCKS, RaftCoreService.KEEPALIVE_TICKS);
	}

	private static void handle(ServerPlayerEntity player, HoldPayload payload) {
		final BlockPos pos = payload.pos();
		final double dist = player.getPos().distanceTo(pos.toCenterPos());
		final double reach = HoldPolicy.reachFor(player.isSpectator());
		switch (HoldPolicy.decide(payload.active(), dist, reach)) {
			case REJECT -> {
				// 不回应、不改状态：给探测者反馈等于告诉他怎么绕
				NBW.LOGGER.warn("[net] 丢弃越界长按包 player={} 目标={} 距离={}格 上限={}格",
						player.getNameForScoreboard(), pos,
						String.format(Locale.ROOT, "%.1f", dist), reach);
			}
			case RELEASE -> RaftCoreService.release(player.getUuid());
			case KEEPALIVE -> {
				if (!(player.getWorld().getBlockEntity(pos) instanceof RaftCoreBlockEntity be)
						|| be.teamIndex() < 0) {
					return;   // 点到的不是有归属的筏心：可能是别的方块，也可能是别队的
				}
				RaftCoreService.keepalive(player.getUuid(), be.teamIndex());
			}
		}
	}
}
