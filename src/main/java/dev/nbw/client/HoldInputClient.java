package dev.nbw.client;

import dev.nbw.content.RaftCoreBlock;
import dev.nbw.net.HoldPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * 长按输入通道的客户端一侧：按住右键期间周期续包，松手补一发 {@code active=false}。
 *
 * <p>单独成类而不是和服务端放一起，是因为模组要能在<b>专用服务端</b>跑：
 * 这个类引用 {@code MinecraftClient}，混进服务端入口就会在无私有客户端的环境里炸。
 *
 * <p>这里筛"是不是残骸筏心"只是为了少发包，<b>不是安全检查</b>：
 * 服务端仍然自己查归属、查距离、查相位，客户端说的话一句都不算。
 */
public final class HoldInputClient {

	/** 续包间隔（tick）。服务端窗口是 4 tick，取 2 留一次丢包的余量又不刷屏。 */
	private static final int SEND_EVERY_TICKS = 2;

	private static int cooldown = 0;
	private static boolean sending = false;
	private static BlockPos lastTarget = null;

	private HoldInputClient() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(HoldInputClient::onClientTick);
	}

	private static void onClientTick(MinecraftClient client) {
		if (client.player == null || client.getNetworkHandler() == null) {
			return;
		}
		final boolean held = client.options.useKey.isPressed();
		final BlockPos target = held ? wreckageUnderCrosshair(client) : null;
		if (held && target != null) {
			sending = true;
			lastTarget = target;
			if (cooldown > 0) {
				cooldown--;
				return;
			}
			cooldown = SEND_EVERY_TICKS;
			ClientPlayNetworking.send(new HoldPayload(target, true));
			return;
		}
		if (sending) {
			// 只在"上一次还在按着"时补松手包，否则每 tick 都会发一个 false
			sending = false;
			cooldown = 0;
			ClientPlayNetworking.send(new HoldPayload(lastTarget != null ? lastTarget : BlockPos.ORIGIN, false));
		}
	}

	/** 准星指的方块只有在"是我们的筏心且已成残骸"时才值得发包。 */
	private static BlockPos wreckageUnderCrosshair(MinecraftClient client) {
		final HitResult hit = client.crosshairTarget;
		if (client.world == null || hit == null || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		final BlockPos pos = ((BlockHitResult) hit).getBlockPos();
		final BlockState state = client.world.getBlockState(pos);
		if (!(state.getBlock() instanceof RaftCoreBlock)) {
			return null;
		}
		return state.get(RaftCoreBlock.WRECKAGE) ? pos : null;
	}
}
