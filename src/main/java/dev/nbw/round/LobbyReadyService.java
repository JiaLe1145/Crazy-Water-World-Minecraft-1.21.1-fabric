package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.domain.round.Phase;
import dev.nbw.domain.round.PlayerSession;
import dev.nbw.domain.round.RoundMachine;
import dev.nbw.net.LobbyStatusPayload;
import dev.nbw.net.LobbyTogglePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 大厅自动准备（2026-11-28 用户需求）。
 *
 * <p>规则：
 * <ul>
 *   <li>玩家身体在 lobby.bounds 内 → 默认参加本场（自动入报名列），不需要指令</li>
 *   <li>走出大厅范围 → 自动取消报名（只取消"系统自动加的"，管理员 force/手动 join 的不动）</li>
 *   <li>大厅内按 K → 切换参加/不参加；"不参加"不会被"在大厅里"重新覆盖，直到再按 K</li>
 *   <li>未开局时屏幕顶部显示 参加人数/在线人数 + 自己是否参加</li>
 * </ul>
 *
 * <p>大厅外/大厅内是每 10 tick 同步一次，不追求逐 tick 精确——报名状态不是玩法判定。
 */
public final class LobbyReadyService {

	private static final Set<UUID> optedOut = new HashSet<>();
	/** 系统自动加进报名列的玩家：只有这些会被"离开大厅"自动取消。 */
	private static final Set<UUID> autoAdded = new HashSet<>();

	private LobbyReadyService() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(LobbyTogglePayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			MinecraftServer srv = context.server();
			srv.execute(() -> {
				String msg = toggle(player);
				player.sendMessage(Text.literal("§b[cww] " + msg), true);
				broadcastStatus(srv);
			});
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
			UUID id = handler.getPlayer().getUuid();
			optedOut.remove(id);
			autoAdded.remove(id);
		});
	}

	/** 每 tick 调用；内部每 10 tick 同步一次。 */
	public static void onEndTick(MinecraftServer srv) {
		RoundMachine m = RoundManager.machine();
		if (m == null) {
			return;
		}
		Phase ph = m.phase();
		if (ph == Phase.PLAY || ph == Phase.SETTLE || ph == Phase.RETURN) {
			return;   // 已开局不再自动改报名
		}
		if (srv.getTicks() % 10 != 0) {
			return;
		}
		boolean allows = ph.allowsEnrollment();
		for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
			UUID id = p.getUuid();
			boolean inside = inLobby(p);
			boolean inQueue = isParticipating(m, id);
			if (allows) {
				if (inside && !optedOut.contains(id) && !inQueue) {
					if (m.queue(id, p.getNameForScoreboard()) == null) {
						autoAdded.add(id);
					}
				} else if (!inside && autoAdded.contains(id) && inQueue) {
					m.unqueue(id);
					autoAdded.remove(id);
				}
			}
			sendStatus(srv, m, p);
		}
	}

	/** K 键切换；返回给玩家的一行动作结果。 */
	public static String toggle(ServerPlayerEntity p) {
		RoundMachine m = RoundManager.machine();
		if (m == null) {
			return "回合系统未就绪";
		}
		if (!m.phase().allowsEnrollment()) {
			return "已开局，无法切换参加状态";
		}
		UUID id = p.getUuid();
		if (optedOut.remove(id)) {
			// 之前是"不参加"→ 现在要参加
			autoAdded.remove(id);
			if (inLobby(p)) {
				String err = m.queue(id, p.getNameForScoreboard());
				if (err != null) {
					return err;
				}
				autoAdded.add(id);
			}
			return "已参加本场游戏";
		}
		optedOut.add(id);
		autoAdded.remove(id);
		m.unqueue(id);
		return "已设为不参加（按 K 可重新参加）";
	}

	/** 手动 /cww join 或管理员 force：视为明确参加，清掉"不参加"。 */
	public static void markManualJoin(UUID id) {
		optedOut.remove(id);
		autoAdded.remove(id);
	}

	/** 手动 /cww quit：记为"不参加"，否则会被大厅自动准备立刻加回来。 */
	public static void markManualQuit(UUID id) {
		optedOut.add(id);
		autoAdded.remove(id);
	}

	/** 局后清理：自动加的记录是局内的；"不参加"的选择保留到玩家自己改回来。 */
	public static void onMatchReset() {
		autoAdded.clear();
	}

	public static void hideAll(MinecraftServer srv) {
		if (srv == null) {
			return;
		}
		for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
			if (ServerPlayNetworking.canSend(p, LobbyStatusPayload.ID)) {
				ServerPlayNetworking.send(p, new LobbyStatusPayload(false, 0, 0, false));
			}
		}
	}

	private static void broadcastStatus(MinecraftServer srv) {
		RoundMachine m = RoundManager.machine();
		if (m == null) {
			return;
		}
		for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
			sendStatus(srv, m, p);
		}
	}

	private static void sendStatus(MinecraftServer srv, RoundMachine m, ServerPlayerEntity p) {
		if (!ServerPlayNetworking.canSend(p, LobbyStatusPayload.ID)) {
			return;
		}
		int participants = m.queuedCount();
		int total = srv.getPlayerManager().getPlayerList().size();
		ServerPlayNetworking.send(p, new LobbyStatusPayload(
				true, participants, total, isParticipating(m, p.getUuid())));
	}

	private static boolean isParticipating(RoundMachine m, UUID id) {
		if (m.queue().contains(id)) {
			return true;
		}
		PlayerSession s = m.session(id);
		return s != null && s.teamIndex() >= 0;
	}

	/** 玩家是否站在大厅 bounds 内（世界也要对）。 */
	public static boolean inLobby(ServerPlayerEntity p) {
		ConfigModels.Lobby lobby = RoundManager.lobbyOrNull();
		ServerWorld lobbyWorld = RoundManager.lobbyWorldPublic();
		if (lobby == null || lobby.bounds == null || lobbyWorld == null) {
			return false;
		}
		if (p.getServerWorld() != lobbyWorld) {
			return false;
		}
		ConfigModels.Arena.Bounds b = lobby.bounds;
		double x = p.getX();
		double y = p.getY();
		double z = p.getZ();
		return x >= b.minX && x <= b.maxX
				&& y >= b.minY && y <= b.maxY
				&& z >= b.minZ && z <= b.maxZ;
	}
}
