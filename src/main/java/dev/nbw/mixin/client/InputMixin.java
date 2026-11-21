package dev.nbw.mixin.client;

import dev.nbw.client.MoveInputClient;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 遥控器"移动模式"里，WASD 只开船、**不许人物自己走**。
 *
 * <p>为什么要 mixin：玩家的移动是客户端自己算的 —— {@code ClientPlayerEntity.tickMovement()}
 * 里先调 {@code Input.tick()} 从按键算出 movementForward/Sideways，再交给
 * {@code LivingEntity.travel()} 施加位移。Fabric 的 tick 事件都在实体 tick 之外，
 * 在 START 里清零会被 {@code Input.tick()} 覆盖、在 END 里清零这一 tick 已经走完了。
 * 唯一干净的位置就是 {@code Input.tick()} 返回之后、{@code travel()} 之前，也就是这里。
 *
 * <p><b>2026-11-21 第二次修（关键）</b>：一开始 @Mixin 到父类 {@code Input} 上，完全没效果 ——
 * 因为客户端玩家的输入实例是子类 {@code KeyboardInput}，它<b>重写了 tick()</b>，
 * 虚分派到子类后父类方法体根本不执行，注入点自然永远不触发。
 * 必须直接瞄准 {@code KeyboardInput}。判据：改完之后移动模式下按 W 人物不动。
 *
 * <p>只清水平移动与跳跃；视角转动（鼠标）保持原样，否则开船时没法环顾四周。
 */
@Mixin(KeyboardInput.class)
public class InputMixin {

	@Inject(method = "tick", at = @At("RETURN"))
	private void nbw$suppressWalkWhileRemote(CallbackInfo ci) {
		if (!MoveInputClient.isMoveMode()) {
			return;
		}
		KeyboardInput self = (KeyboardInput) (Object) this;
		self.movementForward = 0.0f;
		self.movementSideways = 0.0f;
		self.jumping = false;
	}
}
