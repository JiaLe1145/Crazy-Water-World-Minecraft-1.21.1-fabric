package dev.nbw.domain.loot;

import java.util.List;
import java.util.Map;

/**
 * 物资稀有度抽卡（M3-补，02 §4）：按圈层 tier 抽 Common/Rare/Epic/Legendary，再从该档物品池取一件。
 *
 * <p>纯函数、可单测。权重表直接抄 02 §4：
 * tier1 78/20/2/0、tier2 60/32/8/0、tier3 40/35/20/5。
 * <b>Legendary 只在 P3 之后 + tier3 出现</b>——这里用 {@code legendaryUnlocked} 参数表达，
 * 由调用方按 {@code ZoneModel} 的 P3 时刻算。
 */
public final class LootRoll {

	public enum Rarity {
		COMMON, RARE, EPIC, LEGENDARY
	}

	/** 一件可掉落物：id + 数量区间。 */
	public record Item(String id, int min, int max) {
	}

	/** 02 §4 的权重（行=tier 1..3，列=COMMON/RARE/EPIC/LEGENDARY）。 */
	private static final double[][] WEIGHTS = {
			{78, 20, 2, 0},
			{60, 32, 8, 0},
			{40, 35, 20, 5},
	};

	private static final Map<Rarity, List<Item>> POOLS = Map.of(
			Rarity.COMMON, List.of(
					new Item("minecraft:oak_planks", 2, 6),
					new Item("minecraft:oak_log", 1, 4),
					new Item("minecraft:stick", 2, 8),
					new Item("minecraft:string", 1, 6),
					new Item("minecraft:bread", 1, 1),
					new Item("minecraft:apple", 1, 1),
					new Item("nbw:fresh_water", 1, 2)),
			Rarity.RARE, List.of(
					new Item("minecraft:iron_ingot", 1, 2),
					new Item("minecraft:coal", 2, 6),
					new Item("minecraft:cooked_cod", 1, 1),
					new Item("nbw:bandage", 1, 2),
					new Item("nbw:water_pistol", 1, 2),
					new Item("nbw:cutlass", 1, 1),
					new Item("nbw:hand_crossbow", 1, 1),
					new Item("nbw:javelin", 1, 1)),
			Rarity.EPIC, List.of(
					new Item("minecraft:diamond", 1, 2),
					new Item("minecraft:golden_apple", 1, 1),
					new Item("nbw:warhammer", 1, 1),
					new Item("nbw:twin_blade", 1, 1),
					new Item("nbw:musket", 1, 1),
					new Item("nbw:harpoon_gun", 1, 1),
					new Item("nbw:seawater_filter", 1, 1),
					new Item("nbw:medical_kit", 1, 1)),
			Rarity.LEGENDARY, List.of(
					new Item("minecraft:diamond", 3, 4),
					new Item("minecraft:golden_apple", 2, 2),
					new Item("nbw:warhammer", 1, 1),
					new Item("nbw:twin_blade", 1, 1)));

	private LootRoll() {
	}

	/**
	 * 抽稀有度。
	 *
	 * @param tier               圈层 tier（1..3；越界按 1/3 夹）
	 * @param legendaryUnlocked  是否已过 P3（false 时 Legendary 权重归零）
	 * @param roll01             [0,1) 的随机数
	 */
	public static Rarity rollRarity(int tier, boolean legendaryUnlocked, double roll01) {
		double[] w = WEIGHTS[Math.max(0, Math.min(2, tier - 1))];
		double total = 0.0;
		for (int i = 0; i < 4; i++) {
			if (i == 3 && !legendaryUnlocked) {
				continue;
			}
			total += w[i];
		}
		if (total <= 0.0) {
			return Rarity.COMMON;
		}
		double x = Math.max(0.0, Math.min(0.999999, roll01)) * total;
		for (int i = 0; i < 4; i++) {
			if (i == 3 && !legendaryUnlocked) {
				continue;
			}
			x -= w[i];
			if (x < 0.0) {
				return Rarity.values()[i];
			}
		}
		return Rarity.COMMON;
	}

	/** 从该稀有度的池里取一件（roll01 决定取哪件）。 */
	public static Item rollItem(Rarity rarity, double roll01) {
		List<Item> pool = POOLS.get(rarity);
		int idx = (int) Math.floor(Math.max(0.0, Math.min(0.999999, roll01)) * pool.size());
		return pool.get(Math.max(0, Math.min(pool.size() - 1, idx)));
	}

	/** 该档物品池大小（给探针/文档核对）。 */
	public static int poolSize(Rarity rarity) {
		return POOLS.get(rarity).size();
	}
}
