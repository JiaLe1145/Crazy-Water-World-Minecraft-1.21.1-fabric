package dev.nbw.raft;

import dev.nbw.NBW;
import dev.nbw.domain.raft.HelmsmanRule;
import dev.nbw.domain.raft.SpeedGovernor;
import dev.nbw.domain.raft.WindModel;
import dev.nbw.domain.raft.WindModel.Wind;
import dev.nbw.domain.survivor.SurvivorJob;
import dev.nbw.round.EventService;
import dev.nbw.round.SurvivorService;
import dev.nbw.sable.SableRafts;
import net.minecraft.server.MinecraftServer;

import java.util.Locale;
import java.util.UUID;

/**
 * 风（M2-5）。全局、同局同风，每 tick 把风推力施加到有帆的筏上。
 *
 * <p>为什么必须有它：实测无动力时筏子只滑约 2 秒就完全停住
 * （{@code 0.332 → 0.047 → 0.007 → 0}），而一局要横跨几百格海域。
 * 一次性冲量根本不够，持续动力是硬需求。
 *
 * <p><b>公平性是一条硬约束</b>：风是全局单值，所有队同一时刻受同一阵风。
 * 如果做成"每队各自的风"，就等于给某队装了隐形发动机，竞技服上这是致命问题。
 * 所以 {@link #set} 只有一份状态，且每次改变都进日志。
 *
 * <p>接下去的用法很自然：事件池里的风暴/顺风事件直接调 {@link #set}，
 * 圈层内的风场差异则留作以后（那时要改成按位置取风，但接口形状不变）。
 */
public final class WindService {

	private static Wind current = Wind.none();

	private WindService() {
	}

	public static Wind current() {
		return current;
	}

	/**
	 * 设定风。
	 *
	 * @param dir 八向之一或 calm；内部换算成轴对齐分量
	 * @param strength 0..{@link WindModel#MAX_STRENGTH}
	 * @return 一行结论；越界或方向不认识时返回原因并且<b>不改状态</b>
	 */
	public static String set(String dir, double strength) {
		if (!WindModel.strengthInRange(strength)) {
			return "风强必须在 0.." + WindModel.MAX_STRENGTH + "，给了 " + strength + "（状态未改动）";
		}
		if (dir == null) {
			return "方向不能为空（状态未改动）";
		}
		String key = dir.toLowerCase(Locale.ROOT);
		double x = 0.0;
		double z = 0.0;
		switch (key) {
			case "calm", "none", "无风" -> { /* 两个分量都留 0 */ }
			case "north" -> z = -1.0;
			case "south" -> z = 1.0;
			case "east" -> x = 1.0;
			case "west" -> x = -1.0;
			case "northeast" -> { x = 0.7071; z = -0.7071; }
			case "northwest" -> { x = -0.7071; z = -0.7071; }
			case "southeast" -> { x = 0.7071; z = 0.7071; }
			case "southwest" -> { x = -0.7071; z = 0.7071; }
			default -> {
				return "不认识的方向 " + dir + "（可用 calm/north/south/east/west 及四个斜向；状态未改动）";
			}
		}
		Wind previous = current;
		current = new Wind(x, z, strength);
		NBW.LOGGER.info("[wind] 风从 {} 改为 dir={}({},{}) strength={}",
				previous, key, fmt(x), fmt(z), fmt(strength));
		return "风已设为 " + key + " 强度 " + fmt(strength);
	}

	/**
	 * 每 tick 登记。必须在 {@code RaftMotionService.onEndTick} 之前调用，
	 * 这样帆与遥控器的同一份请求能被合成一次施力。
	 *
	 * <p>问题③：帆的方向跟随前进方向。所以这里不再按"各朝向帆数"算合力，
	 * 而是按"风在筏前进方向上的顺风分量 × 帆数"直接登记一个朝前进方向的目标速度。
	 *
	 * @return 本 tick 登记了几艘船的风力目标（给探针与日志用）
	 */
	public static int apply(MinecraftServer server) {
		if (EventService.stormActive()) {
			return 0;   // 风暴：帆与遥控一起失效（02 §10 / 01 §9）
		}
		if (current.calm()) {
			return 0;
		}
		int pushed = 0;
		for (int team : java.util.List.of(0, 1, 2, 3, 4, 5, 6, 7)) {
			UUID raft = RaftSpawner.raftOf(team);
			if (raft == null) {
				continue;
			}
			int sails = Sailing.sailCount(server.getOverworld(), raft);
			if (sails == 0) {
				continue;   // 没帆就不该有风力：帆是受风面，裸筏只能靠遥控器马达
			}
			double[] fwd = SableRafts.forward(server.getOverworld(), raft);
			double tailwind = WindModel.tailwindAlong(current, fwd[0], fwd[1]);
			if (tailwind <= 0.0) {
				continue;   // 静风或逆风：帆这一档连目标速度都没有
			}
			// 只登记"想以多大位移速度往前进方向走"。施力统一交给 RaftMotionService。
			// 舵手同样加成帆速（01 §7 A6）：先乘倍率再夹上限，满帆时加成自然被上限吸收。
			boolean hasHelmsman = SurvivorService.hasJob(team, SurvivorJob.HELMSMAN);
			double target = SpeedGovernor.clampSpeed(
					HelmsmanRule.speed(SpeedGovernor.sailTarget(sails, tailwind), hasHelmsman));
			RaftMotionService.request(team, fwd[0], fwd[1], target);
			pushed++;
		}
		return pushed;
	}

	/** 一行读数：风况 + 指定队的帆数/前进方向/顺风分量。运维与探针都靠它。 */
	public static String readout(MinecraftServer server, int teamIndex) {
		UUID raft = RaftSpawner.raftOf(teamIndex);
		double[] fwd = raft == null ? new double[]{0.0, 1.0}
				: SableRafts.forward(server.getOverworld(), raft);
		double tailwind = WindModel.tailwindAlong(current, fwd[0], fwd[1]);
		int sails = raft == null ? 0 : Sailing.sailCount(server.getOverworld(), raft);
		return String.format(Locale.ROOT,
				"[wind] 风 dir=(%.3f, %.3f) strength=%.2f | 队%d 帆=%d 朝向=(%.3f,%.3f) 顺风=%.2f | %s",
				current.dirX(), current.dirZ(), current.strength(), teamIndex, sails,
				fwd[0], fwd[1], tailwind,
				RaftMotionService.travelReadout(teamIndex));
	}

	private static String fmt(double d) {
		return String.format(Locale.ROOT, "%.4f", d);
	}
}
