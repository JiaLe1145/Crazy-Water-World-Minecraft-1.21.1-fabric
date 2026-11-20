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
					new Item("nbw:fresh_water", 1, 2),
					// 02 §4 / 09 §2.3：绳索与钩竿是 Common 控制工具，救援线全靠它们
					new Item("nbw:rope", 1, 2),
					new Item("nbw:grapnel", 1, 1)),
			Rarity.RARE, List.of(
					new Item("minecraft:iron_ingot", 1, 2),
					new Item("minecraft:coal", 2, 6),
					new Item("minecraft:cooked_cod", 1, 1),
					new Item("nbw:bandage", 1, 2),
					new Item("nbw:water_pistol", 1, 2),
					new Item("nbw:cutlass", 1, 1),
					new Item("nbw:hand_crossbow", 1, 1),
					new Item("nbw:javelin", 1, 1),
					// 09 §2.1：长矛是首发 5 件近战里唯一的 Rare，此前整条链漏了它
					new Item("nbw:pike", 1, 1)),
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
	 * 设施/工具池（2026-11-21 补，修复 M6 全量测试查出的 Q4 获取链缺口）。
	 *
	 * <p><b>为什么单开一个池而不是塞进稀有度池：</b>稀有度池的每一档里只有 4–5 件武器，
	 * 往里加十几件设施会把武器掉率摊薄到原来的 1/3（TTK 与"赤手空拳打不到人"都会跟着变），
	 * 那是一次没人批准的配平改动。设施改为<b>按件</b>独立掷骰，武器池一件不动。
	 *
	 * <p><b>历史证据</b>：{@code 809b70a} 把补给箱从原版 loot table 换成稀有度池时移除了
	 * {@code setLootTable}，之后 M4 的设施只加进了那张已经没人读的表里，
	 * 于是 pike / 绳 / 钩竿 / 发电机 / 修理台 / 堆肥 / 厨台 / 帆 / 三件设施武器
	 * 在真实对局里一件都拿不到（详见 {@code docs/dev/M6-Q-RESOURCES.md} §6.1）。
	 *
	 * <p>分两档是为了不让"投石机"跟"一根绳"同概率：tier1 只出基础件，
	 * tier2 起才出进阶件（帆/发电机/三件设施武器）。
	 */
	private static final List<Item> FACILITY_BASIC = List.of(
			new Item("nbw:rope", 1, 2),
			new Item("nbw:grapnel", 1, 1),
			new Item("nbw:repair_table", 1, 1),
			new Item("nbw:compost_farm", 1, 1),
			new Item("nbw:kitchen", 1, 1));

	private static final List<Item> FACILITY_ADVANCED = List.of(
			new Item("nbw:sail", 1, 2),
			new Item("nbw:generator", 1, 1),
			new Item("nbw:ram", 1, 1),
			new Item("nbw:ballista", 1, 1),
			new Item("nbw:catapult", 1, 1));

	/** 单件补给改从设施池抽的概率。0.22 让"翻到一件设施"是可期的，但不会淹没武器与食物。 */
	public static final double FACILITY_CHANCE = 0.22;

	/**
	 * 从设施池取一件（{@code roll01} 决定取哪件）。
	 *
	 * @param tier 圈层 tier：&lt;2 只出基础件，&gt;=2 出进阶件
	 */
	public static Item rollFacility(int tier, double roll01) {
		List<Item> pool = tier >= 2 ? FACILITY_ADVANCED : FACILITY_BASIC;
		int idx = (int) Math.floor(Math.max(0.0, Math.min(0.999999, roll01)) * pool.size());
		return pool.get(Math.max(0, Math.min(pool.size() - 1, idx)));
	}

	/** 设施池大小（给探针/文档核对）。 */
	public static int facilityPoolSize(int tier) {
		return (tier >= 2 ? FACILITY_ADVANCED : FACILITY_BASIC).size();
	}

	/**
	 * 这件要不要改从设施池抽。
	 *
	 * <p>单独抽成公开方法是为了让探针能算出"多少次补给里应该出现几件设施"，
	 * 并且手算的期望值必须与这里一致（改常数就会红）。
	 */
	public static boolean facilityRoll(double roll01) {
		return roll01 < FACILITY_CHANCE;
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
