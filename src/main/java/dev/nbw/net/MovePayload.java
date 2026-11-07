package dev.nbw.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 遥控器移动意图的 C2S 包（问题③）。
 *
 * <p>只带四个布尔：W/S/A/D 当前是否按下。<b>不做任何方向换算</b>——"前进是哪个方向"由服务端
 * 按筏的实时朝向算，客户端连筏朝哪都不用知道。语义刻意做窄到只有按键状态，
 * 客户端说的话（连方向）一句都不算，与 {@link HoldPayload} 同一纪律。
 *
 * <p>yarn 1.21.1 的 codec 写法与 {@link HoldPayload} 相同：{@code PacketCodec.of} 是
 * {@code ValueFirstEncoder}（值在前、buf 在后）。
 */
public record MovePayload(boolean forward, boolean backward, boolean left, boolean right)
		implements CustomPayload {

	public static final Identifier CHANNEL_ID = Identifier.of("nbw", "move");
	public static final Id<MovePayload> ID = new Id<>(CHANNEL_ID);

	public static final PacketCodec<RegistryByteBuf, MovePayload> CODEC =
			PacketCodec.of(MovePayload::write, MovePayload::read);

	private static void write(MovePayload payload, RegistryByteBuf buf) {
		// 四个布尔压成一个字节：低 4 位依次是 forward/backward/left/right
		int bits = (payload.forward() ? 1 : 0)
				| (payload.backward() ? 2 : 0)
				| (payload.left() ? 4 : 0)
				| (payload.right() ? 8 : 0);
		buf.writeByte(bits);
	}

	private static MovePayload read(RegistryByteBuf buf) {
		int bits = buf.readByte();
		return new MovePayload((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
