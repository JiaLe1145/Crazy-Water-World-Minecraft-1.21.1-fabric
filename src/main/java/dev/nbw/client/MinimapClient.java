package dev.nbw.client;

import dev.nbw.net.MinimapPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 小地图客户端（2026-11-28）。
 *
 * <p>右上角小地图：本队木筏、队友、黑海边界、自己的位置（本局随机颜色）。
 * 按 M 打开大图；大图期间锁移动。右键标点，左键对准标点删除。
 *
 * <p><b>平滑</b>：服务端每 4 tick 发一次位置，客户端在两次数据之间做外插；
 * 自己的位置用 {@code getLerpedPos(tickDelta)}，所以 60 FPS 下地图与视角标点都是连续移动，
 * 不会像原来那样按 tick 一格一格跳。
 */
public final class MinimapClient {

	private static final long STALE_MS = 2500L;
	public static final int MAX_MARKERS = 12;

	private static KeyBinding mapKey;
	private static MinimapPayload data;
	private static long lastReceiveMs;
	private static int lastMatch = Integer.MIN_VALUE;

	/** 本局标点（客户端本地）。换局清空。 */
	public static final List<Marker> markers = new ArrayList<>();

	/** 队友位置的两次采样：渲染时按时间外插，消除 4 tick 一跳的台阶。 */
	private static final Map<String, Track> memberTracks = new HashMap<>();
	/** 幸存者按列表下标跟踪（服务端顺序稳定）；全部固定绿色。 */
	private static final Map<Integer, Track> survivorTracks = new HashMap<>();
	private static Track raftTrack;
	/** 木筏位姿（x/y/z/yaw）的两次采样：木筏视角相机用。 */
	private static PoseTrack raftPose;
	private static final ZoneTrack zoneTrack = new ZoneTrack();

	private record PoseTrack(double pX, double pY, double pZ, double pYaw,
			double cX, double cY, double cZ, double cYaw, long pT, long cT) {
	}

	/** 幸存者统一绿色（玩家颜色会避开这个色系）。 */
	private static final int SURVIVOR_GREEN = 0xFF3BD94A;

	public record Marker(double x, double z) {
	}

	/** 两次位置采样（p=上一次，c=这一次）。 */
	private record Track(double pX, double pZ, double cX, double cZ, long pT, long cT) {
	}

	private static final class ZoneTrack {
		double prev = -1.0;
		double cur = -1.0;
		long prevT;
		long curT;

		void push(double r, long now) {
			if (r <= 0.0) {
				prev = cur = -1.0;
				return;
			}
			prev = cur;
			prevT = curT;
			cur = r;
			curT = now;
		}

		double now() {
			if (cur < 0.0) {
				return -1.0;
			}
			if (prev < 0.0 || curT <= prevT) {
				return cur;
			}
			long now = System.currentTimeMillis();
			double f = Math.min(1.0, (now - curT) / (double) (curT - prevT));
			return cur + (cur - prev) * f;
		}
	}

	private MinimapClient() {
	}

