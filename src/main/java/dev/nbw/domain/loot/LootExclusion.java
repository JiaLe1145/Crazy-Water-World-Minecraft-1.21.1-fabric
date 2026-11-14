package dev.nbw.domain.loot;

import java.util.List;

/**
 * 物资排除区（01 §5 / 03 `loot.exclude`）的纯规则。
 *
 * <p>矩形沿用 arena 配置的<b>半开区间</b>约定：{@code x ∈ [minX, maxX)}、
 * {@code z ∈ [minZ, maxZ)}。这样"相邻两个排除区"不会在边界上重叠，
 * 与 {@code ConfigModels.Arena.Bounds} 的语义一致。
 *
 * <p>为什么单独成类：排除区判定必须能脱离 Minecraft 单测。
 * {@code LootService.spawn} 只负责把配置翻成 {@link Rect} 并调用这里，
 * 防止"配置写了排除区、代码没读"再次静默发生。
 */
public final class LootExclusion {

	/** 一个不刷物资的矩形区域（方块坐标，半开区间）。 */
	public record Rect(int minX, int minZ, int maxX, int maxZ) {

		public boolean contains(int x, int z) {
			return x >= minX && x < maxX && z >= minZ && z < maxZ;
		}
	}

	private LootExclusion() {
	}

	/** 该坐标是否落在任一排除区内；空列表表示任何地方都可刷。 */
	public static boolean contains(int x, int z, List<Rect> rects) {
		for (Rect r : rects) {
			if (r.contains(x, z)) {
				return true;
			}
		}
		return false;
	}
}
