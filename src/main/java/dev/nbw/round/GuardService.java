package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.domain.combat.GuardRule;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.sable.SableRafts;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 幸存者卫兵的运行时（01 §7 A6 / 02 §9）：守筏自动射击。
 *
 * <p>判定全部在 {@link GuardRule}（纯函数）；这里只做三件事：找本队的卫兵实体、
 * 找筏心 12 格内最近的敌方玩家、按冷却施加伤害。
 *
 * <p><b>多卫兵会叠加</b>：每个卫兵有独立冷却，2 个卫兵就是 2×6.5 HP/s。
 * 02 §9 的 0.6 名玩家是<b>单名卫兵</b>的锚点；人数堆叠是幸存者运营的收益，
 * 但如果实测过强，先调 {@link GuardRule#DAMAGE_PER_SHOT} 或改成"同队只算一名卫兵"。
 *
 * <p><b>为什么放在幸存者贴回甲板之后</b>：卫兵实体每 tick 被 {@code SurvivorService.followRafts}
 * 重定位，伤害用筏心而非实体位置，时序上不依赖实体位置；但放在后面便于阅读"先站位、后开火"。
 */
public final class GuardService {

	/** 卫兵实体 UUID → 下次可开火的 tick。局内状态，reset 清空。 */
	private static final Map<UUID, Integer> nextShotTick = new LinkedHashMap<>();
	/** 队号 → 本局命中次数（给 /cww guard 与探针取证）。 */
	private static final Map<Integer, Integer> shotsByTeam = new LinkedHashMap<>();

	private static int tick;

	private GuardService() {
	}

	/** PLAY 每 tick：每名卫兵按自己的冷却对最近敌人开火。 */
	public static void onEndTick(MinecraftServer server) {
		tick++;
		ServerWorld world = server.getOverworld();
		for (var team : RoundManager.machine().teams()) {
			int teamIndex = team.index();
			List<UUID> guards = SurvivorService.guards(teamIndex);
			if (guards.isEmpty()) {
				continue;
			}
			UUID raft = RaftSpawner.raftOf(teamIndex);
			if (raft == null) {
				continue;
			}
			double[] center = SableRafts.position(world, raft);
			if (Double.isNaN(center[0])) {
				continue;
			}
			for (UUID guardId : guards) {
				if (tick < nextShotTick.getOrDefault(guardId, 0)) {
					continue;
				}
				Entity entity = world.getEntity(guardId);
				if (!(entity instanceof LivingEntity shooter)) {
					continue;   // 区块没加载/实体已被清：下一 tick 再看
				}
				ServerPlayerEntity target = nearestEnemy(server, teamIndex, center);
				if (target == null) {
					continue;
				}
				nextShotTick.put(guardId, tick + GuardRule.INTERVAL_TICKS);
				target.damage(world.getDamageSources().mobAttack(shooter),
						(float) GuardRule.DAMAGE_PER_SHOT);
				int n = shotsByTeam.merge(teamIndex, 1, Integer::sum);
				if (n == 1 || n % 10 == 0) {
					NBW.LOGGER.info("[guard] 队 {} 卫兵命中 {}（本局第 {} 发，剩余 {} HP）",
							teamIndex, target.getNameForScoreboard(), n, target.getHealth());
				}
			}
		}
	}

	/** 本队筏心 12 格内最近的敌方玩家；没有返回 null。 */
	private static ServerPlayerEntity nearestEnemy(MinecraftServer server, int guardTeam, double[] center) {
		ServerPlayerEntity best = null;
		double bestD2 = Double.MAX_VALUE;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (!p.isAlive() || p.isSpectator()) {
				continue;
			}
			var session = RoundManager.machine().session(p.getUuid());
			if (session == null || session.teamIndex() < 0 || session.teamIndex() == guardTeam) {
				continue;   // 未分队 / 自己人：不误伤（02 §8 friendly fire 默认关闭）
			}
			double dx = p.getX() - center[0];
			double dy = p.getY() - center[1];
			double dz = p.getZ() - center[2];
			if (!GuardRule.inRange(dx, dy, dz)) {
				continue;
			}
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 < bestD2) {
				bestD2 = d2;
				best = p;
			}
		}
		return best;
	}

	/** 一行读数：各队卫兵数 + 本局命中数（给 {@code /cww guard}）。 */
	public static String status() {
		StringBuilder sb = new StringBuilder("[guard] ");
		for (var team : RoundManager.machine().teams()) {
			int i = team.index();
			sb.append(String.format(Locale.ROOT, "队%d 卫兵=%d 命中=%d｜",
					i, SurvivorService.guards(i).size(), shotsByTeam.getOrDefault(i, 0)));
		}
		return sb.toString().trim();
	}

	/** 局后清理：冷却与命中计数都是局内的。 */
	public static void reset() {
		nextShotTick.clear();
		shotsByTeam.clear();
		tick = 0;
	}
}
