package dev.nbw.mixin.client;

import dev.nbw.client.RaftCameraClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 木筏视角的相机覆盖（2026-11-28 用户需求）。
 *
 * <p>在 {@code Camera.update} 结束时改写位置与朝向：位置在木筏后上方，
 * 朝向看回木筏。退出移动模式后条件不成立，什么都不做，原版相机自然恢复。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

	@Shadow
	protected abstract void setPos(Vec3d pos);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("RETURN"))
	private void nbw$raftView(BlockView area, Entity focusedEntity, boolean thirdPerson,
			boolean inverseView, float tickDelta, CallbackInfo ci) {
		double[] out = RaftCameraClient.step();
		if (out == null) {
			return;
		}
		this.setPos(new Vec3d(out[0], out[1], out[2]));
		this.setRotation((float) out[3], (float) out[4]);
	}
}
