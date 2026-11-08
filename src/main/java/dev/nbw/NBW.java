package dev.nbw;

import dev.nbw.combat.WeaponService;
import dev.nbw.construct.BuildService;
import dev.nbw.cmd.NbwCommands;
import dev.nbw.content.NbwContent;
import dev.nbw.content.NbwWeapons;
import dev.nbw.net.NbwNetworking;
import dev.nbw.rescue.RescueService;
import dev.nbw.survival.SurvivalService;
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
		NbwWeapons.register();      // 首发 5 件近战武器（09 §2.1）：物品注册要在武器规则挂载之前
		NbwNetworking.register();   // 长按输入：codec 必须先于接收器注册，否则服务端解不出包
		RoundManager.register();
		BuildService.register();    // 建造规则（01 §4.2）：方块事件必须在阶段系统起来之后挂
		RescueService.register();     // 落水与扒边（01 §8）：伤害打断要挂在实体事件上
		WeaponService.register();     // PVP 近战（09 §4）：攻击事件挂在阶段系统之后，只在 PLAY 生效
		SurvivalService.register();   // 营养/水分（M4-1）：挂伤害事件记"最近挨打"
		NbwCommands.register();
		LOGGER.info("NB-Water-World {} initialized (mc 1.21.1 / loader 0.19.3 / Sable 2.0.6)", MOD_VERSION);
	}
}
