package dev.nbw.client;

import dev.nbw.net.StatusPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * 生存状态 HUD（M4-1）：收到服务端的 {@link StatusPayload} 后，在屏幕左下画「营养 / 水分」两条。
 *
 * <p>客户端只负责画，数值全由服务端推（服务端权威）。没收到状态（不在对局/没装服务端逻辑）时
 * 什么都不画，避免大厅里也挂两条空条。
 */
public final class StatusHudClient {

	private static final int BAR_W = 100;
	private static final int BAR_H = 6;

	private static float nutrition = -1.0f;
	private static float hydration = -1.0f;

	private StatusHudClient() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(StatusPayload.ID, (payload, context) ->
				context.client().execute(() -> {
					nutrition = payload.nutrition();
					hydration = payload.hydration();
				}));
		HudRenderCallback.EVENT.register((context, tickCounter) -> render(context));
	}

	private static void render(DrawContext context) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden || nutrition < 0.0f) {
			return;
		}
		int x = 6;
		int y = mc.getWindow().getScaledHeight() - 34;
		drawBar(context, mc, x, y, nutrition, 0xFF4CAF50, "营养");
		drawBar(context, mc, x, y + BAR_H + 3, hydration, 0xFF4A9BE8, "水分");
	}

	private static void drawBar(DrawContext context, MinecraftClient mc, int x, int y, float value,
			int color, String label) {
		float ratio = Math.max(0.0f, Math.min(1.0f, value / 100.0f));
		// 底槽
		context.fill(x - 1, y - 1, x + BAR_W + 1, y + BAR_H + 1, 0x88000000);
		context.fill(x, y, x + BAR_W, y + BAR_H, 0xFF2A2A2A);
		// 填充：低于 20 变红提示
		int fillColor = ratio < 0.2f ? 0xFFE53935 : color;
		context.fill(x, y, x + Math.round(BAR_W * ratio), y + BAR_H, fillColor);
		context.drawText(mc.textRenderer, label + " " + Math.round(value), x + BAR_W + 4, y - 1, 0xFFFFFFFF, true);
	}
}
