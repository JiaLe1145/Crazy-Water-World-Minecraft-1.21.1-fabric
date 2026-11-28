package dev.nbw.client;

import dev.nbw.net.StatusPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 生存状态 HUD（M4-1）：营养/水分两条 + 状态标志（2026-11-28）。
 *
 * <p>状态标志（流血/骨折/失温/营养低/水分低）在<b>首次出现</b>时于屏幕中央弹一下，
 * 之后常驻在左下队伍面板里自己名字的后面（由 {@link MinimapClient#renderRoster} 读取
 * {@link #activeIcons()} 绘制）。数值全部由服务端推，客户端只做表现。
 */
public final class StatusHudClient {

	private static final int BAR_W = 100;
	private static final int BAR_H = 6;
	/** 状态标志中央弹出时长（毫秒）。 */
	private static final long POP_MS = 1200L;

	private static float nutrition = -1.0f;
	private static float hydration = -1.0f;
	private static boolean active;

	private static final EnumSet<StatusIcons.Kind> current =
			EnumSet.noneOf(StatusIcons.Kind.class);
	private static final Map<StatusIcons.Kind, Long> popAt =
			new EnumMap<>(StatusIcons.Kind.class);

	private StatusHudClient() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(StatusPayload.ID, (payload, context) ->
				context.client().execute(() -> {
					active = payload.active();
					if (!active) {
						nutrition = -1.0f;
						hydration = -1.0f;
						current.clear();
						popAt.clear();
						return;
					}
					nutrition = payload.nutrition();
					hydration = payload.hydration();

					EnumSet<StatusIcons.Kind> next = EnumSet.noneOf(StatusIcons.Kind.class);
					if (payload.bleeding()) {
						next.add(StatusIcons.Kind.BLEEDING);
					}
					if (payload.fracture()) {
						next.add(StatusIcons.Kind.FRACTURE);
					}
					if (payload.hypothermic()) {
						next.add(StatusIcons.Kind.HYPOTHERMIA);
					}
					if (nutrition < 20.0f) {
						next.add(StatusIcons.Kind.LOW_NUTRITION);
					}
					if (hydration < 20.0f) {
						next.add(StatusIcons.Kind.LOW_HYDRATION);
					}
					long now = System.currentTimeMillis();
					for (StatusIcons.Kind k : next) {
						if (!current.contains(k)) {
							popAt.put(k, now);   // 首次出现 → 屏幕中央弹一下
						}
					}
					for (StatusIcons.Kind k : current) {
						if (!next.contains(k)) {
							popAt.remove(k);
						}
					}
					current.clear();
					current.addAll(next);
				}));
		HudRenderCallback.EVENT.register((context, tickCounter) -> render(context));
	}

	/** 当前生效的状态标志（队伍面板名字后面用）。 */
	public static List<StatusIcons.Kind> activeIcons() {
		return new ArrayList<>(current);
	}

	private static void render(DrawContext context) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden || !active || nutrition < 0.0f) {
			return;
		}
		renderPopups(context, mc);
		int x = 6;
		int y = mc.getWindow().getScaledHeight() - 34;
		drawBar(context, mc, x, y, nutrition, 0xFF4CAF50, "营养");
		drawBar(context, mc, x, y + BAR_H + 3, hydration, 0xFF4A9BE8, "水分");
	}

	/** 中央弹出：新获得状态时放大出现、随后淡出；图标下方带状态名。 */
	private static void renderPopups(DrawContext context, MinecraftClient mc) {
		if (current.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		List<StatusIcons.Kind> showing = new ArrayList<>();
		for (StatusIcons.Kind k : current) {
			Long t = popAt.get(k);
			if (t != null && now - t < POP_MS) {
				showing.add(k);
			}
		}
		if (showing.isEmpty()) {
			return;
		}
		int w = mc.getWindow().getScaledWidth();
		int h = mc.getWindow().getScaledHeight();
		// 整张卡片缩小；图标固定 32px = 64px 贴图的 1/2，整数倍缩放保持像素清晰
		int cardW = 40;
		int cardH = 44;
		int iconSize = 32;
		int step = 44;
		int startX = w / 2 - showing.size() * step / 2;
		int baseY = (int) (h * 0.30);
		for (int i = 0; i < showing.size(); i++) {
			StatusIcons.Kind k = showing.get(i);
			long age = now - popAt.get(k);
			float p = Math.min(1.0f, age / (float) POP_MS);
			// 不做非整数倍缩放（会糊）；只做淡出 + 整像素上浮
			float alpha = p > 0.75f ? 1.0f - (p - 0.75f) / 0.25f : 1.0f;
			int rise = Math.round(4.0f * Math.max(0.0f, 1.0f - p * 4.0f));
			int cardX = startX + i * step;
			int cardY = baseY - rise;
			int a = Math.round(alpha * 255.0f);
			context.fill(cardX + 1, cardY + 2, cardX + cardW + 2, cardY + cardH + 3,
					(a / 3) << 24);
			context.fill(cardX, cardY, cardX + cardW, cardY + cardH,
					(Math.round(alpha * 0.55f * 255.0f) << 24) | 0x00202024);
			int borderColor = (a << 24) | (k.color & 0x00FFFFFF);
			context.fill(cardX, cardY, cardX + cardW, cardY + 1, borderColor);
			context.fill(cardX, cardY + cardH - 1, cardX + cardW, cardY + cardH, borderColor);
			context.fill(cardX, cardY, cardX + 1, cardY + cardH, borderColor);
			context.fill(cardX + cardW - 1, cardY, cardX + cardW, cardY + cardH, borderColor);
			int iconX = cardX + (cardW - iconSize) / 2;
			int iconY = cardY + 1;
			StatusIcons.drawBadge(context, mc, iconX, iconY, iconSize, k, alpha);
			int textColor = (a << 24) | 0x00FFFFFF;
			context.drawCenteredTextWithShadow(mc.textRenderer, k.label,
					cardX + cardW / 2, cardY + 34, textColor);
		}
	}

	private static void drawBar(DrawContext context, MinecraftClient mc, int x, int y, float value,
			int color, String label) {
		float ratio = Math.max(0.0f, Math.min(1.0f, value / 100.0f));
		context.fill(x - 1, y - 1, x + BAR_W + 1, y + BAR_H + 1, 0x88000000);
		context.fill(x, y, x + BAR_W, y + BAR_H, 0xFF2A2A2A);
		int fillColor = ratio < 0.2f ? 0xFFE53935 : color;
		context.fill(x, y, x + Math.round(BAR_W * ratio), y + BAR_H, fillColor);
		context.drawText(mc.textRenderer, label + " " + Math.round(value),
				x + BAR_W + 4, y - 1, 0xFFFFFFFF, true);
	}
}
