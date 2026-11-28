package dev.nbw.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.Vec3d;

/**
 * 木筏视角（2026-11-28 用户需求版）。
 *
 * <ul>
 *   <li>开筏（遥控器移动模式）时切到<b>第三人称</b>，玩家身体原地显示、动作照常，手里不显示物品</li>
 *   <li>相机在木筏后上方；滚轮调距离（初始最远 = 看到整个木筏，最近 5 格）</li>
 *   <li>进入/退出用 0.22s 的逐帧混合，165Hz 下也是连续的</li>
 *   <li>木筏位置用"服务端 10Hz 数据 + 外插 + 逐帧指数平滑"，移动时相机不卡</li>
 * </ul>
 */
public final class RaftCameraClient {

	public static final double MIN_DISTANCE = 5.0;
	public static final double MAX_DISTANCE = 14.0;
	/** 进入/退出过渡时长（秒）。 */
	private static final double BLEND_SECONDS = 0.22;
	/** 木筏位姿的逐帧平滑系数（越大越跟手，越小越平滑）。 */
	private static final double POSE_SMOOTH = 18.0;

	private static double distance = MAX_DISTANCE;
	private static boolean engaged;
	/** 0 = 普通视角，1 = 木筏视角；逐帧向目标移动。 */
	private static double blend;
	private static long lastFrameNanos;

	/** 过渡起点（普通视角的位置/朝向），进入与退出时各采样一次。 */
	private static Vec3d anchorPos = Vec3d.ZERO;
	private static float anchorYaw;
	private static float anchorPitch;

	/** 平滑后的木筏相机目标。 */
	private static Vec3d smoothRaftPos;
	private static float smoothRaftYaw;
	private static float smoothRaftPitch;

	private static Perspective previousPerspective;
	private static boolean perspectiveChanged;

	private RaftCameraClient() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(RaftCameraClient::tick);
	}

	/** 是否处于"想开木筏视角"的状态（移动模式 + 有本队筏 + 地图数据新鲜）。 */
	private static boolean wants(MinecraftClient mc) {
		return mc.player != null && mc.world != null
				&& MoveInputClient.isMoveMode()
				&& MinimapClient.fresh()
				&& MinimapClient.data() != null
				&& MinimapClient.data().hasRaft();
	}

	private static void tick(MinecraftClient mc) {
		if (mc.player == null) {
			return;
		}
		boolean want = wants(mc);
		if (want && !engaged) {
			engaged = true;
			distance = MAX_DISTANCE;
			lastFrameNanos = 0;
			anchorPos = mc.player.getEyePos();
			anchorYaw = mc.player.getYaw();
			anchorPitch = mc.player.getPitch();
			smoothRaftPos = null;
			previousPerspective = mc.options.getPerspective();
			if (previousPerspective != Perspective.THIRD_PERSON_BACK) {
				mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
				perspectiveChanged = true;
			}
		} else if (!want && engaged) {
			engaged = false;
			anchorPos = mc.player.getEyePos();
			anchorYaw = mc.player.getYaw();
			anchorPitch = mc.player.getPitch();
		}
		// 退出过渡播完后再恢复原视角（否则会在过渡中途从第三人称猛地切回第一人称）
		if (!engaged && blend <= 0.001 && perspectiveChanged) {
			mc.options.setPerspective(previousPerspective == null
					? Perspective.FIRST_PERSON : previousPerspective);
			perspectiveChanged = false;
		}
	}

	/** 鼠标滚轮回调：返回 true = 已消费（不要再切快捷栏）。 */
	public static boolean onScroll(double vertical) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (!wants(mc)) {
			return false;
		}
		distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance - vertical * 0.9));
		return true;
	}

	/**
	 * 每帧调用（Camera.update 之后）：返回 {@code {x,y,z,yaw,pitch}}，或 null = 不覆盖相机。
	 */
	public static double[] step() {
		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0.016 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
		lastFrameNanos = now;

		double target = engaged ? 1.0 : 0.0;
		double step = dt / BLEND_SECONDS;
		if (blend < target) {
			blend = Math.min(target, blend + step);
		} else {
			blend = Math.max(target, blend - step);
		}
		if (!engaged && blend <= 0.0001) {
			return null;   // 已经完全回到普通视角
		}

		double[] pose = MinimapClient.smoothRaftPose(System.currentTimeMillis());
		if (pose == null) {
			return null;
		}
		double yawRad = Math.toRadians(pose[3]);
		double height = Math.max(3.0, distance * 0.55);
		Vec3d raftTarget = new Vec3d(
				pose[0] + Math.sin(yawRad) * distance,
				pose[1] + height,
				pose[2] - Math.cos(yawRad) * distance);
		float raftYaw = (float) pose[3];
		float raftPitch = (float) Math.toDegrees(Math.atan2(height, distance));

		if (smoothRaftPos == null) {
			smoothRaftPos = raftTarget;
			smoothRaftYaw = raftYaw;
			smoothRaftPitch = raftPitch;
		} else {
			double alpha = dt <= 0 ? 0.0 : 1.0 - Math.exp(-POSE_SMOOTH * dt);
			smoothRaftPos = smoothRaftPos.lerp(raftTarget, alpha);
			smoothRaftYaw = lerpAngle(smoothRaftYaw, raftYaw, (float) alpha);
			smoothRaftPitch += (raftPitch - smoothRaftPitch) * (float) alpha;
		}

		double t = blend * blend * (3.0 - 2.0 * blend);   // smoothstep
		double x = anchorPos.x + (smoothRaftPos.x - anchorPos.x) * t;
		double y = anchorPos.y + (smoothRaftPos.y - anchorPos.y) * t;
		double z = anchorPos.z + (smoothRaftPos.z - anchorPos.z) * t;
		float yaw = lerpAngle(anchorYaw, smoothRaftYaw, (float) t);
		float pitch = anchorPitch + (smoothRaftPitch - anchorPitch) * (float) t;
		return new double[]{x, y, z, yaw, pitch};
	}

	private static float lerpAngle(float a, float b, float t) {
		float d = b - a;
		while (d > 180.0f) {
			d -= 360.0f;
		}
		while (d < -180.0f) {
			d += 360.0f;
		}
		return a + d * t;
	}
}
