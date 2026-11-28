package dev.nbw.client;

import dev.nbw.content.NbwContent;
import dev.nbw.net.MinimapPayload;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * 手持遥控器时，本队筏心的<b>方块边缘描边发光</b>（2026-11-28 用户需求）。
 *
 * <p>不是粒子，而是围着筏心方块画三层发光线框（越往外越淡），并且只在
 * <b>手持遥控器的本地玩家</b>客户端绘制——服务端不发任何包、别人看不到。
 */
public final class RaftCoreOutlineClient {

	private RaftCoreOutlineClient() {
	}

	public static void register() {
		WorldRenderEvents.LAST.register(RaftCoreOutlineClient::render);
	}

	private static void render(WorldRenderContext context) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null || context.camera() == null) {
			return;
		}
		if (!holdingRemote(mc)) {
			return;
		}
		MinimapPayload d = MinimapClient.data();
		if (d == null || !d.hasRaft() || !MinimapClient.fresh()) {
			return;
		}
		if (mc.player.squaredDistanceTo(d.coreX(), d.coreY(), d.coreZ()) > 96.0 * 96.0) {
			return;
		}
		double[] pose = MinimapClient.smoothRaftPose(System.currentTimeMillis());
		if (pose == null) {
			return;
		}
		// 核心相对筏位姿的偏移（原始包算偏移，再叠到平滑位姿上）
		double cx = pose[0] + (d.coreX() - d.raftX());
		double cy = pose[1] + (d.coreY() - d.raftY());
		double cz = pose[2] + (d.coreZ() - d.raftZ());

		// 必须用世界渲染矩阵：它已经包含相机旋转。自建单位矩阵会漏掉旋转，
		// 表现为描边随着视角偏移一个方向（用户 18:05 截图里的偏移就是这个）。
		MatrixStack matrices = context.matrixStack();
		if (matrices == null) {
			return;
		}
		Vec3d cam = context.camera().getPos();
		// 用客户端自己的立即缓冲，不依赖 WorldRenderContext.consumers() 是否为空
		VertexConsumerProvider.Immediate consumers = mc.getBufferBuilders().getEntityVertexConsumers();
		VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());

		matrices.push();
		matrices.translate(cx - cam.x, cy - cam.y, cz - cam.z);
		// 注意：不要在这里再乘相机旋转，世界矩阵已经带上了。
		// 方块随筏偏航；忽略极小的俯仰/横滚
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-d.raftYaw()));
		// 外层光晕 → 中层 → 亮边
		WorldRenderer.drawBox(matrices, lines, -0.56, -0.56, -0.56, 0.56, 0.56, 0.56,
				1.0f, 0.62f, 0.10f, 0.18f);
		WorldRenderer.drawBox(matrices, lines, -0.53, -0.53, -0.53, 0.53, 0.53, 0.53,
				1.0f, 0.78f, 0.25f, 0.45f);
		WorldRenderer.drawBox(matrices, lines, -0.5, -0.5, -0.5, 0.5, 0.5, 0.5,
				1.0f, 0.94f, 0.55f, 0.95f);
		matrices.pop();

		consumers.draw(RenderLayer.getLines());
	}

	private static boolean holdingRemote(MinecraftClient mc) {
		return mc.player.getMainHandStack().isOf(NbwContent.remote)
				|| mc.player.getOffHandStack().isOf(NbwContent.remote);
	}
}
