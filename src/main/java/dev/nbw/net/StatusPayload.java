package dev.nbw.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 生存状态的 S2C 包（M4-1）：把营养/水分两个 0..100 的值推给客户端 HUD。
 *
 * <p>与 C2S 那两个包相反，这个方向是<b>服务端权威</b>：客户端只拿它画条，
 * 不参与任何结算（扣多少、掉多少血仍由 {@code survival/SurvivalService} 定）。
 */
public record StatusPayload(float nutrition, float hydration) implements CustomPayload {

	public static final Identifier CHANNEL_ID = Identifier.of("nbw", "status");
	public static final Id<StatusPayload> ID = new Id<>(CHANNEL_ID);

	public static final PacketCodec<RegistryByteBuf, StatusPayload> CODEC =
			PacketCodec.of(StatusPayload::write, StatusPayload::read);

	private static void write(StatusPayload payload, RegistryByteBuf buf) {
		buf.writeFloat(payload.nutrition());
		buf.writeFloat(payload.hydration());
	}

	private static StatusPayload read(RegistryByteBuf buf) {
		return new StatusPayload(buf.readFloat(), buf.readFloat());
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
