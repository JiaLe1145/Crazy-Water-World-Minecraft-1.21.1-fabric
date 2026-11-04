package dev.nbw;

import net.fabricmc.api.ClientModInitializer;

public class NBWClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		NBW.LOGGER.info("NB-Water-World client side ready");
	}
}
