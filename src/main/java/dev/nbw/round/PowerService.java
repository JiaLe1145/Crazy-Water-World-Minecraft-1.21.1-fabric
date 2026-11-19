package dev.nbw.round;

import dev.nbw.NBW;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 筏级功率池（M4 补，02 §7）：每队一份电量，发电机充、设施耗；不足时设施效率减半（不是停机）。
 *
 * <p>不做真实电路/导线/实体——只维护一个队级电量数字，设施用 {@link #multiplierFor} 拿倍率。
 * 这是 02 §7 明确的取舍："做电路会吞掉 MVP 周期"。
 */
public final class PowerService {

	/** 每队电量上限。 */
	public static final double MAX_POWER = 20.0;
	/** 发电机单次充能。 */
	public static final double GENERATOR_CHARGE = 5.0;
	/** 设施单次耗电。 */
	public static final double FACILITY_COST = 1.0;
	/** 功率不足时的效率倍率。 */
	public static final double LOW_POWER_MULTIPLIER = 0.5;

	private static final Map<Integer, Double> power = new LinkedHashMap<>();

	private PowerService() {
	}

	/** PLAY 开始：每队电量清零。 */
	public static void begin(MinecraftServer srv) {
		power.clear();
		for (var team : RoundManager.machine().teams()) {
			if (!team.members().isEmpty()) {
				power.put(team.index(), 0.0);
			}
		}
		NBW.LOGGER.info("[power] 开局：{} 队电量池", power.size());
	}

	/** 发电机充能（夹到上限）。 */
	public static void add(int team, double amount) {
		power.merge(team, amount, (a, b) -> Math.min(MAX_POWER, a + b));
	}

	/** 某队当前电量。 */
	public static double get(int team) {
		return power.getOrDefault(team, 0.0);
	}

	/**
	 * 设施使用前调用：够电就扣 1 回 1.0（满效），不够就回 0.5（减半）。
	 * 找不到队伍时按满效（大厅/旁观不卡生产）。
	 */
	public static double multiplierFor(ServerPlayerEntity player) {
		var session = RoundManager.machine().session(player.getUuid());
		if (session == null || session.teamIndex() < 0) {
			return 1.0;
		}
		int team = session.teamIndex();
		if (power.getOrDefault(team, 0.0) >= FACILITY_COST) {
			power.put(team, power.get(team) - FACILITY_COST);
			return 1.0;
		}
		return LOW_POWER_MULTIPLIER;
	}

	/**
	 * 硬性耗电（修理台这类"没电就别干"的设施）：够就扣、不够就不扣并返回 false。
	 * 与 {@link #multiplierFor} 的区别是它没有"减半"这个档。
	 */
	public static boolean tryConsume(int team, double amount) {
		if (team < 0) {
			return false;
		}
		double have = power.getOrDefault(team, 0.0);
		if (have < amount) {
			return false;
		}
		power.put(team, have - amount);
		return true;
	}

	/** 一行读数：给 {@code /cww power}。 */
	public static String status() {
		if (power.isEmpty()) {
			return "[power] 本局没有电量池（未开局/无队伍）";
		}
		StringBuilder sb = new StringBuilder("[power] ");
		for (Map.Entry<Integer, Double> e : power.entrySet()) {
			sb.append(String.format(Locale.ROOT, "队%d=%.1f ", e.getKey(), e.getValue()));
		}
		return sb.toString().trim();
	}

	/** 局后清理。 */
	public static void reset() {
		power.clear();
	}
}
