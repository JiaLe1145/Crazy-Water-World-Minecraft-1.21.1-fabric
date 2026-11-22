package dev.nbw.raft;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.domain.arena.HazardRule;
import dev.nbw.domain.raft.SpeedGovernor;
import dev.nbw.round.RoundManager;
import dev.nbw.sable.SableRafts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 每 tick 的航行调度（#39）：把"动力源想要的航速"翻译成<b>真实位移</b>上的控制冲量。
 *
 * <p>分工要说清，否则配平会散在三处：
 * <ul>
 *   <li>{@link WindService} 与 {@link OarService} 只负责<b>算自己那一份目标速度矢量</b>并登记请求，
 *       不再各自 {@code push}；</li>
 *   <li>这里每个 tick 把各队请求合成一个目标方向，读位移差算出实测航速，
 *       再交给 {@link SpeedGovernor} 得到唯一一发冲量；</li>
 *   <li>数值口径全在 {@code domain/raft/SpeedGovernor}（纯函数 + 单测），这里不写数字。</li>
 * </ul>
 *
 * <p><b>为什么一定要单点施力：</b>帆与桨若各自闭环，两个控制器都盯着同一个刚体、
 * 却各自只看自己的方向，合力就会在临界速度上互相打摆；玩家看到的表现是筏子抽搐。
 * 合成矢量之后再控制，抵消关系（2 南 + 2 北 = 不动）仍然是自然的。
 *
 * <p>每 tick 读一次位姿是刻意的：只为测量，不写世界。{@code SableRafts.position} 走
 * {@code logicalPose}，与 {@code posY} 同一来源，代价与一次 map 查找同级。
 */
public final class RaftMotionService {

	/** 队号 → 本 tick 的目标速度矢量（{@code {vx, vz}}，格/tick）。由各动力源累加。 */
	private static final Map<Integer, double[]> wanted = new LinkedHashMap<>();

	/**
	 * 队号 → 上一 tick 的位姿，形状与 {@code SableRafts.position} 一致：{@code {x, y, z}}。
	 * 位移差只用索引 0（x）与 2（z）；索引 1 是 y，浮沉不进入水平航速。
	 */
	private static final Map<Integer, double[]> lastPose = new LinkedHashMap<>();

	/** 队号 → 最近一次实测位移速度矢量（格/tick），给读数与探针。 */
	private static final Map<Integer, double[]> measured = new LinkedHashMap<>();

	/** 队号 → 最近一次的目标速率（格/tick），给读数与探针。 */
	private static final Map<Integer, Double> targetSpeed = new LinkedHashMap<>();

	/** 队号 → 施力瞬间的峰值线速度，只作诊断（历史教训：它曾是唯一被读的数，结果全错，#39）。 */
	private static final Map<Integer, Double> peak = new LinkedHashMap<>();

	private static long samples;
	private static int pushed;
	/** 本 tick 施加了姿态修正的筏数（诊断用；稳态 0 = 没有筏需要扶）。 */
	private static int stabilized;

	/**
	 * 姿态稳定的参数（用户 2026-11-22："速度过快、木筏翻倒入水"）。
	 *
	 * <p>阈值取 8°：实测静止倾斜 2~3°、全速直航 6~7°、满帆转向峰值 12.9°。
	 * 8° 正好"不影响正常航行，只压真正要翻的那一段"。
	 */
	private static final double STABILIZE_MAX_TILT_DEG = 8.0;
	/** 每超出 1° 的回正角冲量。 */
	private static final double STABILIZE_GAIN = 0.05;
	/** 单 tick 回正角冲量上限（与 TURN_IMPULSE 同量纲，1.5 ≈ 30°/s 偏航）。 */
	private static final double STABILIZE_MAX_IMPULSE = 0.50;
	/** 俯仰/横滚角速度上限（rad/s）：约 46°/s，超过就阻尼。 */
	private static final double STABILIZE_MAX_TILT_RATE = 0.8;

	private RaftMotionService() {
	}

