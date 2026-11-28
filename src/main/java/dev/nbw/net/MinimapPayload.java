package dev.nbw.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 小地图 S2C（2026-11-28 用户需求）：服务端把"画地图需要的世界事实"推给客户端。
 *
 * <p>只推事实，不推判定：客户端拿它画点，不参与任何结算。
 * 每 10 tick 发一次（0.5 秒），掉线/对局结束自然停发。
 *
 * <p>字段：
 * <ul>
 *   <li>{@code matchIndex} —— 客户端用它判断"换局了没有"（换局清掉标点）</li>
 *   <li>{@code selfColor} —— 本局随机分配给"你"的颜色（每个玩家不同）</li>
 *   <li>{@code teamIndex / teamName / teamAlive / teamSize} —— 左上角队伍信息</li>
 *   <li>{@code zoneX/zoneZ/zoneRadius} —— 黑海圈心与当前有效半径</li>
 *   <li>{@code raftX/raftZ/hasRaft} —— 本队木筏位置</li>
 *   <li>{@code teammates} —— 本队其他在线存活成员（名字、位置、颜色）</li>
 * </ul>
 */
public record MinimapPayload(
		int matchIndex,
		int selfColor,
		int teamIndex,
		String teamName,
		int teamAlive,
		int teamSize,
		float zoneX,
		float zoneZ,
		float zoneRadius,
		boolean hasRaft,
		float raftX,
		float raftZ,
		float raftY,
		float raftYaw,
		float coreX,
		float coreY,
		float coreZ,
		List<Member> teammates,
		List<Survivor> survivors,
		List<Roster> roster) implements CustomPayload {

	public record Member(String name, float x, float z, int color) {
	}

	/** 幸存者（NPC）在地图上的位置；客户端统一画绿色。 */
	public record Survivor(float x, float z) {
	}

	/** 队伍名单：名字 + 本局颜色 + 是否存活（含自己；用于左下角队伍面板）。 */
	public record Roster(String name, int color, boolean alive) {
	}

	public static final Identifier CHANNEL_ID = Identifier.of("nbw", "minimap");
	public static final Id<MinimapPayload> ID = new Id<>(CHANNEL_ID);

	public static final PacketCodec<RegistryByteBuf, MinimapPayload> CODEC =
			PacketCodec.of(MinimapPayload::write, MinimapPayload::read);

	private static void write(MinimapPayload p, RegistryByteBuf buf) {
		buf.writeInt(p.matchIndex());
		buf.writeInt(p.selfColor());
		buf.writeInt(p.teamIndex());
		buf.writeString(p.teamName());
		buf.writeInt(p.teamAlive());
		buf.writeInt(p.teamSize());
		buf.writeFloat(p.zoneX());
		buf.writeFloat(p.zoneZ());
		buf.writeFloat(p.zoneRadius());
		buf.writeBoolean(p.hasRaft());
		buf.writeFloat(p.raftX());
		buf.writeFloat(p.raftZ());
		buf.writeFloat(p.raftY());
		buf.writeFloat(p.raftYaw());
		buf.writeFloat(p.coreX());
		buf.writeFloat(p.coreY());
		buf.writeFloat(p.coreZ());
		buf.writeInt(p.teammates().size());
		for (Member m : p.teammates()) {
			buf.writeString(m.name());
			buf.writeFloat(m.x());
			buf.writeFloat(m.z());
			buf.writeInt(m.color());
		}
		buf.writeInt(p.survivors().size());
		for (Survivor s : p.survivors()) {
			buf.writeFloat(s.x());
			buf.writeFloat(s.z());
		}
		buf.writeInt(p.roster().size());
		for (Roster r : p.roster()) {
			buf.writeString(r.name());
			buf.writeInt(r.color());
			buf.writeBoolean(r.alive());
		}
	}

	private static MinimapPayload read(RegistryByteBuf buf) {
		int match = buf.readInt();
		int selfColor = buf.readInt();
		int teamIndex = buf.readInt();
		String teamName = buf.readString();
		int teamAlive = buf.readInt();
		int teamSize = buf.readInt();
		float zx = buf.readFloat();
		float zz = buf.readFloat();
		float zr = buf.readFloat();
		boolean hasRaft = buf.readBoolean();
		float rx = buf.readFloat();
		float rz = buf.readFloat();
		float ry = buf.readFloat();
		float ryaw = buf.readFloat();
		float cx = buf.readFloat();
		float cy = buf.readFloat();
		float cz = buf.readFloat();
		int n = buf.readInt();
		List<Member> list = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			list.add(new Member(buf.readString(), buf.readFloat(), buf.readFloat(), buf.readInt()));
		}
		int sn = buf.readInt();
		List<Survivor> survivors = new ArrayList<>(sn);
		for (int i = 0; i < sn; i++) {
			survivors.add(new Survivor(buf.readFloat(), buf.readFloat()));
		}
		int rn = buf.readInt();
		List<Roster> roster = new ArrayList<>(rn);
		for (int i = 0; i < rn; i++) {
			roster.add(new Roster(buf.readString(), buf.readInt(), buf.readBoolean()));
		}
		return new MinimapPayload(match, selfColor, teamIndex, teamName, teamAlive, teamSize,
				zx, zz, zr, hasRaft, rx, rz, ry, ryaw, cx, cy, cz, list, survivors, roster);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
