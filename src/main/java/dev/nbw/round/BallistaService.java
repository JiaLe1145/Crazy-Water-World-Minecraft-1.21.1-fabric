package dev.nbw.round;

import dev.nbw.NBW;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 弩炮（09 §2.2 设施武器）：放在筏上的定点防御，自动打 32 格内最近的敌方玩家。
 *
 * <p><b>规格</b>（09 §2.2）：7♥/发（=14 HP）、射程 32 格、每发 1 电、风暴中 −50%。
 *
 * <p><b>为什么不用方块实体 tick</b>：筏心那套 {@code BlockEntitySubLevelActor} 是给
 * "必须被 Sable 收进 actor 表" 的 BE 用的（见 {@code RaftCoreBlockEntity} 注释）；
 * 弩炮只要"每 tick 扫一遍已放置的炮位"，用服务端 tick 驱动的表更简单，也不依赖 sub-level。
 * 炮位在 {@code BallistaBlock.onPlaced} 登记、{@code onStateReplaced} 注销、局后 {@link #reset()} 清空。
 *
 * <p><b>本轮只做"自动炮台"</b>：不做玩家手动瞄准（09 的"转向慢 1.5s"属操作手感，
 * 需要客户端输入通道，留给后续；自动炮台已经能验证"设施武器 + 耗电 + 风暴减益"这条闭环）。
 */
public final class BallistaService {

	/** 射程（格，3D）。 */
	public static final double RANGE = 32.0;
	/** 每发伤害（HP）；7♥ = 14 HP。 */
	public static final double DAMAGE = 14.0;
	/** 开火间隔（tick）：2 秒。 */
	public static final int INTERVAL_TICKS = 40;
	/** 风暴中的伤害倍率（09 §2.2：−50%）。 */
	public static final double STORM_MULTIPLIER = 0.5;

	/** 炮位 → 归属队伍。-1 = 还没登记。 */
	private static final Map<BlockPos, Integer> ballistas = new LinkedHashMap<>();
	/** 炮位 → 上次开火 tick。 */
	private static final Map<BlockPos, Integer> lastFire = new LinkedHashMap<>();
	/** 各队已装填的弹药数（09 §2.2：弩炮需弹药，1 发消耗 1 枚）。 */
	private static final Map<Integer, Integer> ammo = new LinkedHashMap<>();

	private BallistaService() {
	}

	/** 放置时登记（由 {@code BallistaBlock.onPlaced} 调用）。 */
	public static void register(BlockPos pos, int team) {
		if (team < 0) {
			return;
		}
		ballistas.put(pos.toImmutable(), team);
	}

	/** 破坏时注销。 */
	public static void unregister(BlockPos pos) {
		ballistas.remove(pos);
		lastFire.remove(pos);
	}

	/** PLAY 每 tick：遍历炮位，找最近敌人开火。 */
	public static void onEndTick(MinecraftServer srv) {
		if (ballistas.isEmpty()) {
			return;
		}
		ServerWorld world = srv.getOverworld();
		int now = srv.getTicks();
		int fired = 0;
		for (Map.Entry<BlockPos, Integer> e : ballistas.entrySet()) {
			BlockPos pos = e.getKey();
			int team = e.getValue();
			Integer last = lastFire.get(pos);
			if (last != null && now - last < INTERVAL_TICKS) {
				continue;
			}
			ServerPlayerEntity target = nearestEnemy(world, pos, team);
			if (target == null) {
				continue;
			}
			if (!PowerService.tryConsume(team, PowerService.FACILITY_COST)) {
				continue;   // 没电就不打（不是减半：炮台没有"半发"）
			}
			// 09 §2.2：弩炮「需弹药（铁+木合成）」。弹药按队记，由玩家把 nbw:ballista_bolt
			// 对着炮位右键装填（BallistaBlock.onUse）。没弹就不发，电也不再扣。
			if (!consumeAmmo(team)) {
				PowerService.add(team, PowerService.FACILITY_COST);   // 把上面扣的电退回去
				continue;
			}
			double dmg = DAMAGE * (EventService.stormActive() ? STORM_MULTIPLIER : 1.0);
			target.damage(world.getDamageSources().magic(), (float) dmg);
			lastFire.put(pos, now);
			fired++;
			NBW.LOGGER.debug("[ballista] {} 开火 → {}（{:.1f} HP，电量剩 {:.1f}）",
					pos, target.getNameForScoreboard(), dmg, PowerService.get(team));
		}
		if (fired > 0) {
			NBW.LOGGER.debug("[ballista] 本 tick {} 门炮开火", fired);
		}
	}

	/** 找 32 格内最近的敌方玩家（同队/旁观/未分队都不算）。 */	private static ServerPlayerEntity nearestEnemy(ServerWorld world, BlockPos pos, int team) {
		ServerPlayerEntity best = null;
		double bestD = RANGE * RANGE;
		double cx = pos.getX() + 0.5;
		double cy = pos.getY() + 0.5;
		double cz = pos.getZ() + 0.5;
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.isSpectator() || !p.isAlive()) {
				continue;
			}
			var session = RoundManager.machine().session(p.getUuid());
			int t = session == null ? -1 : session.teamIndex();
			if (t < 0 || t == team) {
				continue;   // 未分队 / 同队不打
			}
			double d = p.squaredDistanceTo(cx, cy, cz);
			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	/** 一行读数：给 {@code /cww ballista}。 */
	public static String status() {
		if (ballistas.isEmpty()) {
			return "[ballista] 本局没有放置弩炮";
		}
		StringBuilder sb = new StringBuilder("[ballista] ");
		for (Map.Entry<BlockPos, Integer> e : ballistas.entrySet()) {
			sb.append(String.format(java.util.Locale.ROOT, "%s=队%d ", e.getKey(), e.getValue()));
		}
		sb.append(String.format(java.util.Locale.ROOT, "｜弹药 "));
		for (var a : ammo.entrySet()) {
			sb.append(String.format(java.util.Locale.ROOT, "队%d=%d发 ", a.getKey(), a.getValue()));
		}
		return sb.toString().trim();
	}

	/** 局后清理。 */
	public static void reset() {
		ballistas.clear();
		lastFire.clear();
		ammo.clear();
	}

	/** 装填弹药（玩家拿 nbw:ballista_bolt 对炮位右键）。 */
	public static void addAmmo(int team, int count) {
		if (team >= 0 && count > 0) {
			ammo.merge(team, count, Integer::sum);
		}
	}

	/** 某队当前弹药数。 */
	public static int ammoCount(int team) {
		return ammo.getOrDefault(team, 0);
	}

	/** 消耗 1 发弹药；没有就返回 false（调用方据此不开火）。 */
	private static boolean consumeAmmo(int team) {
		int have = ammo.getOrDefault(team, 0);
		if (have <= 0) {
			return false;
		}
		ammo.put(team, have - 1);
		return true;
	}
}
