package dev.nbw;

import dev.nbw.client.FloatingCrateRenderer;
import dev.nbw.client.HoldInputClient;
import dev.nbw.client.MoveInputClient;
import dev.nbw.client.StatusHudClient;
import dev.nbw.content.NbwEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class NBWClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HoldInputClient.register();   // 按住右键期间续包 / 松手补一刀；只在有客户端时存在
		MoveInputClient.register();   // 遥控器移动模式：右键切模式，WASD 开船
		StatusHudClient.register();   // 生存状态 HUD：营养/水分两条（M4-1）
		// 海面补给箱：实体本身只有位置/摇晃数据，外观复用 barrel 方块模型
		EntityRendererRegistry.register(NbwEntities.FLOATING_CRATE, FloatingCrateRenderer::new);
		NBW.LOGGER.info("NB-Water-World client side ready（长按输入 + 遥控器移动 + 生存 HUD + 漂浮补给箱已接）");
	}
}
