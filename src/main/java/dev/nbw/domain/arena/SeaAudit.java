package dev.nbw.domain.arena;

import java.util.ArrayList;
import java.util.List;

/**
 * 海域地形与 arena 配置的一致性判定（纯函数，零 Minecraft 依赖）。
 *
 * <p>存在的理由就一条：READY 会把玩家直接传到 {@code waterLevel + 1}，而那片海到底存不存在，
 * 之前没有任何代码看过一眼。配置写错和地形没建是两种完全不同的故障，读数必须能把它们分开说清楚。
 */
public final class SeaAudit {

	/**
	 * 建海分层（与 {@code tools/gen_sea_function.py} 同一组数字）：
	 * 水 {@code waterLevel-9..waterLevel}、海床再往下 {@code FLOOR_DEPTH} 层石头、水面以上清空 {@code AIR_CLEAR} 层。
	 */
	public static final int WATER_DEPTH = 10;
	public static final int FLOOR_DEPTH = 3;
	public static final int AIR_CLEAR = 9;

	/** 一个待检列：它落在哪个坐标、按配置应该是什么样子。 */
	public record Expect(int x, int z, String label, int waterLevel) {
		public int floorTop() {
			return waterLevel - WATER_DEPTH;
		}

		public int airCeiling() {
			return waterLevel + AIR_CLEAR;
		}
	}

	/**
	 * 一列的实测读数。
	 *
	 * @param loaded        区块是否真的加载到位；没加载就只能报 UNKNOWN，绝不能报成"这里没有海"
	 * @param waterSurfaceY 该列最高的水方块 Y，-1 表示扫描范围内没有水
	 * @param floorY        水体下方第一个非空方块的 Y，-1 表示一路到底都是空的
	 */
	public record Column(boolean loaded, int waterSurfaceY, int floorY) {
		public static Column notLoaded() {
			return new Column(false, -1, -1);
		}
	}

	public record Reading(Expect expect, Column column) {
		public Verdict verdict() {
			return SeaAudit.verdict(expect, column);
		}
	}

	public enum Verdict {
		OK("正常"),
		NO_WATER("没有水"),
		WRONG_LEVEL("水位不符"),
		WRONG_FLOOR("海床不符"),
		CHUNK_NOT_LOADED("区块未加载·无法判定");

		private final String label;

		Verdict(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public boolean problem() {
			return this != OK;
		}
	}

	private SeaAudit() {
	}

	public static Verdict verdict(Expect e, Column c) {
		if (!c.loaded()) {
			return Verdict.CHUNK_NOT_LOADED;
		}
		if (c.waterSurfaceY() < 0) {
			return Verdict.NO_WATER;
		}
		if (c.waterSurfaceY() != e.waterLevel()) {
			return Verdict.WRONG_LEVEL;
		}
		if (c.floorY() != e.floorTop()) {
			return Verdict.WRONG_FLOOR;
		}
		return Verdict.OK;
	}

	/** 每个坐标一句人话；只返回有问题的列，全好就是空列表（给 READY 前的日志警告用）。 */
	public static List<String> audit(List<Reading> readings) {
		List<String> lines = new ArrayList<>();
		for (Reading r : readings) {
			if (r.verdict().problem()) {
				lines.add(describe(r));
			}
		}
		return lines;
	}

	/** 一条读数的完整说明，命令与日志共用，避免两处各写一套口径。 */
	public static String describe(Reading r) {
		Expect e = r.expect();
		Column c = r.column();
		return switch (r.verdict()) {
			case OK -> "%s (%d,%d) 正常：水面 y=%d、海床 y=%d".formatted(e.label(), e.x(), e.z(), c.waterSurfaceY(), c.floorY());
			case NO_WATER -> "%s (%d,%d) 没有水：配置水面 y=%d，实际扫描 %d..%d 内一个水方块都没有"
					.formatted(e.label(), e.x(), e.z(), e.waterLevel(), e.floorTop(), e.airCeiling());
			case WRONG_LEVEL -> "%s (%d,%d) 水位不符：实际水面 y=%d，配置 waterLevel=%d（差 %d 格）"
					.formatted(e.label(), e.x(), e.z(), c.waterSurfaceY(), e.waterLevel(),
							c.waterSurfaceY() - e.waterLevel());
			case WRONG_FLOOR -> "%s (%d,%d) 海床不符：水面正常 y=%d，但水底第一块实体方块在 y=%d，按分层应在 y=%d"
					.formatted(e.label(), e.x(), e.z(), c.waterSurfaceY(), c.floorY(), e.floorTop());
			case CHUNK_NOT_LOADED -> ("%s (%d,%d) 所在区块未加载，无法判定 —— 这既不是『没有海』也不是『海没问题』，"
					+ "先把区块加载起来再看").formatted(e.label(), e.x(), e.z());
		};
	}
}
