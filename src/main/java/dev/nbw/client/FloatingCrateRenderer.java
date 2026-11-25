package dev.nbw.client;

import dev.nbw.domain.loot.FlotsamMotion;
import dev.nbw.loot.FloatingCrate;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/**
 * 海面补给箱的渲染（2026-11-25 新增）。
 *
 * <p>不画新模型：直接把 {@code minecraft:barrel} 的方块模型当实体画（{@code renderBlockAsEntity}），
 * 所以玩家看到的还是原来那只桶/箱子，只是现在会随浪起伏、会漂。
 *
 * <p><b>位置</b>：实体 y 已经由服务端算好（水面线 ± 起伏），渲染只补"摇晃"。上下起伏不能再在
 * 渲染里加一遍，否则会和位置同步的插值叠成双倍幅度。
 */
public class FloatingCrateRenderer extends EntityRenderer<FloatingCrate> {

	private static final BlockState MODEL = Blocks.BARREL.getDefaultState();

	/**
	 * {@code EntityRenderer#getTexture} 在 1.21.1 仍是抽象方法，但方块模型渲染不会去取它。
	 * 给一张一定存在的原版贴图，避免任何"顺手 bind 一下"的路径炸掉。
	 */
	private static final Identifier UNUSED_TEXTURE = Identifier.ofVanilla("textures/entity/chest/normal.png");

	public FloatingCrateRenderer(EntityRendererFactory.Context context) {
		super(context);
		this.shadowRadius = 0.35f;   // 水面上的一小块影子：有存在感，又不至于像压在甲板上
		this.shadowOpacity = 0.6f;
	}

	@Override
	public void render(FloatingCrate entity, float yaw, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		double ticks = entity.age + tickDelta;
		float roll = (float) FlotsamMotion.rollDeg(ticks, entity.bobPhase());
		float pitch = (float) FlotsamMotion.pitchDeg(ticks, entity.bobPhase());

		matrices.push();
		// 方块模型的原点在角上（0..1），实体的原点是碰撞箱底面中心 → 先挪半格对齐
		matrices.translate(-0.5, 0.0, -0.5);
		// 绕箱体中心转，否则摇晃会让箱子"甩"出去
		matrices.translate(0.5, 0.5, 0.5);
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
		matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll));
		matrices.translate(-0.5, -0.5, -0.5);
		MinecraftClient.getInstance().getBlockRenderManager()
				.renderBlockAsEntity(MODEL, matrices, vertexConsumers, light, OverlayTexture.DEFAULT_UV);
		matrices.pop();

		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
	}

	@Override
	public Identifier getTexture(FloatingCrate entity) {
		return UNUSED_TEXTURE;
	}
}