	public static void register() {
		mapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.nbw.map", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.nbw"));

		ClientPlayNetworking.registerGlobalReceiver(MinimapPayload.ID, (payload, context) ->
				context.client().execute(() -> {
					long now = System.currentTimeMillis();
					if (payload.matchIndex() != lastMatch) {
						markers.clear();
						memberTracks.clear();
						survivorTracks.clear();
						raftTrack = null;
						lastMatch = payload.matchIndex();
					}
					for (MinimapPayload.Member m : payload.teammates()) {
						Track old = memberTracks.get(m.name());
						memberTracks.put(m.name(), old == null
								? new Track(m.x(), m.z(), m.x(), m.z(), now, now)
								: new Track(old.cX(), old.cZ(), m.x(), m.z(), old.cT(), now));
					}
					for (int i = 0; i < payload.survivors().size(); i++) {
						MinimapPayload.Survivor sv = payload.survivors().get(i);
						Track old = survivorTracks.get(i);
						survivorTracks.put(i, old == null
								? new Track(sv.x(), sv.z(), sv.x(), sv.z(), now, now)
								: new Track(old.cX(), old.cZ(), sv.x(), sv.z(), old.cT(), now));
					}
					final int survivorCount = payload.survivors().size();
					survivorTracks.keySet().removeIf(k -> k >= survivorCount);
					if (payload.hasRaft()) {
						raftTrack = raftTrack == null
								? new Track(payload.raftX(), payload.raftZ(), payload.raftX(), payload.raftZ(), now, now)
								: new Track(raftTrack.cX(), raftTrack.cZ(), payload.raftX(), payload.raftZ(),
										raftTrack.cT(), now);
						raftPose = raftPose == null
								? new PoseTrack(payload.raftX(), payload.raftY(), payload.raftZ(), payload.raftYaw(),
										payload.raftX(), payload.raftY(), payload.raftZ(), payload.raftYaw(), now, now)
								: new PoseTrack(raftPose.cX(), raftPose.cY(), raftPose.cZ(), raftPose.cYaw(),
										payload.raftX(), payload.raftY(), payload.raftZ(), payload.raftYaw(),
										raftPose.cT(), now);
					} else {
						raftTrack = null;
						raftPose = null;
					}
					zoneTrack.push(payload.zoneRadius(), now);
					data = payload;
					lastReceiveMs = now;
				}));

		ClientTickEvents.END_CLIENT_TICK.register(MinimapClient::tick);
		HudRenderCallback.EVENT.register(MinimapClient::renderHud);
	}

	public static MinimapPayload data() {
		return data;
	}

	public static boolean fresh() {
		return data != null && System.currentTimeMillis() - lastReceiveMs < STALE_MS;
	}

	/**
	 * 平滑后的木筏位姿 {@code {x, y, z, yaw}}；筏不在场返回 null。
	 * 与队友位置同一套外插：服务端 4 tick 一发，渲染每帧取值。
	 */
	public static double[] smoothRaftPose(long now) {
		PoseTrack t = raftPose;
		if (t == null) {
			return null;
		}
		if (t.cT <= t.pT) {
			return new double[]{t.cX, t.cY, t.cZ, t.cYaw};
		}
		double f = Math.min(1.0, (now - t.cT) / (double) (t.cT - t.pT));
		double yawDelta = t.cYaw - t.pYaw;
		while (yawDelta > 180.0) {
			yawDelta -= 360.0;
		}
		while (yawDelta < -180.0) {
			yawDelta += 360.0;
		}
		return new double[]{
				t.cX + (t.cX - t.pX) * f,
				t.cY + (t.cY - t.pY) * f,
				t.cZ + (t.cZ - t.pZ) * f,
				t.cYaw + yawDelta * f};
	}

	/** 平滑后的黑海半径（大图投影用）。 */
	public static double smoothedZoneRadius(MinimapPayload d) {
		double r = zoneTrack.now();
		return r > 0.0 ? r : d.zoneRadius();
	}

	private static void tick(MinecraftClient mc) {
		if (mc.player == null) {
			return;
		}
		while (mapKey.wasPressed()) {
			if (mc.currentScreen instanceof MinimapScreen screen) {
				screen.requestClose();
			} else if (mc.currentScreen == null && fresh()) {
				mc.setScreen(new MinimapScreen());
			}
		}
		// 看地图期间不能移动。
		if (mc.currentScreen instanceof MinimapScreen screen && !screen.isClosing()) {
			mc.player.input.movementForward = 0.0f;
			mc.player.input.movementSideways = 0.0f;
			mc.player.input.jumping = false;
			mc.player.input.sneaking = false;
			mc.player.setSprinting(false);
			mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);
		}
		// 左键对准标点 = 删除该标点（大图里左键点标点也删）。
		if (mc.currentScreen == null && mc.options.attackKey.wasPressed() && !markers.isEmpty()) {
			deleteMarkerUnderCrosshair(mc);
		}
	}

	/** 视线 6° 内最近的标点删除。 */
	private static void deleteMarkerUnderCrosshair(MinecraftClient mc) {
		if (mc.player == null) {
			return;
		}
		Vec3d eye = mc.player.getEyePos();
		Vec3d look = mc.player.getRotationVec(1.0f).normalize();
		int best = -1;
		double bestDot = Math.cos(Math.toRadians(6.0));
		for (int i = 0; i < markers.size(); i++) {
			Marker m = markers.get(i);
			Vec3d to = new Vec3d(m.x() - eye.x, eye.y - eye.y, m.z() - eye.z).normalize();
			double dot = to.dotProduct(look);
			if (dot > bestDot) {
				bestDot = dot;
				best = i;
			}
		}
		if (best >= 0) {
			markers.remove(best);
			mc.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 0.8f);
		}
	}

	// ------------------------------------------------------------------ HUD

	private static void renderHud(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden || !fresh()) {
			return;
		}
		if (mc.currentScreen instanceof MinimapScreen) {
			return;
		}
		MinimapPayload d = data;
		float delta = tickCounter.getTickDelta(false);
		Vec3d self = mc.player.getLerpedPos(delta);
		int w = mc.getWindow().getScaledWidth();
		int size = 108;
		int x = w - size - 8;
		int y = 8;
		renderMap(context, mc, d, self, x, y, size, false);

		renderRoster(context, mc, d);
		renderMarkerHud(context, mc, d, self, delta);
	}

	/**
	 * 左下角队伍面板：队伍名 + 每名成员的名字与本局颜色。
	 * 位置在营养/水分条正上方；死亡成员名字变灰。
	 */
	private static void renderRoster(DrawContext context, MinecraftClient mc, MinimapPayload d) {
		int lineH = 10;
		String title = d.teamName() + "  " + d.teamAlive() + "/" + d.teamSize();
		int width = mc.textRenderer.getWidth(title) + 24;
		for (MinimapPayload.Roster r : d.roster()) {
			width = Math.max(width, mc.textRenderer.getWidth(r.name()) + 26);
		}
		width = Math.max(82, Math.min(160, width));
		int lines = 1 + d.roster().size();
		int bottom = mc.getWindow().getScaledHeight() - 40;   // 营养条上沿再留 6px
		int top = bottom - lines * lineH - 4;
		int x0 = 4;
		int x1 = x0 + width;

		context.fill(x0, top, x1, bottom, 0x77000000);
		// 边框
		context.fill(x0, top, x1, top + 1, 0xFF6A7A8A);
		context.fill(x0, bottom - 1, x1, bottom, 0xFF6A7A8A);
		context.fill(x0, top, x0 + 1, bottom, 0xFF6A7A8A);
		context.fill(x1 - 1, top, x1, bottom, 0xFF6A7A8A);
		// 队伍色条
		context.fill(x0 + 1, top + 1, x0 + 3, bottom - 1, d.selfColor());

		String selfName = mc.getSession().getUsername();
		int y = top + 3;
		context.fill(x0 + 6, y + 1, x0 + 10, y + 5, d.selfColor());
		context.drawText(mc.textRenderer, title, x0 + 13, y, 0xFFFFFFFF, true);
		y += lineH;
		for (MinimapPayload.Roster r : d.roster()) {
			context.fill(x0 + 7, y + 1, x0 + 11, y + 5, r.alive() ? r.color() : 0xFF555555);
			String name = r.name().equals(selfName) ? r.name() + " · 你" : r.name();
			context.drawText(mc.textRenderer, name, x0 + 14, y,
					r.alive() ? 0xFFFFFFFF : 0xFF8A8A8A, true);
			if (r.name().equals(selfName)) {
				// 状态标志常驻在自己名字后面（首次出现时在屏幕中央弹一下）
				int bx = x0 + 14 + mc.textRenderer.getWidth(name) + 4;
				for (StatusIcons.Kind k : StatusHudClient.activeIcons()) {
					// 8px = 64px 贴图的 1/8，整数倍缩放保持清晰
					StatusIcons.drawBadge(context, mc, bx, y, 8, k, 1.0f);
					bx += 9;
				}
			}
			y += lineH;
		}
	}

	/** 画一张地图（小地图与大图共用）。 */
	public static void renderMap(DrawContext context, MinecraftClient mc, MinimapPayload d,
			Vec3d self, int x, int y, int size, boolean big) {
		Projection p = projection(mc, d, self, x, y, size, big);
		drawRect(context, x, y, x + size, y + size, 0xB0101820);
		renderContent(context, mc, d, self, p, big);

		drawRect(context, x, y, x + size, y + 1, 0xFF3A4A5A);
		drawRect(context, x, y + size - 1, x + size, y + size, 0xFF3A4A5A);
		drawRect(context, x, y, x + 1, y + size, 0xFF3A4A5A);
		drawRect(context, x + size - 1, y, x + size, y + size, 0xFF3A4A5A);
		context.drawText(mc.textRenderer, "N", x + size / 2 - 2, y + 2, 0xFFCCCCCC, false);
	}

	/** 只画地图内容（不画底板/边框）。 */
	public static void renderContent(DrawContext context, MinecraftClient mc, MinimapPayload d,
			Vec3d self, Projection p, boolean big) {
		int x = p.x0();
		int y = p.y0();
		int size = p.size();
		long now = System.currentTimeMillis();
		double zoneR = smoothedZoneRadius(d);

		if (zoneR > 0.0) {
			shadeOutsideZone(context, d.zoneX(), d.zoneZ(), zoneR, p, x, y, size);
		}

		context.enableScissor(x, y, x + size, y + size);
		if (zoneR > 0.0) {
			drawCircle(context, p, d.zoneX(), d.zoneZ(), zoneR, 0xFFFF4444);
		}
		// 本队木筏（平滑）
		if (d.hasRaft() && raftTrack != null) {
			double[] rp = smooth(raftTrack, now);
			int rx = p.px(rp[0]);
			int ry = p.py(rp[1]);
			context.fill(rx - 4, ry - 4, rx + 4, ry + 4, 0xFF3A2E22);
			context.fill(rx - 3, ry - 3, rx + 3, ry + 3, 0xFFB08A5A);
			if (big) {
				context.drawText(mc.textRenderer, "筏", rx + 6, ry - 4, 0xFFD9C39A, true);
			}
		}
		// 幸存者（NPC）：统一绿色，与玩家队友区分
		for (int i = 0; i < d.survivors().size(); i++) {
			double[] sp = smooth(survivorTracks.get(i), now);
			int mx = p.px(sp[0]);
			int my = p.py(sp[1]);
			// 比玩家队友（5×5）小一圈：4×4 描边 + 2×2 绿点
			context.fill(mx - 2, my - 2, mx + 2, my + 2, 0xFF15331A);
			context.fill(mx - 1, my - 1, mx + 1, my + 1, SURVIVOR_GREEN);
			if (big) {
				context.drawText(mc.textRenderer, "幸存者", mx + 5, my - 4, SURVIVOR_GREEN, true);
			}
		}
		// 队友（平滑）
		for (MinimapPayload.Member m : d.teammates()) {
			Track tr = memberTracks.get(m.name());
			double[] mp = smooth(tr, now);
			int mx = p.px(mp[0]);
			int my = p.py(mp[1]);
			context.fill(mx - 2, my - 2, mx + 3, my + 3, 0xFFFFFFFF);
			context.fill(mx - 1, my - 1, mx + 2, my + 2, m.color());
			if (big) {
				context.drawText(mc.textRenderer, m.name(), mx + 5, my - 4, 0xFFFFFFFF, true);
			}
		}
		// 自己（用插值坐标）
		int selfX = p.px(self.x);
		int selfY = p.pz(self.z);
		drawSelfArrow(context, mc, p, selfX, selfY, d.selfColor());
		if (big) {
			context.drawText(mc.textRenderer, "你", selfX + 6, selfY - 4, d.selfColor(), true);
		}
		// 标点（大图上加脉动光晕）
		for (int i = 0; i < markers.size(); i++) {
			Marker m = markers.get(i);
			int mx = p.px(m.x());
			int my = p.py(m.z());
			if (big) {
				double pulse = 0.5 + 0.5 * Math.sin(now / 180.0 + i);
				drawDiamond(context, mx, my, 6 + (int) Math.round(pulse * 2), 0x33FFAA00);
				drawDiamond(context, mx, my, 4, 0xFFFFAA00);
				context.drawText(mc.textRenderer, String.valueOf(i + 1), mx + 7, my - 4, 0xFFFFCC55, true);
			} else {
				drawDiamond(context, mx, my, 3, 0xFFFFAA00);
			}
		}
		context.disableScissor();
	}

	/** 小图以自己为中心，大图以圈心为中心。 */
	public static Projection projection(MinecraftClient mc, MinimapPayload d, Vec3d self,
			int x, int y, int size, boolean big) {
		double cxWorld = self.x;
		double czWorld = self.z;
		double zoneR = smoothedZoneRadius(d);
		double range = 96.0;
		if (big && zoneR > 0.0) {
			cxWorld = d.zoneX();
			czWorld = d.zoneZ();
			range = Math.max(60.0, zoneR * 1.18);
		} else if (zoneR > 0.0) {
			range = Math.max(80.0, zoneR * 1.35);
		}
		double scale = size / (2.0 * range);
		return new Projection(cxWorld, czWorld, scale, x + size / 2, y + size / 2, x, y, size);
	}

	public record Projection(double worldCX, double worldCZ, double scale,
			int cx, int cy, int x0, int y0, int size) {
		public int px(double wx) {
			return (int) Math.round(cx + (wx - worldCX) * scale);
		}

		public int py(double wz) {
			return (int) Math.round(cy + (wz - worldCZ) * scale);
		}

		/** 别名，避免把 z 误读成 y。 */
		public int pz(double wz) {
			return py(wz);
		}

		public double wx(int sx) {
			return worldCX + (sx - cx) / scale;
		}

		public double wz(int sy) {
			return worldCZ + (sy - cy) / scale;
		}
	}

	private static double[] smooth(Track t, long now) {
		if (t == null) {
			return new double[]{0.0, 0.0};
		}
		if (t.cT <= t.pT) {
			return new double[]{t.cX, t.cZ};
		}
		double f = Math.min(1.0, (now - t.cT) / (double) (t.cT - t.pT));
		return new double[]{t.cX + (t.cX - t.pX) * f, t.cZ + (t.cZ - t.pZ) * f};
	}

	private static void shadeOutsideZone(DrawContext context, double zx, double zz, double r,
			Projection p, int x, int y, int size) {
		int zcx = p.px(zx);
		int zcy = p.pz(zz);
		int rpx = (int) Math.round(r * p.scale());
		context.enableScissor(x, y, x + size, y + size);
		for (int yy = y; yy < y + size; yy++) {
			int dy = yy - zcy;
			if (Math.abs(dy) >= rpx) {
				context.fill(x, yy, x + size, yy + 1, 0x66000000);
				continue;
			}
			int hw = (int) Math.round(Math.sqrt((double) rpx * rpx - (double) dy * dy));
			context.fill(x, yy, Math.max(x, zcx - hw), yy + 1, 0x66000000);
			context.fill(Math.min(x + size, zcx + hw), yy, x + size, yy + 1, 0x66000000);
		}
		context.disableScissor();
	}

	private static void drawCircle(DrawContext context, Projection p, double wx, double wz,
			double worldR, int color) {
		int cx = p.px(wx);
		int cy = p.pz(wz);
		int r = (int) Math.round(worldR * p.scale());
		if (r <= 0) {
			return;
		}
		int steps = Math.max(48, Math.min(160, r * 4));
		for (int i = 0; i < steps; i++) {
			double a = Math.PI * 2.0 * i / steps;
			int px = cx + (int) Math.round(Math.cos(a) * r);
			int py = cy + (int) Math.round(Math.sin(a) * r);
			context.fill(px - 1, py - 1, px + 1, py + 1, color);
		}
	}

	private static void drawSelfArrow(DrawContext context, MinecraftClient mc, Projection p,
			int x, int y, int color) {
		context.fill(x - 3, y - 3, x + 4, y + 4, 0xFFFFFFFF);
		context.fill(x - 2, y - 2, x + 3, y + 3, color);
		double yaw = Math.toRadians(mc.player.getYaw());
		int dx = (int) Math.round(-Math.sin(yaw) * 8.0);
		int dy = (int) Math.round(Math.cos(yaw) * 8.0);
		fillLine(context, x, y, x + dx, y + dy, 1, 0xFFFFFFFF);
	}

	/**
	 * 视角内的标点：用真实相机投影画在标点方向对应的屏幕位置，
	 * 带脉动光晕、竖直光柱与距离；看不见（在身后）时贴边提示方向。
	 */
	private static void renderMarkerHud(DrawContext context, MinecraftClient mc, MinimapPayload d,
			Vec3d self, float delta) {
		if (markers.isEmpty()) {
			return;
		}
		int w = mc.getWindow().getScaledWidth();
		int h = mc.getWindow().getScaledHeight();
		// 用 Camera（每帧按 tickDelta 更新）而不是玩家的 tick 坐标：
		// 否则 165Hz 下仍然是 20Hz 的台阶。这是"标点跟不上视角"的根因。
		var camera = mc.gameRenderer.getCamera();
		Vec3d eye = camera.getPos();
		double yaw = Math.toRadians(camera.getYaw());
		double pitch = Math.toRadians(camera.getPitch());
		double cy = Math.cos(yaw), sy = Math.sin(yaw);
		double cp = Math.cos(pitch), sp = Math.sin(pitch);
		// 相机基向量（yaw 0 朝 +Z，yaw 90 朝 -X）
		double fx = -sy * cp, fy = -sp, fz = cy * cp;
		double rx = -cy, ry = 0.0, rz = -sy;
		// up = right × forward
		double ux = ry * fz - rz * fy;
		double uy = rz * fx - rx * fz;
		double uz = rx * fy - ry * fx;
		double fov = mc.options.getFov().getValue();
		double focal = (h / 2.0) / Math.tan(Math.toRadians(fov) / 2.0);
		long now = System.currentTimeMillis();
		double pulse = 0.5 + 0.5 * Math.sin(now / 200.0);

		for (int i = 0; i < markers.size(); i++) {
			Marker m = markers.get(i);
			double dx = m.x() - eye.x;
			double dz = m.z() - eye.z;
			// 标点按"脚下高度"投影（常量偏移，不再读 tick 坐标），避免竖直方向抖动
			double dy = -1.6;
			double viewX = dx * rx + dy * ry + dz * rz;
			double viewY = dx * ux + dy * uy + dz * uz;
			double viewZ = dx * fx + dy * fy + dz * fz;
			double dist = Math.sqrt(dx * dx + dz * dz);
			if (viewZ > 0.1) {
				int sx = (int) Math.round(w / 2.0 + viewX / viewZ * focal);
				int sy2 = (int) Math.round(h / 2.0 - viewY / viewZ * focal);
				int glow = 7 + (int) Math.round(pulse * 4);
				drawDiamond(context, sx, sy2, glow, 0x33FFAA00);
				drawDiamond(context, sx, sy2, 5, 0xAAFFB733);
				drawDiamond(context, sx, sy2, 2, 0xFFFFFFFF);
				// 竖直光柱，进一步把"点"标出来
				fillLine(context, sx, sy2 - 34, sx, sy2 - 8, 2, 0x66FFAA00);
				context.drawText(mc.textRenderer, (i + 1) + " · " + Math.round(dist) + "m",
						sx + 9, sy2 - 4, 0xFFFFCC55, true);
			} else {
				// 在身后：贴边显示方向
				boolean right = viewX > 0;
				int sx = right ? w - 20 : 20;
				int sy2 = h / 2;
				drawDiamond(context, sx, sy2, 5, 0xAAFFB733);
				context.drawText(mc.textRenderer, (i + 1) + " " + Math.round(dist) + "m",
						right ? sx - 52 : sx + 9, sy2 - 4, 0xFFFFCC55, true);
			}
		}
	}

	// ------------------------------------------------------------------ 基础绘制

	public static void drawRect(DrawContext context, int x1, int y1, int x2, int y2, int color) {
		context.fill(x1, y1, x2, y2, color);
	}

	public static void drawDiamond(DrawContext context, int cx, int cy, int r, int color) {
		for (int dy = -r; dy <= r; dy++) {
			int half = r - Math.abs(dy);
			context.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
		}
	}

	/** 1px 以上的直线（画朝向导引/光柱）。 */
	public static void fillLine(DrawContext context, int x1, int y1, int x2, int y2, int thickness, int color) {
		int dx = x2 - x1;
		int dy = y2 - y1;
		int steps = Math.max(Math.abs(dx), Math.abs(dy));
		if (steps == 0) {
			context.fill(x1, y1, x1 + thickness, y1 + thickness, color);
			return;
		}
		for (int i = 0; i <= steps; i++) {
			int x = x1 + dx * i / steps;
			int y = y1 + dy * i / steps;
			context.fill(x - thickness / 2, y - thickness / 2,
					x - thickness / 2 + thickness, y - thickness / 2 + thickness, color);
		}
	}
}
