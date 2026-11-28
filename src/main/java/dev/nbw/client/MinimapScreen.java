package dev.nbw.client;

import dev.nbw.net.MinimapPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * 按 M 打开的大地图（2026-11-28）。
 *
 * <p>打开/收起按<b>真实时间</b>做动画（不是按 tick 计数），渲染每帧重新取进度，
 * 所以 60 FPS 下是连续的、不会一格一格跳。手部动画已按用户要求删除。
 *
 * <p>右键在图上标点；<b>左键点中已有标点 = 删除那个标点</b>。
 * 看地图期间由 {@link MinimapClient} 清零移动输入。
 */
public final class MinimapScreen extends Screen {

	/** 打开/收起动画时长（毫秒）。 */
	private static final long ANIM_MS = 160L;

	private final long openedAt = System.nanoTime();
	private long closeAt;
	private boolean closing;

	/** 最近一次渲染的投影：鼠标点击复用它换算世界坐标。 */
	private MinimapClient.Projection lastProjection;

	public MinimapScreen() {
		super(Text.literal("地图"));
	}

	public boolean isClosing() {
		return closing;
	}

	public void requestClose() {
		if (!closing) {
			closing = true;
			closeAt = System.nanoTime();
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private float progress() {
		long now = System.nanoTime();
		if (closing) {
			return Math.max(0.0f, 1.0f - (now - closeAt) / (ANIM_MS * 1_000_000.0f));
		}
		return Math.min(1.0f, (now - openedAt) / (ANIM_MS * 1_000_000.0f));
	}

	@Override
	public void tick() {
		if (!MinimapClient.fresh()) {
			requestClose();
		}
		if (closing && System.nanoTime() - closeAt >= ANIM_MS * 1_000_000L) {
			close();
		}
	}

	private MinimapClient.Projection projectionFor(MinecraftClient mc, MinimapPayload d, Vec3d self, float p) {
		int w = mc.getWindow().getScaledWidth();
		int h = mc.getWindow().getScaledHeight();
		int full = Math.max(80, Math.min(w - 80, h - 80));
		int ds = Math.round(full * (0.40f + 0.60f * p));
		int cx = w / 2 + Math.round((1.0f - p) * w * 0.18f);
		int cy = h / 2 + Math.round((1.0f - p) * h * 0.22f);
		int x0 = cx - ds / 2;
		int y0 = cy - ds / 2;

		double zoneR = MinimapClient.smoothedZoneRadius(d);
		double worldCX = zoneR > 0.0 ? d.zoneX() : self.x;
		double worldCZ = zoneR > 0.0 ? d.zoneZ() : self.z;
		double range = zoneR > 0.0 ? Math.max(60.0, zoneR * 1.18) : 110.0;
		double scale = ds / (2.0 * range);
		return new MinimapClient.Projection(worldCX, worldCZ, scale, cx, cy, x0, y0, ds);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		MinecraftClient mc = this.client;
		MinimapPayload d = MinimapClient.data();
		if (mc == null || mc.player == null || d == null) {
			return;
		}
		float p = progress();
		int w = mc.getWindow().getScaledWidth();
		int h = mc.getWindow().getScaledHeight();
		Vec3d self = mc.player.getLerpedPos(delta);

		context.fill(0, 0, w, h, 0x66000000);

		MinimapClient.Projection pj = projectionFor(mc, d, self, p);
		lastProjection = pj;
		int x0 = pj.x0();
		int y0 = pj.y0();
		int x1 = x0 + pj.size();
		int y1 = y0 + pj.size();

		// 纸张
		MinimapClient.drawRect(context, x0 - 7, y0 - 7, x1 + 7, y1 + 7, 0xFF6B5332);
		MinimapClient.drawRect(context, x0 - 5, y0 - 5, x1 + 5, y1 + 5, 0xFFE8D8A8);
		MinimapClient.drawRect(context, x0, y0, x1, y1, 0xB0101820);
		MinimapClient.renderContent(context, mc, d, self, pj, true);

		context.drawText(mc.textRenderer, "地图", x0, y0 - 20, 0xFFFFF2CC, true);
		context.drawText(mc.textRenderer, "M / Esc 收起  ·  右键标点  ·  左键点标点删除",
				x0 + 40, y0 - 20, 0xFFCFC3A0, true);
		context.drawText(mc.textRenderer, "标点 " + MinimapClient.markers.size() + "/" + MinimapClient.MAX_MARKERS,
				x1 - 70, y1 + 10, 0xFFFFCC55, true);

		if (mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1) {
			context.fill(mouseX - 3, mouseY, mouseX + 4, mouseY + 1, 0xFFFFAA00);
			context.fill(mouseX, mouseY - 3, mouseX + 1, mouseY + 4, 0xFFFFAA00);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		MinecraftClient mc = this.client;
		MinimapPayload d = MinimapClient.data();
		if (mc == null || mc.player == null || d == null || lastProjection == null) {
			return super.mouseClicked(mouseX, mouseY, button);
		}
		MinimapClient.Projection pj = lastProjection;
		boolean inside = mouseX >= pj.x0() && mouseX < pj.x0() + pj.size()
				&& mouseY >= pj.y0() && mouseY < pj.y0() + pj.size();
		if (!inside) {
			return super.mouseClicked(mouseX, mouseY, button);
		}

		if (button == 0) {
			// 左键点中已有标点 = 删除它
			int best = -1;
			double bestDist = 10.0 * 10.0;
			for (int i = 0; i < MinimapClient.markers.size(); i++) {
				MinimapClient.Marker m = MinimapClient.markers.get(i);
				double sx = pj.px(m.x());
				double sy = pj.pz(m.z());
				double dd = (sx - mouseX) * (sx - mouseX) + (sy - mouseY) * (sy - mouseY);
				if (dd < bestDist) {
					bestDist = dd;
					best = i;
				}
			}
			if (best >= 0) {
				MinimapClient.markers.remove(best);
				mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 0.8f);
			}
			return true;
		}

		if (button == 1) {
			if (hasShiftDown()) {
				MinimapClient.markers.clear();
				mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 0.7f);
			} else if (MinimapClient.markers.size() < MinimapClient.MAX_MARKERS) {
				double wx = pj.wx((int) mouseX);
				double wz = pj.wz((int) mouseY);
				MinimapClient.markers.add(new MinimapClient.Marker(wx, wz));
				mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.7f, 1.3f);
			}
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_M || keyCode == GLFW.GLFW_KEY_ESCAPE) {
			requestClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
