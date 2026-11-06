package dev.nbw;

import dev.nbw.client.HoldInputClient;
import net.fabricmc.api.ClientModInitializer;

public class NBWClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HoldInputClient.register();   // 按住右键期间续包 / 松手补一刀；只在有客户端时存在
		NBW.LOGGER.info("NB-Water-World client side ready（长按输入已接）");
	}
}
