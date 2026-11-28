package dev.nbw.mixin.client;

import dev.nbw.client.RaftCameraClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 木筏视角下的滚轮缩放（2026-11-28 用户需求）。
 *
 * <p>只在木筏视角生效；生效时取消原版滚轮（否则会同时切换快捷栏）。
 */
@Mixin(Mouse.class)
public class MouseMixin {

	@Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
	private void nbw$raftZoom(long window, double horizontal, double vertical, CallbackInfo ci) {
		if (RaftCameraClient.onScroll(vertical)) {
			ci.cancel();
		}
	}
}
