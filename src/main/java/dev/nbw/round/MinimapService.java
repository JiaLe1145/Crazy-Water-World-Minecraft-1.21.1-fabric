package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.domain.round.MatchTeam;
import dev.nbw.domain.round.PlayerSession;
import dev.nbw.net.MinimapPayload;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.sable.SableRafts;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 小地图数据源（2026-11-28 用户需求）。
 *
 * <p>每 10 tick 给每名参赛者发一份 {@link MinimapPayload}：本队木筏位置、队友位置、
 * 黑海圈心/半径、队伍存活数、以及本局随机分配的颜色。客户端只负责画，
 * 位置与颜色全部以服务端为准（防"客户端改小地图透视"）。
 *
 * <p>颜色分配：开局按 matchIndex 打乱参赛者顺序，再按 HSV 等分色相，
 * 保证同一局内<b>每个玩家颜色不同</b>（最多 20 人也够用）。
 */
public final class MinimapService {

	/** 发图间隔：2 tick = 0.1 秒。木筏视角要求 165Hz 平滑，数据越新越好。 */
	private static final int SEND_INTERVAL = 2;

	/** UUID → 本局颜色（ARGB）。 */
	private static final Map<UUID, Integer> colors = new HashMap<>();

	private MinimapService() {
	}

	/** PLAY 开始（含重启恢复）时调用：重新给本局所有参赛者分配颜色。 */
	public static void begin(MinecraftServer server) {
		colors.clear();
		List<UUID> ids = new ArrayList<>(RoundManager.participantIds());
		// 同一个 matchIndex 用同一个种子：重进/重启恢复时颜色不会乱跳。
		Collections.shuffle(ids, new Random(RoundManager.machine().matchIndex() * 341873128712L + 132897987541L));
		for (int i = 0; i < ids.size(); i++) {
			colors.put(ids.get(i), hueColor(i, ids.size()));
		}
		NBW.LOGGER.info("[minimap] 本局颜色已分配：{} 人", ids.size());
	}

