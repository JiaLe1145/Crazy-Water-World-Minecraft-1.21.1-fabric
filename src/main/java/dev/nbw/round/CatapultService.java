package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.content.CatapultBlock;
import dev.nbw.content.NbwContent;
import dev.nbw.raft.RaftCoreService;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.sable.SableRafts;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
	/** 落点范围半径（格）：09 §2.2 只写了「12♥ 范围」，半径取 4 格（约 9×9 的甲板尺度）。 */
	public static final double SPLASH_RADIUS = 4.0;
	/** 对玩家只给 40%（即 −60%，09 §2.2）：拆家器械不杀人。 */
	public static final double PLAYER_DAMAGE_RATIO = 0.4;

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
				double[] center = facilityWorldCenter(world, pos, team);
				double d = mine == null || center == null ? -1.0 : Math.sqrt(
						Math.pow(mine.x - center[0], 2)
						+ Math.pow(mine.y - center[1], 2)
						+ Math.pow(mine.z - center[2], 2));
				NBW.LOGGER.debug("[catapult] {} 无目标：敌方筏心={} 距离={}（射程 {}）",
						pos, mine == null ? "取不到" : mine.toString(), String.format(Locale.ROOT, "%.1f", d), RANGE);
				continue;
			}
			double dealt = RaftCoreService.breakCoreSiege(target, DAMAGE);   // 0 电
			int hit = splashPlayers(world, target, team);
			NBW.LOGGER.info("[catapult] {} 命中队 {} 筏心 -{} HP，波及 {} 名玩家", pos, target,
					Math.round(dealt), hit);
		}
	}

	/**
	 * 落点范围伤害（09 §2.2「12♥ 范围；对玩家伤害 −60%」）：以被砸筏心为中心
	 * {@link #SPLASH_RADIUS} 格内的敌方玩家吃 {@code DAMAGE × PLAYER_DAMAGE_RATIO}。
	 *
	 * <p>为什么只对<b>敌方</b>生效：投石机是拆家器械，不能拿来在自家筏上炸队友；
	 * 对玩家只给 40% 是为了守住"拆家不杀人"的定位（09 §2.2 约束列）。
	 */
	private static int splashPlayers(ServerWorld world, int victimTeam, int attackerTeam) {
		var center = RaftCoreService.coreWorldPos(world, victimTeam);
		if (center == null) {
			return 0;
		}
		int hit = 0;
		double dmg = DAMAGE * PLAYER_DAMAGE_RATIO;
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.isSpectator() || !p.isAlive()) {
				continue;
			}
			var s = RoundManager.machine().session(p.getUuid());
			int t = s == null ? -1 : s.teamIndex();
			if (t < 0 || t == attackerTeam) {
				continue;   // 不炸自己队
			}
			if (p.squaredDistanceTo(center.x, center.y, center.z) > SPLASH_RADIUS * SPLASH_RADIUS) {
				continue;
			}
			p.damage(world.getDamageSources().magic(), (float) dmg);
			hit++;
		}
		return hit;
	}

	/** 找 40 格内最近的敌方筏心所属队伍；没有返回 -1。 */
	private static int nearestEnemyCore(net.minecraft.server.world.ServerWorld world, BlockPos pos, int team) {
		int best = -1;
		double bestD = RANGE;
		double[] center = facilityWorldCenter(world, pos, team);
		if (center == null) {
			return -1;
		}
		double cx = center[0];
		double cy = center[1];
		double cz = center[2];
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

	/** 炮位坐标归一：玩家真实放置的是 Sable 存储坐标，dev 命令放置的是世界坐标。 */
	private static double[] facilityWorldCenter(ServerWorld world, BlockPos pos, int team) {
		if (Math.abs(pos.getX()) > 1_000_000 || Math.abs(pos.getZ()) > 1_000_000) {
			UUID raft = RaftSpawner.raftOf(team);
			if (raft == null) {
				return null;
			}
			double[] w = SableRafts.storageToWorld(world, raft, pos);
			return Double.isNaN(w[0]) ? null : w;
		}
		return new double[]{pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5};
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

	/** 重启恢复：从各筏 plot 重建投石机炮位（只认主方块）。 */
	public static void rebuildFromWorld(MinecraftServer srv) {
		catapults.clear();
		lastFire.clear();
		ServerWorld world = srv.getOverworld();
		for (Map.Entry<Integer, UUID> raft : RaftSpawner.current().entrySet()) {
			for (SableRafts.PlotBlock b : SableRafts.plotBlocks(world, raft.getValue(), 12, -1, 8)) {
				if (b.state().isOf(NbwContent.catapult)
						&& b.state().get(CatapultBlock.PART) == CatapultBlock.Part.MAIN) {
					BlockPos storage = SableRafts.localToStorage(world, raft.getValue(), b.lx(), b.ly(), b.lz());
					if (storage != null) {
						catapults.put(storage, raft.getKey());
					}
				}
			}
		}
		NBW.LOGGER.info("[catapult] 重启恢复炮位 {} 门", catapults.size());
	}
}
