package dev.nbw;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NBW implements ModInitializer {
	public static final String MOD_ID = "nbw";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// M0-1：只验证依赖装配与起服，不写玩法。
		LOGGER.info("NB-Water-World {} initialized (mc 1.21.1 / loader 0.19.3 / Sable 2.0.6)", "0.0.1");
	}
}
