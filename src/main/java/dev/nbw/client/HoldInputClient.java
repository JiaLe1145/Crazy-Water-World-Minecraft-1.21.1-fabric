package dev.nbw.client;

import dev.nbw.net.HoldPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * 长按输入通道的客户端一侧：按住右键期间周期续包，松手补一发 {@code active=false}。
 *
 * <p>单独成类而不是和服务端放一起，是因为模组要能在<b>专用服务端</b>跑：
 * 这个类引用 {@code MinecraftClient}，混进服务端入口就会在无私有客户端的环境里炸。
 *
 * <p><b>这一侧只做一件事：把"我正按着右键 + 我指着哪一格"送上去，不做任何判定。</b>
 * 它连"指的是什么方块"都说不准（准星给的 sub-level 坐标是存储坐标，在客户端父世界里查不出名堂），
 * 归属、可达、玩法全归服务端。
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
		// 主手拿遥控器时走移动模式那条通道（MoveInputClient），不在这里发长按包：
		// 否则开船右键会同时触发"按住方块"的修复/扒边判定，两个动作打架。
		if (client.player.getMainHandStack().getItem() == dev.nbw.content.NbwContent.remote) {
			if (sending) {
				sending = false;
				cooldown = 0;
				ClientPlayNetworking.send(new HoldPayload(lastTarget != null ? lastTarget : BlockPos.ORIGIN, false));
			}
			return;
		}
		final boolean held = client.options.useKey.isPressed();
		final BlockPos target = held ? targetUnderCrosshair(client) : null;
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

	/**
	 * 准星指到方块就发包，<b>不再分方块是什么</b>。
	 *
	 * <p><b>为什么原来那套"只挑桨台/残骸筏心/水里实心格"的白名单不够用。</b>
	 * 伸手救队友这条动作里，救援方是<b>旱的</b>、指着的就是甲板或船壳上一个普通方块——
	 * 既不是桨台也不是残骸筏心，白名单一条都不匹配，这个动作就根本发不出包。
	 *
	 * <p>顺带更正一条我在这里犯过的推断：sub-level 的方块<b>物理上就存在父世界的存储坐标里</b>
	 * （{@code ServerSubLevelContainer.java:152} 把父 Level 交给 sub-level），
	 * 所以 {@code client.world.getBlockState(准星坐标)} 一直读得出来，不存在"筛选器恒读空气"这回事
	 * ——2026-11-06 用 {@code cww net probe} 实测钉死了这一点（父世界读得到 OarBlockEntity）。
	 * 真正坏的只有服务端那道距离判定，见 {@code NbwNetworking.reachDistance}。
	 *
	 * <p>现在客户端只负责"我正按着、我指着哪一格"，剩下全部由服务端判：
	 * 归属从方块实体读，可达距离在甲板局部帧量，
	 * 扒边还是伸手由 {@code RescueService} 按几何决定。04 §2 不变式 4 本来就是这么定的——
	 * 客户端说的话一句都不算，那它连"我指的是什么方块"都不必说。
	 */
	private static BlockPos targetUnderCrosshair(MinecraftClient client) {
		final HitResult hit = client.crosshairTarget;
		if (client.world == null || hit == null || hit.getType() != HitResult.Type.BLOCK) {
			return null;   // 指向实体或指向天空都不算：这条通道只认方块
		}
		return ((BlockHitResult) hit).getBlockPos();
	}
}
