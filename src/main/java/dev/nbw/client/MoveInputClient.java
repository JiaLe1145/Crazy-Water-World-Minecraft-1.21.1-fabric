package dev.nbw.client;

import dev.nbw.NBW;
import dev.nbw.content.NbwContent;
import dev.nbw.net.MovePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;

/**
 * 遥控器输入的客户端一侧（问题③）：主手拿遥控器时按右键进入/退出"移动模式"，
 * 移动模式里读 WASD 并周期发 {@link MovePayload} 给服务端。
 *
 * <p>与 {@link HoldInputClient} 的分工：那边是"按住右键 + 指着方块"（修筏心/扒边/救援），
 * 这边是"移动模式 + WASD"（开船）。两条互斥：主手拿遥控器时走这条，不拿时走 hold 那一条
 * （hold 侧也会在拿遥控器时跳过，见它的注释）。单独成类是为了专用服务端能跑——
 * 这个类引用 {@code MinecraftClient}，混进服务端入口会炸。
 *
 * <p><b>这一侧不做任何方向换算</b>：只报"哪些键按着"，前进是哪个方向由服务端按筏的实时朝向算。
 */
public final class MoveInputClient {

	/** 发包节拍（tick）。服务端窗口 6 tick，取 2 留丢包余量又不刷屏。 */
	private static final int SEND_EVERY_TICKS = 2;

	private static boolean moveMode = false;
	private static boolean useWasPressed = false;
	private static int cooldown = 0;
	private static boolean lastForward, lastBackward, lastLeft, lastRight;

	private MoveInputClient() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(MoveInputClient::onClientTick);
	}

	/**
	 * 当前是否处于"遥控器移动模式"。
	 *
	 * <p>给 {@code InputMixin} 用：移动模式里 WASD 只该开船、不该让人物自己走。
	 * 光在这里读键是不够的 —— 玩家自己的移动由原版 {@code Input} 算出来并施加，
	 * 必须在 {@code Input.tick()} 之后把 movementForward/Sideways 清零才拦得住。
	 */
	public static boolean isMoveMode() {
		return moveMode;
	}

	private static void onClientTick(MinecraftClient client) {
		if (client.player == null || client.getNetworkHandler() == null) {
			reset();
			return;
		}
		final boolean holdingRemote = client.player.getMainHandStack().getItem() == NbwContent.remote;
		final boolean usePressed = client.options.useKey.isPressed();

		// 右键边沿：主手拿遥控器时按一下 = 切移动模式。
		// 2026-11-28 用户要求：**必须对准筏心才能进入**；退出不要求瞄准，
		// 否则第三人称木筏视角下准星不一定还在筏心上，会退不出去。
		if (holdingRemote && usePressed && !useWasPressed) {
			if (moveMode) {
				moveMode = false;
				NBW.LOGGER.info("[remote] 移动模式 关闭");
				sendClear();
			} else if (aimingAtRaftCore(client)) {
				moveMode = true;
				NBW.LOGGER.info("[remote] 移动模式 开启（对准筏心）");
			} else {
				client.player.sendMessage(Text.literal("§e[cww] 请对准本队筏心使用遥控器"), true);
			}
		}
		useWasPressed = usePressed;

		// 不再拿遥控器（切换物品/掉落）→ 强制退出移动模式，且立刻松掉所有键
		if (moveMode && !holdingRemote) {
			moveMode = false;
			sendClear();
		}
		if (!moveMode || !holdingRemote) {
			return;
		}

		final boolean f = client.options.forwardKey.isPressed();
		final boolean b = client.options.backKey.isPressed();
		final boolean l = client.options.leftKey.isPressed();
		final boolean r = client.options.rightKey.isPressed();
		if (cooldown > 0) {
			cooldown--;
		}
		boolean changed = f != lastForward || b != lastBackward || l != lastLeft || r != lastRight;
		if (changed || cooldown == 0) {
			lastForward = f;
			lastBackward = b;
			lastLeft = l;
			lastRight = r;
			cooldown = SEND_EVERY_TICKS;
			// 诊断（2026-11-21）：用户反馈"移动模式下木筏完全不动"，但服务端用
			// /cww remote move 夹具能推起来 —— 说明包没发出去或发的是全 false。
			// 这条日志就是用来区分"客户端没发"和"服务端没收"的，每秒最多一条。
			if ((f || b || l || r) && (client.world != null && client.world.getTime() % 20 == 0)) {
				NBW.LOGGER.info("[remote] 发包 w={} s={} a={} d={}", f, b, l, r);
			}
			ClientPlayNetworking.send(new MovePayload(f, b, l, r));
		}
	}

	/** 准星是否正对着筏心方块（Sable 的射线会给出 sub-level 方块，state 能读到）。 */
	private static boolean aimingAtRaftCore(MinecraftClient client) {
		if (client.world == null || !(client.crosshairTarget instanceof BlockHitResult hit)) {
			return false;
		}
		return client.world.getBlockState(hit.getBlockPos()).isOf(NbwContent.raftCore);
	}

	private static void sendClear() {
		lastForward = lastBackward = lastLeft = lastRight = false;
		cooldown = 0;
		// 退出移动模式必须补一发全 false，否则服务端会继续按最后一帧意图开船
		ClientPlayNetworking.send(new MovePayload(false, false, false, false));
	}

	private static void reset() {
		moveMode = false;
		useWasPressed = false;
		cooldown = 0;
		lastForward = lastBackward = lastLeft = lastRight = false;
	}
}