	/**
	 * 登记一个动力源的本 tick 请求。
	 *
	 * @param dirX 单位方向的东西分量（轴对齐）
	 * @param dirZ 单位方向的南北分量
	 * @param speed 该档的目标位移速度（格/tick），由 {@link SpeedGovernor} 的档位函数给出
	 */
	public static void request(int team, double dirX, double dirZ, double speed) {
		if (speed <= 0.0 || Double.isNaN(speed)) {
			return;
		}
		double[] acc = wanted.computeIfAbsent(team, k -> new double[2]);
		acc[0] += dirX * speed;
		acc[1] += dirZ * speed;
	}

	/**
	 * 每 tick 一次，顺序不能反：<b>先</b>用上一 tick 存下的位姿算位移差并施控制冲量，
	 * <b>再</b>为所有在场的筏（不只本 tick 有请求的）推进位姿基线。
	 *
	 * <p>反过来的话差值永远是 0 —— 上一版就是这么写的，现场表现是"筏子明明在走，
	 * 速率读数一直是 0.0000"。基线覆盖所有在场筏也是必须的：一个队划划停停时，
	 * 若只在有请求的 tick 更新基线，下一 tick 的差值会跨过多拍，把实测航速算低、控制器随之过冲。
	 *
	 * @return 实际施力的筏数（给探针与日志用）
	 */
	public static int onEndTick(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		// 顺序就是这一节的全部难点：先用<b>上一 tick</b> 的位姿算位移，再推进位姿基线。
		// 反过来写差值永远是 0 —— 上一版就是这么错的，现场表现是"筏子明明在走，速率读数 0.0000"。
		Map<Integer, double[]> now_measured = new LinkedHashMap<>();
		Map<Integer, Double> now_target = new LinkedHashMap<>();
		Map<Integer, Double> now_peak = new LinkedHashMap<>();
		List<HazardRule.Zone> hazards = hazardZones();
		int did = 0;
		for (Map.Entry<Integer, double[]> e : wanted.entrySet()) {
			int team = e.getKey();
			UUID raft = RaftSpawner.raftOf(team);
			if (raft == null) {
				continue;
			}
			double[] v = SpeedGovernor.combine(e.getValue()[0], e.getValue()[1], 1.0, 0.0, 0.0, 0.0);
			double rawSpeed = v[2];
			if (rawSpeed <= 0.0) {
				// 抵消干净的请求不进读数：留着上一 tick 的目标会让 目标= 撒谎
				continue;
			}
			double dirX = v[0] / rawSpeed;
			double dirZ = v[1] / rawSpeed;
			double[] now = SableRafts.position(world, raft);
			// 03 §3.2：暗礁/残骸不阻断，只减速；筏心位置是"船体在不在障碍里"的代理。
			double target = rawSpeed * HazardRule.speedMultiplier(hazards, now[0], now[2]);
			if (target <= 0.0) {
				continue;
			}
			double[] prev = lastPose.get(team);
			double actual = 0.0;
			if (prev != null && !Double.isNaN(now[0]) && !Double.isNaN(now[2])
					&& !Double.isNaN(prev[0]) && !Double.isNaN(prev[2])) {
				double dx = now[0] - prev[0];
				double dz = now[2] - prev[2];
				actual = SpeedGovernor.actualAlong(dx, dz, dirX, dirZ);
				now_measured.put(team, new double[]{dx, dz});
				samples++;
			}
			now_target.put(team, target);
			double[] vv = SableRafts.linearVelocity(world, raft);
			now_peak.put(team, Math.hypot(vv[0], vv[2]));
			double mass = SableRafts.mass(world, raft);
			double impulse = SpeedGovernor.impulse(mass, actual, target);
			if (impulse <= 0.0) {
				continue;   // 已经追上目标：这一 tick 一句力都不施（也绝不倒车刹车）
			}
			if (SableRafts.push(world, raft, dirX * impulse, 0.0, dirZ * impulse)) {
				did++;
			}
		}
		// 位姿基线覆盖所有在场筏，而不只是本 tick 有请求的那些：
		// 否则一个队划划停停时，下一 tick 的差值会跨过多拍，把实测航速算低、控制器就过冲。
		for (Map.Entry<Integer, UUID> e : RaftSpawner.current().entrySet()) {
			double[] p = SableRafts.position(world, e.getValue());
			if (!Double.isNaN(p[0]) && !Double.isNaN(p[2])) {
				// 与 SableRafts.position 同形的 {x, y, z}，读侧只用索引 0 与 2。
				// 上一版在这里存两个分量、读三个索引，有请求的第二 tick 就把服务端整个崩掉。
				lastPose.put(e.getKey(), p);
			}
			// 姿态稳定每 tick 都要跑（不只是有推力的那些）：漂着被浪顶歪、被撞歪也要扶回来。
			// 数值刻意保守，只压"会失控"的部分，偏航（转向）完全不碰。
			if (SableRafts.stabilize(world, e.getValue(), STABILIZE_MAX_TILT_DEG, STABILIZE_GAIN,
					STABILIZE_MAX_IMPULSE, STABILIZE_MAX_TILT_RATE)) {
				stabilized++;
			}
		}
		measured.clear();
		measured.putAll(now_measured);
		targetSpeed.clear();
		targetSpeed.putAll(now_target);
		peak.clear();
		peak.putAll(now_peak);
		pushed = did;
		wanted.clear();
		return did;
	}

