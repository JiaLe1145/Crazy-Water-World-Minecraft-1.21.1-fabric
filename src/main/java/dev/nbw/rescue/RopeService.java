package dev.nbw.rescue;

import dev.nbw.NBW;
import dev.nbw.domain.raft.RaftLayout;
import dev.nbw.domain.rescue.ClingRule;
import dev.nbw.domain.rescue.LineRule;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.round.RoundManager;
import dev.nbw.sable.SableRafts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * 抛绳索 / 钩竿 的运行时（M2 尾巴，02 §8 + 09 §2.3）。
 *
 * <p>与 {@link RescueService}（自救 + 伸手）的区别：这两个是<b>远程道具</b>，一次使用（右键）
 * 就生效，目标在<b>世界坐标</b>里挑最近那个合格玩家。判定全在 {@link LineRule}（纯函数），
 * 这里只做"挑人 + 传送"。
 *
 * <p><b>绳索</b>：把远处在水里的队友拉到本队筏的甲板落点（复用 {@link ClingRule#landingLocal}
 * 那套夹进甲板的逻辑，所以落点一定站得住）。<b>钩竿</b>：把敌方玩家拉近到救援方 2 格外
 * （"拉近"即"拉下水"——他本来站在自己筏上，被拉到救援方脚边就是落到两筏之间的水里）。
 *
 * <p>钩竿的"0.5s 挣脱窗口"与"拉漂浮物"本轮不做（依赖更多系统），{@code M2-RESULTS.md} 记。
 */
public final class RopeService {

	private static long ropePulls;
	private static long grapples;

	private RopeService() {
	}

	/**
	 * 一次使用（右键 / 命令）。{@code grapnel=false} 是绳索，{@code true} 是钩竿。
	 *
	 * @return 一行结论（给命令与探针断言）；{@code use()} 入口会忽略返回值只留日志
	 */
	public static String cast(ServerPlayerEntity caster, boolean grapnel) {
		ServerWorld world = caster.getServerWorld();
		if (world == null || !RoundManager.phase().allowsPvp()) {
			return "不在对局中，用不了";
		}
		var session = RoundManager.machine().session(caster.getUuid());
		int casterTeam = session == null ? -1 : session.teamIndex();
		if (casterTeam < 0) {
			return "你没有分队";
		}
		if (caster.isSpectator()) {
			return "旁观者不能用绳索/钩竿";
		}

		// 钩竿的第一用途：把海面漂流幸存者拉上筏（招募优先于拉敌人，2026-11-09）
		if (grapnel) {
			java.util.UUID floating = dev.nbw.round.SurvivorService.nearestFloating(
					world, caster.getPos(), LineRule.GRAPNEL_RANGE);
			if (floating != null) {
				if (dev.nbw.round.SurvivorService.recruitSurvivor(casterTeam, floating)) {
					return String.format(Locale.ROOT, "招募了 1 名漂流幸存者（队 %d）", casterTeam);
				}
				return "幸存者人口已满，招募失败";
			}
		}

		ServerPlayerEntity best = null;
		double bestDist = Double.MAX_VALUE;
		String sampleDenied = "没有可拉的人";
		for (ServerPlayerEntity p : world.getPlayers()) {
			if (p.getUuid().equals(caster.getUuid()) || p.isSpectator()) {
				continue;   // 旁观者既不能被拉，也不能成为绳索/钩竿的目标
			}
			var s = RoundManager.machine().session(p.getUuid());
			int team = s == null ? -1 : s.teamIndex();
			double dist = horizontalDist(caster, p);
			String denied = grapnel
					? LineRule.grapnelDeny(true, team >= 0 && team != casterTeam, true, dist)
					: LineRule.ropeDeny(true, team == casterTeam, p.isTouchingWater(), true, dist);
			if (denied != null) {
				if (best == null && sampleDenied.equals("没有可拉的人")) {
					sampleDenied = denied;   // 记住第一个拒绝原因，全灭时好归因
				}
				continue;
			}
			if (dist < bestDist) {
				best = p;
				bestDist = dist;
			}
		}
		if (best == null) {
			NBW.LOGGER.info("[rope] {} {}：{}", caster.getName().getString(), grapnel ? "钩竿" : "绳索", sampleDenied);
			return sampleDenied;
		}

		if (grapnel) {
			pullToward(caster, best);
			grapples++;
			NBW.LOGGER.info("[rope] {} 用钩竿把 {} 拉到 2 格外（距离 {:.1f} 格）",
					caster.getName().getString(), best.getName().getString(), bestDist);
			return String.format(Locale.ROOT, "已把 %s 拉近到 %.1f 格外（原距离 %.1f 格）",
					best.getName().getString(), LineRule.GRAPNEL_PULL_GAP, bestDist);
		}

		UUID raft = RaftSpawner.raftOf(casterTeam);
		if (raft == null) {
			return "队 " + casterTeam + " 这一局没有筏，拉不了人";
		}
		pullToRaft(caster, raft, best);
		ropePulls++;
		NBW.LOGGER.info("[rope] {} 把队友 {} 拉回筏上（距离 {:.1f} 格）",
				caster.getName().getString(), best.getName().getString(), bestDist);
		return String.format(Locale.ROOT, "已把队友 %s 拉回筏上（距离 %.1f 格）",
				best.getName().getString(), bestDist);
	}

	/** dev / 探针入口：按名字找玩家再走同一条 {@link #cast}。 */
	public static String cast(MinecraftServer server, @Nullable String playerName, boolean grapnel) {
		ServerPlayerEntity p = playerByName(server, playerName);
		if (p == null) {
			return "找不到在线玩家 " + playerName;
		}
		return cast(p, grapnel);
	}

	private static void pullToRaft(ServerPlayerEntity caster, UUID raft, ServerPlayerEntity victim) {
		ServerWorld world = victim.getServerWorld();
		int half = RaftLayout.halfWidth(RaftSpawner.deckSide());
		double[] local = SableRafts.worldToLocal(world, raft, victim.getX(), victim.getY(), victim.getZ());
		if (Double.isNaN(local[0])) {
			return;   // 读不到位姿：不猜，下一拍再试
		}
		double[] landing = ClingRule.landingLocal(local[0], local[2], half);
		double[] worldPos = SableRafts.localToWorld(world, raft, landing[0], landing[1], landing[2]);
		if (Double.isNaN(worldPos[0])) {
			return;
		}
		victim.setVelocity(0.0, 0.0, 0.0);
		victim.requestTeleport(worldPos[0], worldPos[1], worldPos[2]);
		victim.sendMessage(Text.literal("§a[cww] 队友用绳索把你拉回了木筏"), true);
	}

	private static void pullToward(ServerPlayerEntity caster, ServerPlayerEntity enemy) {
		double dx = enemy.getX() - caster.getX();
		double dz = enemy.getZ() - caster.getZ();
		double len = Math.hypot(dx, dz);
		if (len < 1e-9) {
			dx = 1.0;
			dz = 0.0;
			len = 1.0;
		}
		double tx = caster.getX() + dx / len * LineRule.GRAPNEL_PULL_GAP;
		double tz = caster.getZ() + dz / len * LineRule.GRAPNEL_PULL_GAP;
		// 拉到救援方脚边的水平位置、Y 压低一格：目标从"站筏上"变成"落到两筏之间的水面"
		enemy.setVelocity(0.0, -0.3, 0.0);
		enemy.requestTeleport(tx, caster.getY() - 1.0, tz);
		enemy.sendMessage(Text.literal("§c[cww] 你被敌方的钩竿拉了过去"), true);
	}

	private static double horizontalDist(ServerPlayerEntity a, ServerPlayerEntity b) {
		double dx = a.getX() - b.getX();
		double dz = a.getZ() - b.getZ();
		return Math.hypot(dx, dz);
	}

	private static @Nullable ServerPlayerEntity playerByName(MinecraftServer server, @Nullable String name) {
		if (name == null) {
			return null;
		}
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
		return p != null ? p : server.getPlayerManager().getPlayer(name.toLowerCase(Locale.ROOT));
	}

	/** 一行读数：绳索/钩竿累计计数，给 {@code /cww rope status} 与探针。 */
	public static String status() {
		return String.format(Locale.ROOT, "[rope] 绳索拉回=%d 钩竿钩人=%d｜射程 绳=%.0f格 钩=%.0f格",
				ropePulls, grapples, LineRule.ROPE_RANGE, LineRule.GRAPNEL_RANGE);
	}

	/** 局后清理：累计计数是跨局运维量，故意不清（与 rescue 的计数同一口径）。 */
	public static void reset() {
		// 无局内状态可清：绳索/钩竿是瞬发动作，不存进行中的状态
	}
}
