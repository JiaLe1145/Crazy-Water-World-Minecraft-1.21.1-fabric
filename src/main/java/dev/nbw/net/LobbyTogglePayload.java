package dev.nbw.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S：大厅里按 K 切换"参加/不参加本场游戏"（2026-11-28 用户需求）。
 * 包内没有字段——意图只有"切换一次"，服务端自己维护 optedOut 状态。
 */
public record LobbyTogglePayload() implements CustomPayload {

	public static final Identifier CHANNEL_ID = Identifier.of("nbw", "lobby_toggle");
	public static final Id<LobbyTogglePayload> ID = new Id<>(CHANNEL_ID);

	public static final PacketCodec<RegistryByteBuf, LobbyTogglePayload> CODEC =
			PacketCodec.of((payload, buf) -> {
			}, buf -> new LobbyTogglePayload());

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
