package dev.nbw.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * 状态标志（2026-11-28 用户需求）：流血、骨折、失温、营养低、水分低。
 *
 * <p>五张 16×16 像素图放在 {@code textures/status/}，使用与项目物品/方块一致的
 * 单像素深色描边。中央状态提示和队伍面板共用同一张图，避免两处语义漂移。
 */
public final class StatusIcons {

	public enum Kind {
		BLEEDING("流血", "bleeding", 0xFFE53935),
		FRACTURE("骨折", "fracture", 0xFFCFD8DC),
		HYPOTHERMIA("失温", "hypothermia", 0xFF4DD0E1),
		LOW_NUTRITION("营养低", "low_nutrition", 0xFFFFA726),
		LOW_HYDRATION("水分低", "low_hydration", 0xFF42A5F5);

		public final String label;
		public final int color;
		public final Identifier texture;

		Kind(String label, String textureName, int color) {
			this.label = label;
			this.color = color;
			this.texture = Identifier.of("nbw", "textures/status/" + textureName + ".png");
		}
	}

	private StatusIcons() {
	}

	/** 画一枚状态图标（x/y 为左上角）。alpha ∈ [0,1]。 */
	public static void drawBadge(DrawContext context, MinecraftClient mc, int x, int y, int size,
			Kind kind, float alpha) {
		float a = Math.max(0.0f, Math.min(1.0f, alpha));
		context.setShaderColor(1.0f, 1.0f, 1.0f, a);
		// 贴图保持 64×64；目标尺寸 size，源区域完整 64×64。
		// 注意必须用带 u/v(float) + region 的重载：9 参重载会把 size 同时当成源区域，
		// 结果只画了贴图左上角的一小块（用户看到的"显示不正常"）。
		context.drawTexture(kind.texture, x, y, size, size, 0.0f, 0.0f, 64, 64, 64, 64);
		context.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
	}
}
