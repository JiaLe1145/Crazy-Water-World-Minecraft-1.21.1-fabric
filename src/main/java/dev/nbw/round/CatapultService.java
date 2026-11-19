package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.raft.RaftCoreService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 投石机（09 §2.2 设施武器）：40 格外的攻城器械，只打敌方筏心。
 *
 * <p><b>规格</b>（09 §2.2）：12♥（=24 HP）/发、40 格、**0 电**、5 秒一发。
 * 定位是"拆家不是杀人"：只打筏心，不做玩家命中（"对玩家伤害 −60%"因此不涉及）。
 *
 * <p><b>为什么不吃反偷家 ×0.25</b>：见 {@link RaftCoreService#breakCoreSiege} 的注释——
 * 反偷家防的是"玩家登筏偷袭"，投石机是 40 格外的攻城器械，幸存者挡不住它。
 *
 * <p>与弩炮的分工：弩炮打人（32 格、吃电），投石机拆家（40 格、不吃电）。两套都用
 * "放置时登记归属、每 tick 扫炮位"的服务端驱动，不依赖 sub-level 的 BE tick。
 */
public final class CatapultService {

	/** 射程（格，3D，从炮位到敌方筏心的距离）。 */
	public static final double RANGE = 40.0;
	/** 每发对筏心的伤害（HP）；12♥ = 24 HP。 */
	public static final double DAMAGE = 24.0;
	/** 开火间隔（tick）：5 秒。 */
	public static final int INTERVAL_TICKS = 100;

	/** 炮位 → 归属队伍。 */
	private static final Map<BlockPos, Integer> catapults = new LinkedHashMap<>();
	/** 炮位 → 上次开火 tick。 */
	private static final Map<BlockPos, Integer> lastFire = new LinkedHashMap<>();

	private CatapultService() {
	}

	public static void register(BlockPos pos, int team) {
		if (team < 0) {
			return;
		}
		catapults.put(pos.toImmutable(), team);
	}

	public static void unregister(BlockPos pos) {
		catapults.remove(pos);
		lastFire.remove(pos);
	}

	/** PLAY 每 tick：遍历炮位，对 40 格内最近的敌方筏心开火。 */
	public static void onEndTick(MinecraftServer srv) {
		if (catapults.isEmpty()) {
			return;
		}
		ServerWorld world = srv.getOverworld();
		int now = srv.getTicks();
		for (Map.Entry<BlockPos, Integer> e : catapults.entrySet()) {
			BlockPos pos = e.getKey();
			int team = e.getValue();
			Integer last = lastFire.get(pos);
			if (last != null && now - last < INTERVAL_TICKS) {
				continue;
			}
			lastFire.put(pos, now);   // 先记节拍：否则"无目标"时每 tick 都会走进来刷日志
			int target = nearestEnemyCore(world, pos, team);
			if (target < 0) {
				// 调试：告诉运维"为什么没打"——是没有敌方筏心，还是超距
				var mine = RaftCoreService.coreWorldPos(world, team == 0 ? 1 : 0);
				double d = mine == null ? -1.0 : Math.sqrt(
						Math.pow(mine.x - (pos.getX() + 0.5), 2)
						+ Math.pow(mine.y - (pos.getY() + 0.5), 2)
						+ Math.pow(mine.z - (pos.getZ() + 0.5), 2));
				NBW.LOGGER.info("[catapult] {} 无目标：敌方筏心={} 距离={}（射程 {}）",
						pos, mine == null ? "取不到" : mine.toString(), String.format(Locale.ROOT, "%.1f", d), RANGE);
				continue;
			}
			double dealt = RaftCoreService.breakCoreSiege(target, DAMAGE);   // 0 电
			NBW.LOGGER.info("[catapult] {} 命中队 {} 筏心 -{} HP", pos, target, Math.round(dealt));
		}
	}

	/** 找 40 格内最近的敌方筏心所属队伍；没有返回 -1。 */
	private static int nearestEnemyCore(net.minecraft.server.world.ServerWorld world, BlockPos pos, int team) {
		int best = -1;
		double bestD = RANGE;
		double cx = pos.getX() + 0.5;
		double cy = pos.getY() + 0.5;
		double cz = pos.getZ() + 0.5;
		for (var t : RoundManager.machine().teams()) {
			int other = t.index();
			if (other == team) {
				continue;
			}
			// 必须用世界坐标：coreByPos 里存的是 plot 局部坐标（见 RaftCoreService.coreWorldPos 注释）
			var core = RaftCoreService.coreWorldPos(world, other);
			if (core == null) {
				continue;
			}
			double dx = core.x - cx;
			double dy = core.y - cy;
			double dz = core.z - cz;
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d < bestD) {
				bestD = d;
				best = other;
			}
		}
		return best;
	}

	/** 一行读数：给 {@code /cww catapult}。 */
	public static String status() {
		if (catapults.isEmpty()) {
			return "[catapult] 本局没有放置投石机";
		}
		StringBuilder sb = new StringBuilder("[catapult] ");
		for (Map.Entry<BlockPos, Integer> e : catapults.entrySet()) {
			sb.append(String.format(Locale.ROOT, "%s=队%d ", e.getKey(), e.getValue()));
		}
		return sb.toString().trim();
	}

	/** 局后清理。 */
	public static void reset() {
		catapults.clear();
		lastFire.clear();
	}
}
