package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.raft.RaftCoreService;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.sable.SableRafts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 撞角（09 §2.2 设施武器）：装在本队筏上，<b>筏在平移时</b>才会对贴上来的敌人造成撞击伤害。
 *
 * <p><b>规格</b>（09 §2.2）：接触判定、2 电、筏体平移时撞击伤害。09 §5 / 05 R3 的"相对速度"
 * 公式在 {@code PROPOSAL-P01} 里仍是<b>提案</b>，没批准；本轮按最小口径落：
 * <ul>
 *   <li>筏速 &gt; {@link #MIN_SPEED} 才算"在撞"（停着不产生伤害，防止停船白嫖）；</li>
 *   <li>敌方玩家落在本队筏心 {@link #RANGE} 格内即命中；</li>
 *   <li>每 {@link #INTERVAL_TICKS} tick 最多一次，每次 {@link #DAMAGE} HP（8♥），耗 {@link #POWER_COST} 电；</li>
 *   <li>没电就不撞（硬性耗电，与修理台/弩炮同档）。</li>
 * </ul>
 *
 * <p><b>明确未做</b>：按相对速度缩放伤害（提案未批）、撞敌方筏体的物理反冲（需要 Sable 碰撞回调）。
 */
public final class RamService {

	/** 命中半径（格，从本队筏心量；水平距离）。 */
	public static final double RANGE = 5.0;
	/** 垂直窗口（格）：筏心上下这么多以内才算"贴着船壳"，防止站在岸上/天上远程白嫖。 */
	public static final double VERTICAL_WINDOW = 6.0;
	/** 每次撞击伤害（HP）；5♥ = 10 HP（2026-11-21 用户裁决：定位是打断/骚扰，不是杀伤主力）。 */
	public static final double DAMAGE = 10.0;
	/** 撞击间隔（tick）：1 秒。 */
	public static final int INTERVAL_TICKS = 20;
	/** 每次撞击耗电。 */
	public static final double POWER_COST = 2.0;
	/** 低于这个筏速视为"没在移动"，不产生撞击伤害。 */
	public static final double MIN_SPEED = 0.05;

	/** 撞角位置 → 归属队伍。 */
	private static final Map<BlockPos, Integer> rams = new LinkedHashMap<>();
	/** 撞角位置 → 上次撞击 tick。 */
	private static final Map<BlockPos, Integer> lastHit = new LinkedHashMap<>();

	private RamService() {
	}

	public static void register(BlockPos pos, int team) {
		if (team < 0) {
			return;
		}
		rams.put(pos.toImmutable(), team);
	}

	public static void unregister(BlockPos pos) {
		rams.remove(pos);
		lastHit.remove(pos);
	}

	/** PLAY 每 tick：筏在动 + 敌人在筏心附近 → 撞击。 */
	public static void onEndTick(MinecraftServer srv) {
		if (rams.isEmpty()) {
			return;
		}
		ServerWorld world = srv.getOverworld();
		int now = srv.getTicks();
		for (Map.Entry<BlockPos, Integer> e : rams.entrySet()) {
			BlockPos pos = e.getKey();
			int team = e.getValue();
			Integer last = lastHit.get(pos);
			if (last != null && now - last < INTERVAL_TICKS) {
				continue;
			}
			lastHit.put(pos, now);   // 先记节拍，避免"没命中"时每 tick 刷日志
			var raft = RaftSpawner.raftOf(team);
			if (raft == null) {
				NBW.LOGGER.debug("[ram] 队 {} 没有筏，跳过", team);
				continue;
			}
			double[] v = SableRafts.linearVelocity(world, raft);
			if (v == null) {
				NBW.LOGGER.debug("[ram] 队 {} 取不到位速", team);
				continue;
			}
			double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
			if (speed < MIN_SPEED) {
				NBW.LOGGER.debug("[ram] 队 {} 筏速 {} < {}，停船不撞", team,
						String.format(Locale.ROOT, "%.4f", speed), MIN_SPEED);
				continue;   // 停船不撞
			}
			var core = RaftCoreService.coreWorldPos(world, team);
			if (core == null) {
				NBW.LOGGER.debug("[ram] 队 {} 取不到筏心世界坐标", team);
				continue;
			}
			ServerPlayerEntity victim = nearestEnemy(world, core, team);
			if (victim == null) {
				NBW.LOGGER.debug("[ram] 队 {} 筏速 {}，但 {} 格内无敌人（筏心 {}）", team,
						String.format(Locale.ROOT, "%.4f", speed), RANGE, core);
				continue;
			}
			if (!PowerService.tryConsume(team, POWER_COST)) {
				NBW.LOGGER.debug("[ram] 队 {} 电量不足 {}", team, POWER_COST);
				continue;   // 没电撞不动
			}
			victim.damage(world.getDamageSources().magic(), (float) DAMAGE);
			NBW.LOGGER.info("[ram] 队 {} 撞角命中 {}（筏速 {}，-{} HP）",
					team, victim.getNameForScoreboard(), String.format(Locale.ROOT, "%.3f", speed), Math.round(DAMAGE));
		}
	}

	/**
	 * 找筏心水平 {@link #RANGE} 格内、且垂直差在 {@link #VERTICAL_WINDOW} 以内的最近敌方玩家。
	 *
	 * <p>用水平距离而不是 3D：筏心在甲板高度（y≈64）、玩家可能在水里（y≈55），
	 * 垂直差 9 格会把"贴着船壳的敌人"判成够不着（2026-11-19 实测踩到）。
	 */
	private static ServerPlayerEntity nearestEnemy(ServerWorld world, net.minecraft.util.math.Vec3d core, int team) {
		ServerPlayerEntity best = null;
		double bestD = RANGE * RANGE;
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.isSpectator() || !p.isAlive()) {
				continue;
			}
			var session = RoundManager.machine().session(p.getUuid());
			int t = session == null ? -1 : session.teamIndex();
			if (t < 0 || t == team) {
				continue;
			}
			double dy = p.getY() - core.y;
			if (Math.abs(dy) > VERTICAL_WINDOW) {
				continue;   // 垂直太远（站在岸上/天上）不算撞到
			}
			double dx = p.getX() - core.x;
			double dz = p.getZ() - core.z;
			double d = dx * dx + dz * dz;
			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	/** 一行读数：给 {@code /cww ram}。 */
	public static String status() {
		if (rams.isEmpty()) {
			return "[ram] 本局没有放置撞角";
		}
		StringBuilder sb = new StringBuilder("[ram] ");
		for (Map.Entry<BlockPos, Integer> e : rams.entrySet()) {
			sb.append(String.format(Locale.ROOT, "%s=队%d ", e.getKey(), e.getValue()));
		}
		return sb.toString().trim();
	}

	/** 局后清理。 */
	public static void reset() {
		rams.clear();
		lastHit.clear();
	}
}
