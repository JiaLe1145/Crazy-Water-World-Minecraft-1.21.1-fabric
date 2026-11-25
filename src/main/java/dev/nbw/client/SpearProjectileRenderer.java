package dev.nbw.client;

import dev.nbw.combat.SpearProjectileEntity;
import dev.nbw.content.NbwContent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 投出去的石矛的渲染（2026-11-25 新增）。
 *
 * <p>不建 3D 模型：直接用物品模型（{@code nbw:item/stone_spear}，贴图是 1.21.11 原版石矛的
 * 16×16 图），在飞行方向上身姿对齐——这样玩家看到的是"那支矛飞出去了"，而不是另一个替身模型。
 *
 * <p><b>为什么画两片（十字）</b>：物品模型是一片平面四边形，只看侧面积它才有厚度；如果只画一片，
 * 从正后方看（也就是投掷者自己最常看到的视角）它会变成一条线消失。绕矛身轴再转 90° 画第二片，
 * 任何角度都至少有一片可见。
 */
public class SpearProjectileRenderer extends EntityRenderer<SpearProjectileEntity> {

	/** {@code EntityRenderer#getTexture} 在 1.21.1 仍是抽象方法，方块/物品模型渲染不会取它。 */
	private static final Identifier UNUSED_TEXTURE = Identifier.ofVanilla("textures/entity/chest/normal.png");

	/** 朝向基准：物品模型的贴图里矛是斜的（左下→右上），先转 45° 让它沿本地 +Y。 */
	private static final float ART_DIAGONAL_DEG = 45.0f;

	/** 显示尺寸：物品模型是 1×1 格，×1.45 让矛看起来接近 2 格长。 */
	private static final float SCALE = 1.45f;

	private static final Vector3f UP = new Vector3f(0.0f, 1.0f, 0.0f);

	public SpearProjectileRenderer(EntityRendererFactory.Context context) {
		super(context);
		this.shadowRadius = 0.2f;
	}

	@Override
	public void render(SpearProjectileEntity entity, float yaw, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		ItemStack stack = entity.getItemStack();
		if (stack.isEmpty()) {
			if (NbwContent.stoneSpear == null) {
				return;
			}
			stack = new ItemStack(NbwContent.stoneSpear);
		}
		ItemRenderer itemRenderer = MinecraftClient.getInstance().getItemRenderer();
		BakedModel model = itemRenderer.getModel(stack, entity.getWorld(), null, 0);

		Vec3d velocity = entity.getVelocity();
		Vector3f dir = velocity.lengthSquared() < 0.01
				? new Vector3f(1.0f, 0.0f, 0.0f)   // 静止（插住/浮着）：平躺，别指向天上
				: new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z).normalize();

		matrices.push();
		matrices.translate(0.0, 0.25, 0.0);   // 实体原点在碰撞箱底面，抬到矛身中心
		matrices.multiply(new Quaternionf().rotationTo(UP, dir));   // 本地 +Y → 飞行方向
		for (int pass = 0; pass < 2; pass++) {
			matrices.push();
			if (pass == 1) {
				matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90));   // 绕矛身轴再画一片
			}
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(ART_DIAGONAL_DEG));   // 贴图对角 → +Y
			matrices.scale(SCALE, SCALE, SCALE);
			itemRenderer.renderItem(stack, ModelTransformationMode.NONE, false, matrices, vertexConsumers,
					light, OverlayTexture.DEFAULT_UV, model);
			matrices.pop();
		}
		matrices.pop();

		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
	}

	@Override
	public Identifier getTexture(SpearProjectileEntity entity) {
		return UNUSED_TEXTURE;
	}
}