	/** 把本图 hazards 翻成纯规则可用的区间；没有配置时返回空列表。 */
	private static List<HazardRule.Zone> hazardZones() {
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		if (arena == null || arena.hazards == null || arena.hazards.isEmpty()) {
			return List.of();
		}
		List<HazardRule.Zone> out = new ArrayList<>();
		for (ConfigModels.Arena.Hazard h : arena.hazards) {
			out.add(new HazardRule.Zone(h.type, h.minX, h.minZ, h.maxX, h.maxZ));
		}
		return out;
	}

	/**
	 * 一行航行读数。<b>字段名就是探针的解析口径</b>，改这里必须同步改 {@code oar_probe}/{@code sea_probe}。
	 *
	 * <p>{@code 速度=} 与 {@code 速率=} 现在是<b>位移</b>口径（相邻两 tick 的位姿差），
	 * 也就是玩家看见的那个快慢；峰值单列成 {@code 峰值=} 只作诊断 ——
	 * 曾经它就是唯一的"航速"，而它比位移高约 22 倍（#39）。
	 */
	public static String travelReadout(int team) {
		double[] m = measured.get(team);
		double[] v = m == null ? new double[]{0.0, 0.0} : m;
		return String.format(Locale.ROOT,
				"速度=(%.4f, %.4f) 速率=%.4f 目标=%.4f 峰值=%.4f 上限=%.3f 采样=%d 施力=%d 稳姿=%d",
				v[0], v[1], Math.hypot(v[0], v[1]), targetSpeed.getOrDefault(team, 0.0),
				peak.getOrDefault(team, 0.0), SpeedGovernor.MAX_TRAVEL_SPEED, samples, pushed, stabilized);
	}

	/** 最近一次实测位移速度矢量（格/tick）；没测过就返回零向量。 */
	public static double[] measuredVector(int team) {
		double[] m = measured.get(team);
		return m == null ? new double[]{0.0, 0.0} : new double[]{m[0], m[1]};
	}

	/** 给单测与探针：累计采过多少次相邻 tick 的位移差。 */
	public static long samples() {
		return samples;
	}

	/** 局后清理：请求、位姿基线、读数全部丢掉，不许跨局残留成"上一局的目标航速"。 */
	public static void reset() {
		wanted.clear();
		lastPose.clear();
		measured.clear();
		targetSpeed.clear();
		peak.clear();
		samples = 0;
		pushed = 0;
		stabilized = 0;
		NBW.LOGGER.debug("[sailing] 航行控制器状态已清空");
	}
}
