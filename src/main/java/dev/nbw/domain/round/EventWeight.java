package dev.nbw.domain.round;

import java.util.List;
import java.util.Random;

/**
 * 事件权重（M5 事件收尾，02 §10 的“间隔权重”）。
 *
 * <p>02 §10 只给语义（高/中/低），没有给具体数字。这里取 **高=3 / 中=2 / 低=1**，
 * 含义是“同一池里，高权重事件被抽中的概率是低权重的 3 倍”；改这三个常量就能整体调权重。
 *
 * <p>纯函数，不碰 MC/Sable；随机源由调用方传入，便于单测与复现。
 */
public final class EventWeight {

	/** 高：物资雨。 */
	public static final int HIGH = 3;
	/** 中：风暴 / 黑海倒流。 */
	public static final int MEDIUM = 2;
	/** 低：幸存者聚集点 / 悬赏令。 */
	public static final int LOW = 1;
	/** 未知事件（运维在 format.events.pool 里写了新名字）按“低”处理，不让整池抽不出来。 */
	public static final int DEFAULT = 1;

	private EventWeight() {
	}

	/** 单个事件的权重；大小写不敏感。null/未知返回 {@link #DEFAULT}。 */
	public static int weightOf(String event) {
		if (event == null) {
			return DEFAULT;
		}
		return switch (event.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "supply_rain" -> HIGH;
			case "storm", "black_sea_surge" -> MEDIUM;
			case "survivor_drift", "bounty" -> LOW;
			default -> DEFAULT;
		};
	}

	/**
	 * 按权重抽一个事件。
	 *
	 * @return 抽中的事件；池为空返回 {@code null}（调用方应把空池当作“不触发”处理）
	 */
	public static String pick(List<String> pool, Random rnd) {
		if (pool == null || pool.isEmpty()) {
			return null;
		}
		int total = 0;
		for (String event : pool) {
			total += Math.max(0, weightOf(event));
		}
		if (total <= 0) {
			return pool.get(rnd.nextInt(pool.size()));
		}
		return pickAt(pool, rnd.nextInt(total));
	}

	/**
	 * 确定性版本：给定 {@code [0, total)} 的 roll 返回命中的事件。
	 * 单测用它钉边界，不依赖随机数序列。
	 */
	public static String pickAt(List<String> pool, int roll) {
		if (pool == null || pool.isEmpty()) {
			return null;
		}
		int total = 0;
		for (String event : pool) {
			total += Math.max(0, weightOf(event));
		}
		if (total <= 0) {
			return pool.get(Math.floorMod(roll, pool.size()));
		}
		int r = Math.floorMod(roll, total);
		for (String event : pool) {
			r -= Math.max(0, weightOf(event));
			if (r < 0) {
				return event;
			}
		}
		return pool.get(pool.size() - 1);
	}
}
