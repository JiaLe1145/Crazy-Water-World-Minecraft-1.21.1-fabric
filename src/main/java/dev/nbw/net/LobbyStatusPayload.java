package dev.nbw.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C：未开局时屏幕顶部的人数/参加状态（2026-11-28 用户需求）。
 *
 * @param active       false = 已开局，客户端应立即收起面板
 * @param participants 已参加本场的人数
 * @param total        在线总人数
 * @param joined       自己是否已参加
 */
public record LobbyStatusPayload(boolean active, int participants, int total, boolean joined)
		implements CustomPayload {

	public static final Identifier CHANNEL_ID = Identifier.of("nbw", "lobby_status");
	public static final Id<LobbyStatusPayload> ID = new Id<>(CHANNEL_ID);

	public static final PacketCodec<RegistryByteBuf, LobbyStatusPayload> CODEC =
			PacketCodec.of(LobbyStatusPayload::write, LobbyStatusPayload::read);

	private static void write(LobbyStatusPayload p, RegistryByteBuf buf) {
		buf.writeBoolean(p.active());
		buf.writeInt(p.participants());
		buf.writeInt(p.total());
		buf.writeBoolean(p.joined());
	}

	private static LobbyStatusPayload read(RegistryByteBuf buf) {
		return new LobbyStatusPayload(buf.readBoolean(), buf.readInt(), buf.readInt(), buf.readBoolean());
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
