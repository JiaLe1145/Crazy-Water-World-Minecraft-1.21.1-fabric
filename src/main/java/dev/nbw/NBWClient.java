package dev.nbw;

import dev.nbw.client.HoldInputClient;
import dev.nbw.client.MoveInputClient;
import dev.nbw.client.StatusHudClient;
import net.fabricmc.api.ClientModInitializer;

public class NBWClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HoldInputClient.register();   // 按住右键期间续包 / 松手补一刀；只在有客户端时存在
		MoveInputClient.register();   // 遥控器移动模式：右键切模式，WASD 开船
		StatusHudClient.register();   // 生存状态 HUD：营养/水分两条（M4-1）
		NBW.LOGGER.info("NB-Water-World client side ready（长按输入 + 遥控器移动 + 生存 HUD 已接）");
	}
}
