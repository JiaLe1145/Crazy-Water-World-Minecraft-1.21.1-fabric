package dev.nbw.client;

import dev.nbw.net.LobbyStatusPayload;
import dev.nbw.net.LobbyTogglePayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 未开局顶部面板（2026-11-28 用户需求）：
 * <pre>
 *   参加玩家人数：3/5
 *   你已参加本场游戏
 * </pre>
 * 或"你未参加本场游戏，可以使用 K 键切换参加或者不参加"。
 *
 * <p>服务端只在 LOBBY/ENROLL/READY 推这个包，进 PLAY 时还会补一包 active=false；
 * 客户端再用 2.5 秒超时兜底，避免掉线/卡服时面板一直挂着。
 */
public final class LobbyHudClient {

	private static final long STALE_MS = 2500L;

	private static KeyBinding participateKey;
	private static boolean active;
	private static int participants;
	private static int total;
	private static boolean joined;
	private static long lastReceiveMs;

	private LobbyHudClient() {
	}

	public static void register() {
		participateKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.nbw.participate", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.nbw"));

		ClientPlayNetworking.registerGlobalReceiver(LobbyStatusPayload.ID, (payload, context) ->
				context.client().execute(() -> {
					active = payload.active();
					participants = payload.participants();
					total = payload.total();
					joined = payload.joined();
					lastReceiveMs = System.currentTimeMillis();
				}));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (participateKey.wasPressed()) {
				if (client.getNetworkHandler() != null) {
					ClientPlayNetworking.send(new LobbyTogglePayload());
				}
			}
		});

		HudRenderCallback.EVENT.register(LobbyHudClient::render);
	}

	private static void render(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!active || mc.player == null || mc.options.hudHidden
				|| System.currentTimeMillis() - lastReceiveMs > STALE_MS) {
			return;
		}
		String line1 = "参加玩家人数：" + participants + "/" + total;
		String line2 = joined
				? "你已参加本场游戏"
				: "你未参加本场游戏，可以使用 K 键切换参加或者不参加";
		int w = mc.getWindow().getScaledWidth();
		int textW = Math.max(mc.textRenderer.getWidth(line1), mc.textRenderer.getWidth(line2));
		int panelW = textW + 18;
		int panelH = 27;
		int x0 = (w - panelW) / 2;
		int y0 = 6;
		int x1 = x0 + panelW;
		int y1 = y0 + panelH;

		context.fill(x0, y0, x1, y1, 0xAA000000);
		context.fill(x0, y0, x1, y0 + 1, 0xFF6A7A8A);
		context.fill(x0, y1 - 1, x1, y1, 0xFF6A7A8A);
		context.fill(x0, y0, x0 + 1, y1, 0xFF6A7A8A);
		context.fill(x1 - 1, y0, x1, y1, 0xFF6A7A8A);

		context.drawText(mc.textRenderer, line1, x0 + (panelW - mc.textRenderer.getWidth(line1)) / 2,
				y0 + 4, 0xFFFFFFFF, true);
		context.drawText(mc.textRenderer, line2, x0 + (panelW - mc.textRenderer.getWidth(line2)) / 2,
				y0 + 15, joined ? 0xFF55E06A : 0xFFFFCC55, true);
	}
}
