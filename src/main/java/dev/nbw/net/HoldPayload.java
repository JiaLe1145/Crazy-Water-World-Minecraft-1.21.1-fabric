package dev.nbw.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * "按住右键"周期的 C2S 包（M2-4 客户端侧、M2-5 桨台共用同一条通道）。
 *
 * <p><b>为什么必须有这条通道。</b>A2-v2 的设计是"持续右键 8 秒恢复筏心，期间受伤即打断"，
 * 桨台同样要求持续输入。但服务端这边只有两种输入源：单次右键（{@code onUse}，只开
 * {@code KEEPALIVE_TICKS} 的窗）和 admins 的模拟命令。真人长按一直没接上，
 * 等于<b>这条机制从来没被真实输入验证过</b>。
 *
 * <p>语义刻意做窄：包里<b>只有目标坐标和一个 active 标志</b>。
 * 进度、时长、是否成功，全部由服务端自己数 tick；客户端说什么都当没说。
 *
 * <p>yarn 1.21.1 的 codec 写法有两个坑，都是编译器教的：
 * 没有 {@code PayloadTypeRegistry}（要注册就注册，见 {@link NbwNetworking}），
 * 而 {@code PacketCodec.of} 的编码器是 {@code ValueFirstEncoder}——<b>值在前、buf 在后</b>，
 * 和 Mojang 的 {@code StreamCodec} 参数顺序相反。写成 {@code (buf, value)} 会直接编译失败。
 */
public record HoldPayload(BlockPos pos, boolean active) implements CustomPayload {

	public static final Identifier CHANNEL_ID = Identifier.of("nbw", "hold");
	public static final Id<HoldPayload> ID = new Id<>(CHANNEL_ID);

	public static final PacketCodec<RegistryByteBuf, HoldPayload> CODEC =
			PacketCodec.of(HoldPayload::write, HoldPayload::read);

	/** 坐标压成原版 long，active 一个字节：不用 BlockPos 的 NBT codec，走包时那是绕远路。 */
	private static void write(HoldPayload payload, RegistryByteBuf buf) {
		buf.writeLong(payload.pos().asLong());
		buf.writeBoolean(payload.active());
	}

	private static HoldPayload read(RegistryByteBuf buf) {
		return new HoldPayload(BlockPos.fromLong(buf.readLong()), buf.readBoolean());
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
