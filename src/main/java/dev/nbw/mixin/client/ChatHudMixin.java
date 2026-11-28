package dev.nbw.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.network.message.ChatVisibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 把原版聊天框从"左下角"移到"左上角"，并给它画一个深色底 + 边框
 * （2026-11-28 用户需求：左下角要留给队伍面板，聊天框会挡住它）。
 *
 * <p>为什么用 mixin：聊天框的位置是 {@code ChatHud.render} 内部自己按
 * {@code (screenHeight - 40)} 算出来的，Fabric 没有"移动聊天框"的 API。
 * 这里在 render 的 HEAD 推一次矩阵并平移，RETURN 再 pop —— 聊天框内部的
 * scale / 行距 / 滚动条全部原样不动，只是整体挪到左上角。
 *
 * <p>v1 只做平移和边框，不改变聊天内容、行数、透明度与输入框（聊天输入在
 * {@code ChatScreen}，不受这里影响）。
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {

	/** 顶部留白（像素）：让开顶部"参加玩家人数"面板。 */
	private static final int TOP_MARGIN = 46;
	/** 边框额外宽度。 */
	private static final int PAD_X = 5;
	/** 底部多留一点，避免最后一行被边框压住。 */
	private static final int PAD_Y = 4;

	/** 原版当前可见的消息行（只用于把边框收到实际行数，不改变渲染内容）。 */
	@Shadow
	@Final
	private List<ChatHudLine.Visible> visibleMessages;

	@Inject(method = "render", at = @At("HEAD"))
	private void nbw$moveChatToTopLeft(DrawContext context, int currentTick, int mouseX, int mouseY,
			boolean focused, CallbackInfo ci) {
		MinecraftClient mc = MinecraftClient.getInstance();
		ChatHud self = (ChatHud) (Object) this;

		int screenH = context.getScaledWindowHeight();
		double scale = self.getChatScale();
		int chatW = self.getWidth();
		// 按"实际消息行数"收高度：只有一条消息时不要挂一个 100+ 像素的空框
		double spacing = mc.options.getChatLineSpacing().getValue();
		double lineHeight = 9.0 * (1.0 + spacing);
		// 按原版规则数"还没淡出"的行（>=200 tick 且未打开聊天时原版直接跳过）：
		// 没有新鲜消息时整块聊天框连边框一起隐藏。
		int shown = 0;
		for (ChatHudLine.Visible line : visibleMessages) {
			if (focused || currentTick - line.addedTime() < 200) {
				shown++;
			}
		}
		shown = Math.min(shown, self.getVisibleLineCount());
		int chatH = shown <= 0 ? 0 : (int) Math.ceil(shown * lineHeight * scale);
		// 顶部标题条（"聊天框"三个字）
		int titleH = 12;
		int totalH = chatH + titleH;

		// 目标：聊天框整体贴左上角，底边 = TOP_MARGIN + totalH + PAD
		int targetBottom = TOP_MARGIN + totalH + PAD_Y + 2;
		int dy = targetBottom - PAD_Y - (screenH - 40);

		context.getMatrices().push();
		context.getMatrices().translate(0.0f, dy, 0.0f);

		boolean visible = chatH > 0
				&& !mc.options.hudHidden
				&& mc.options.getChatVisibility().getValue() != ChatVisibility.HIDDEN;
		if (visible) {
			// 用"平移前的聊天框坐标"画底与边框，矩阵会带着它们一起挪到左上角。
			int origBottom = screenH - 40;
			int y0 = origBottom - totalH - PAD_Y;
			int y1 = origBottom + PAD_Y;
			int x0 = 0;
			int x1 = chatW + PAD_X * 2;
			context.fill(x0, y0, x1, y1, 0x99000000);
			context.fill(x0, y0, x1, y0 + 1, 0xFF6A7A8A);
			context.fill(x0, y1 - 1, x1, y1, 0xFF6A7A8A);
			context.fill(x0, y0, x0 + 1, y1, 0xFF6A7A8A);
			context.fill(x1 - 1, y0, x1, y1, 0xFF6A7A8A);
			// 标题："聊天框"居中在边框顶部（只有有消息时才画这个框）
			String title = "聊天框";
			context.drawText(mc.textRenderer, title,
					x0 + (x1 - x0 - mc.textRenderer.getWidth(title)) / 2, y0 + 3, 0xFFA8B8C8, true);
			context.fill(x0 + 1, y0 + titleH - 1, x1 - 1, y0 + titleH, 0x556A7A8A);
		}
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void nbw$restoreChatMatrix(DrawContext context, int currentTick, int mouseX, int mouseY,
			boolean focused, CallbackInfo ci) {
		context.getMatrices().pop();
	}
}
