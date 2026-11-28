package dev.nbw;

import dev.nbw.client.FloatingCrateRenderer;
import dev.nbw.client.HoldInputClient;
import dev.nbw.client.LobbyHudClient;
import dev.nbw.client.MinimapClient;
import dev.nbw.client.RaftCameraClient;
import dev.nbw.client.RaftCoreOutlineClient;
import dev.nbw.client.MoveInputClient;
import dev.nbw.client.SpearProjectileRenderer;
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
		MinimapClient.register();     // 小地图 + M 键大地图 + 标点（2026-11-28）
		LobbyHudClient.register();    // 未开局：顶部参加人数 + K 键切换（2026-11-28）
		RaftCoreOutlineClient.register();   // 手持遥控器：本队筏心方块边缘发光
		RaftCameraClient.register();        // 开筏：第三人称木筏视角 + 滚轮缩放
		// 海面补给箱：实体本身只有位置/摇晃数据，外观复用 barrel 方块模型
		EntityRendererRegistry.register(NbwEntities.FLOATING_CRATE, FloatingCrateRenderer::new);
		// 投出去的石矛：用物品模型（原版石矛贴图）按飞行方向摆正，画两片十字避免侧视消失
		EntityRendererRegistry.register(NbwEntities.STONE_SPEAR, SpearProjectileRenderer::new);
		NBW.LOGGER.info("NB-Water-World client side ready（长按输入 + 遥控器移动 + 生存 HUD + 小地图 + 漂浮补给箱 + 投矛已接）");
	}
}
