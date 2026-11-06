package dev.nbw.net;

import dev.nbw.NBW;
import dev.nbw.content.OarBlockEntity;
import dev.nbw.content.RaftCoreBlockEntity;
import dev.nbw.domain.net.HoldPolicy;
import dev.nbw.raft.OarService;
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
 * <p>只做三件事：<b>判距离、认方块、续期或解除</b>。包里没有任何进度或动力字段，
 * 它只表达"我正按着哪个方块"；进度仍然由 {@link RaftCoreService#onEndTick} 数 tick，
 * 动力仍然由 {@link OarService#onEndTick} 每 tick 重新算 —— 两条各数各的，客户端一句都不算。
 *
 * <p>同一个包按<b>点到的 BE 是什么</b>分派给两种设施（筏心修复 / 桨台推进）。
 * 与其加一个"操作类型"字段让客户端自报干什么，不如直接认方块：
 * 客户端能撒谎，方块不能。
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
		NBW.LOGGER.info("[net] 长按输入通道已注册：{}（可达上限 {} 格，窗口 {} tick；去向=筏心修复 + 桨台推进）",
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
			case RELEASE -> {
				// 松手就是"我不再按着了"，不区分对象：两边都清，漏一边会留下过期动力或修复进度
				RaftCoreService.release(player.getUuid());
				OarService.release(player.getUuid());
			}
			case KEEPALIVE -> {
				// 同一个包按"点到的方块是什么"分派；身份仍然只从 BE 读，不按坐标查表
				switch (player.getWorld().getBlockEntity(pos)) {
					case RaftCoreBlockEntity core when core.teamIndex() >= 0 ->
							RaftCoreService.keepalive(player.getUuid(), core.teamIndex());
					case OarBlockEntity oar when oar.isRigged() ->
							OarService.keepalive(player.getUuid(), oar.teamIndex(), oar.slot());
					// 必须显式接 null：getBlockEntity 对空气与普通方块返回 null，
					// 而 Java 21 的 switch 碰上 null 选择值会直接 NPE —— 真人长按任意方块就能打崩服务端线程。
					case null, default -> {
						// 点到的不是有归属的设施：别的方块、别队的、或手工摆的。静默丢弃
					}
				}
			}
		}
	}
}
