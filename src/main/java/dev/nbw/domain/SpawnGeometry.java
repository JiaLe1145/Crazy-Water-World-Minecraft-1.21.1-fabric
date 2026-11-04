package dev.nbw.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 等角出生点（03 §3.3 A15c、06 M1"等角出生"）。
 * 纯几何，不依赖 Minecraft：一张图能跑 2/3/4/6 队而不用为每种人数重画地图。
 */
public final class SpawnGeometry {

	/** 出生点到圈心的距离占海域半径的比例（arena 默认图 112/140 = 0.8，与之对齐）。 */
	public static final double DEFAULT_RADIUS_FACTOR = 0.8;

	/** 一个队伍出生点：方块整数坐标 + 朝向圈心的 yaw（03 §2 约定用方块坐标，避免半格歧义）。 */
	public record Spot(int teamIndex, int x, int z, float yaw, double distanceFromCenter) {
	}

	private SpawnGeometry() {
	}

	/**
	 * 从正北（-Z）起顺时针均分。
	 * @throws IllegalArgumentException teamCount / radius 非法（配置层的责任，不做静默纠正）
	 */
	public static List<Spot> equidistant(int centerX, int centerZ, double radius, int teamCount, double radiusFactor) {
		if (teamCount < 1) {
			throw new IllegalArgumentException("teamCount 必须 >= 1");
		}
		if (radius <= 0) {
			throw new IllegalArgumentException("radius 必须 > 0");
		}
		if (radiusFactor <= 0 || radiusFactor > 1) {
			throw new IllegalArgumentException("radiusFactor 必须在 (0,1]");
		}
		double r = radius * radiusFactor;
		List<Spot> spots = new ArrayList<>(teamCount);
		for (int i = 0; i < teamCount; i++) {
			double angle = Math.toRadians(-90.0 + 360.0 * i / teamCount);
			int x = (int) Math.round(centerX + r * Math.cos(angle));
			int z = (int) Math.round(centerZ + r * Math.sin(angle));
			// 朝向圈心：MC 视线向量 = (-sin yaw, cos yaw)，故 yaw = atan2(x-cx, cz-z)
			float yaw = (float) Math.toDegrees(Math.atan2(x - centerX, centerZ - z));
			spots.add(new Spot(i, x, z, yaw, Math.hypot(x - centerX, z - centerZ)));
		}
		return spots;
	}

	/** 03 §6.1 规则 4 的自动补位版本：点必须落在圆内、距中心 >= radius*0.5、两两间距 >= minSeparation。 */
	public static List<String> validate(List<Spot> spots, int centerX, int centerZ, double radius, int minSeparation) {
		List<String> errors = new ArrayList<>();
		for (Spot s : spots) {
			if (s.distanceFromCenter() > radius) {
				errors.add("spawns.teams[" + s.teamIndex() + "] 落在海域圆外（距中心 "
						+ (int) s.distanceFromCenter() + " > " + (int) radius + "）");
			}
			if (s.distanceFromCenter() < radius * 0.5) {
				errors.add("spawns.teams[" + s.teamIndex() + "] 距圈心过近（应 >= radius*0.5）");
			}
		}
		for (int i = 0; i < spots.size(); i++) {
			for (int j = i + 1; j < spots.size(); j++) {
				double d = Math.hypot(spots.get(i).x() - spots.get(j).x(), spots.get(i).z() - spots.get(j).z());
				if (d < minSeparation) {
					errors.add("出生点 " + spots.get(i).teamIndex() + "/" + spots.get(j).teamIndex()
							+ " 间距 " + (int) d + " < " + minSeparation);
				}
			}
		}
		return errors;
	}

	/** 相邻点最小间距（给地图作者看"这张图最多能开几队"）。 */
	public static int minSeparation(List<Spot> spots) {
		int min = Integer.MAX_VALUE;
		for (int i = 0; i < spots.size(); i++) {
			for (int j = i + 1; j < spots.size(); j++) {
				int d = (int) Math.hypot(spots.get(i).x() - spots.get(j).x(), spots.get(i).z() - spots.get(j).z());
				min = Math.min(min, d);
			}
		}
		return spots.size() < 2 ? 0 : min;
	}

	/** 该半径/间距下限下最多能塞几队（02 §1"人数不足时自动降档"的依据）。 */
	public static int maxTeamsFor(double radius, int minSeparation, double radiusFactor) {
		double r = radius * radiusFactor;
		if (r <= 0 || minSeparation <= 0) {
			return 0;
		}
		// 弦长 = 2r*sin(pi/n) >= minSeparation
		double ratio = minSeparation / (2.0 * r);
		if (ratio >= 1.0) {
			return 0;
		}
		int n = (int) Math.floor(Math.PI / Math.asin(ratio));
		return Math.max(1, n);
	}
}