	/** PLAY 每 tick：每 10 tick 发一次。 */
	public static void onEndTick(MinecraftServer server) {
		if (RoundManager.machine() == null || RoundManager.machine().phase() != dev.nbw.domain.round.Phase.PLAY) {
			return;
		}
		if (server.getTicks() % SEND_INTERVAL != 0) {
			return;
		}
		ServerWorld world = server.getOverworld();
		int match = RoundManager.machine().matchIndex();
		float zoneX = ZoneService.centerX();
		float zoneZ = ZoneService.centerZ();
		float zoneR = (float) ZoneService.currentRadius();

		for (UUID id : RoundManager.participantIds()) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
			if (p == null) {
				continue;
			}
			PlayerSession s = RoundManager.machine().session(id);
			if (s == null || s.teamIndex() < 0) {
				continue;
			}
			MatchTeam team = RoundManager.machine().teamAt(s.teamIndex());
			if (team == null) {
				continue;
			}
			int selfColor = colors.computeIfAbsent(id, k -> hueColor(colors.size(), Math.max(4, colors.size() + 1)));

			List<MinimapPayload.Member> mates = new ArrayList<>();
			int alive = 0;
			for (UUID memberId : team.members()) {
				PlayerSession ms = RoundManager.machine().session(memberId);
				if (ms == null) {
					continue;
				}
				boolean memberAlive = ms.isAlive() && !ms.isEliminated();
				if (memberAlive) {
					alive++;
				}
				if (memberId.equals(id) || !memberAlive) {
					continue;
				}
				ServerPlayerEntity mp = server.getPlayerManager().getPlayer(memberId);
				if (mp == null) {
					continue;
				}
				int mc = colors.computeIfAbsent(memberId, k -> hueColor(mates.size() + 1, 8));
				mates.add(new MinimapPayload.Member(mp.getNameForScoreboard(),
						(float) mp.getX(), (float) mp.getZ(), mc));
			}

			// 队伍名单：名字 + 本局颜色 + 存活（含自己，客户端左下角面板用）
			List<MinimapPayload.Roster> roster = new ArrayList<>();
			for (UUID memberId : team.members()) {
				PlayerSession ms = RoundManager.machine().session(memberId);
				if (ms == null) {
					continue;
				}
				int rosterColor = colors.computeIfAbsent(memberId,
						k -> hueColor(roster.size() + 1, Math.max(4, team.size() + 1)));
				roster.add(new MinimapPayload.Roster(ms.name(), rosterColor,
						ms.isAlive() && !ms.isEliminated()));
			}

			boolean hasRaft = false;
			float rx = 0.0f, rz = 0.0f, ry = 0.0f, ryaw = 0.0f;
			float coreX = 0.0f, coreY = 0.0f, coreZ = 0.0f;
			UUID raftId = RaftSpawner.raftOf(s.teamIndex());
			if (raftId != null) {
				double[] pos = SableRafts.position(world, raftId);
				if (!Double.isNaN(pos[0]) && !Double.isNaN(pos[2])) {
					hasRaft = true;
					rx = (float) pos[0];
					ry = (float) pos[1];
					rz = (float) pos[2];
					double[] fwd = SableRafts.forward(world, raftId);
					ryaw = (float) Math.toDegrees(Math.atan2(-fwd[0], fwd[1]));
					// 筏心方块在 plot 局部 (0,1,0)。
					// XZ 用整数（Sable 客户端渲染的方块中心就是局部整数位，不是 +0.5；
					// 之前的 +0.5 会让描边整格偏到方块外面）。
					// Y 用实测甲板面 0.30 + 半格 = 0.80。
					double[] core = SableRafts.localToWorld(world, raftId, 0.0,
							dev.nbw.domain.raft.RaftLayout.DECK_SURFACE_Y + 0.5, 0.0);
					if (!Double.isNaN(core[0])) {
						coreX = (float) core[0];
						coreY = (float) core[1];
						coreZ = (float) core[2];
						if (s.teamIndex() == 0 && server.getTicks() % 200 == 0) {
							NBW.LOGGER.info("[minimap] 队0 筏心世界坐标=({}, {}, {})",
									String.format(java.util.Locale.ROOT, "%.2f", coreX),
									String.format(java.util.Locale.ROOT, "%.2f", coreY),
									String.format(java.util.Locale.ROOT, "%.2f", coreZ));
						}
					}
				}
			}

			// 幸存者（NPC）：统一绿色，与"玩家队友"分开
			List<MinimapPayload.Survivor> survivors = new ArrayList<>();
			for (UUID sid : SurvivorService.survivorsOf(s.teamIndex())) {
				Entity e = world.getEntity(sid);
				if (e != null && e.isAlive()) {
					survivors.add(new MinimapPayload.Survivor((float) e.getX(), (float) e.getZ()));
				}
			}

			ServerPlayNetworking.send(p, new MinimapPayload(
					match, selfColor, s.teamIndex(), team.name(), alive, team.size(),
					zoneX, zoneZ, zoneR, hasRaft, rx, rz, ry, ryaw, coreX, coreY, coreZ,
					mates, survivors, roster));
		}
	}

	/** 局后清理：颜色是局内的。 */
	public static void reset() {
		colors.clear();
	}

	/** 本局已分配的颜色（调试/测试用）。 */
	public static Map<UUID, Integer> colorsView() {
		return Map.copyOf(colors);
	}

	/**
	 * HSV 等分色相 → 亮色 ARGB；i/n 决定色相，保证同局不撞色。
	 *
	 * <p>绿色段（约 75°–165°）整体跳过：绿色固定留给幸存者 NPC，
	 * 玩家颜色不能和幸存者混在一起。
	 */
	private static int hueColor(int i, int n) {
		int total = Math.max(1, n);
		float greenStart = 75.0f / 360.0f;
		float greenEnd = 165.0f / 360.0f;
		float allowed = 1.0f - (greenEnd - greenStart);
		float t = (i % total) / (float) total * allowed;
		float hue = t < greenStart ? t : t + (greenEnd - greenStart);
		return hsvToRgb(hue, 0.78f, 1.0f);
	}

	private static int hsvToRgb(float h, float s, float v) {
		float hh = (h % 1.0f) * 6.0f;
		int sector = (int) Math.floor(hh);
		float f = hh - sector;
		float p = v * (1 - s);
		float q = v * (1 - s * f);
		float t = v * (1 - s * (1 - f));
		float r, g, b;
		switch (sector % 6) {
			case 0 -> { r = v; g = t; b = p; }
			case 1 -> { r = q; g = v; b = p; }
			case 2 -> { r = p; g = v; b = t; }
			case 3 -> { r = p; g = q; b = v; }
			case 4 -> { r = t; g = p; b = v; }
			default -> { r = v; g = p; b = q; }
		}
		return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
	}
}
