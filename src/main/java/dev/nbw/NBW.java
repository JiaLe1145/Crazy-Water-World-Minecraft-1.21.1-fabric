package dev.nbw;

import dev.nbw.cmd.NbwCommands;
import dev.nbw.content.NbwContent;
import dev.nbw.net.NbwNetworking;
import dev.nbw.round.RoundManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NBW implements ModInitializer {
	public static final String MOD_ID = "nbw";
	public static final String MOD_VERSION = "0.1.0";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		NbwContent.register();
		NbwNetworking.register();   // 长按输入：codec 必须先于接收器注册，否则服务端解不出包
		RoundManager.register();
		NbwCommands.register();
		LOGGER.info("NB-Water-World {} initialized (mc 1.21.1 / loader 0.19.3 / Sable 2.0.6)", MOD_VERSION);
	}
}
