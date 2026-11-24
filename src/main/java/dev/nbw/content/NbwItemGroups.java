package dev.nbw.content;

import dev.nbw.NBW;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 自定义创造模式物品栏。
 *
 * <ul>
 *   <li><b>工具与消耗品</b>（{@code nbw:tools}）：5 件近战 + 4 件远程 + 3 件设施武器
 *       + 弩炮弹 + 遥控器/绳索/钩竿/水弹枪 + 淡水/绷带。</li>
 *   <li><b>水世界方块</b>（{@code nbw:blocks}）：筏心/帆/桨台/生产设施 +
 *       弩炮/投石机/撞角（它们既是方块也是武器，因此两页都会出现）。</li>
 * </ul>
 *
 * <p>设施武器在“工具与消耗品”里按武器归类，在“水世界方块”里按方块归类；
 * 原版创造栏也允许多个页签包含同一物品（例如建筑方块/功能方块）。
 */
public final class NbwItemGroups {

	public static ItemGroup TOOLS_AND_CONSUMABLES;
	public static ItemGroup BLOCKS;

	private NbwItemGroups() {
	}

	/** 由 {@code NBW.onInitialize} 在内容/武器注册之后调用一次。 */
	public static void register() {
		TOOLS_AND_CONSUMABLES = Registry.register(Registries.ITEM_GROUP,
				Identifier.of(NBW.MOD_ID, "tools"),
				FabricItemGroup.builder()
						.displayName(Text.translatable("itemGroup.nbw.tools"))
						.icon(() -> new ItemStack(iconCutlass()))
						.entries((context, entries) -> {
							// 5 件近战（paddle / cutlass / pike / warhammer / twin_blade）
							for (WeaponItem weapon : NbwWeapons.items()) {
								entries.add(new ItemStack(weapon));
							}
							// 4 件远程
							entries.add(new ItemStack(NbwContent.handCrossbow));
							entries.add(new ItemStack(NbwContent.harpoonGun));
							entries.add(new ItemStack(NbwContent.musket));
							entries.add(new ItemStack(NbwContent.javelin));
							// 3 件设施武器（方块物品，按武器归类）
							entries.add(new ItemStack(NbwContent.ballista.asItem()));
							entries.add(new ItemStack(NbwContent.catapult.asItem()));
							entries.add(new ItemStack(NbwContent.ram.asItem()));
							// 弹药
							entries.add(new ItemStack(NbwContent.ballistaBolt));
							// 工具
							entries.add(new ItemStack(NbwContent.remote));
							entries.add(new ItemStack(NbwContent.rope));
							entries.add(new ItemStack(NbwContent.grapnel));
							entries.add(new ItemStack(NbwContent.waterPistol));
							// 消耗品
							entries.add(new ItemStack(NbwContent.freshWater));
							entries.add(new ItemStack(NbwContent.bandage));
						})
						.build());

		BLOCKS = Registry.register(Registries.ITEM_GROUP,
				Identifier.of(NBW.MOD_ID, "blocks"),
				FabricItemGroup.builder()
						.displayName(Text.translatable("itemGroup.nbw.blocks"))
						.icon(() -> new ItemStack(iconBlock()))
						.entries((context, entries) -> {
							entries.add(new ItemStack(NbwContent.raftCore.asItem()));
							entries.add(new ItemStack(NbwContent.oar.asItem()));
							entries.add(new ItemStack(NbwContent.seawaterFilter.asItem()));
							entries.add(new ItemStack(NbwContent.medicalKit.asItem()));
							entries.add(new ItemStack(NbwContent.compostFarm.asItem()));
							entries.add(new ItemStack(NbwContent.kitchen.asItem()));
							entries.add(new ItemStack(NbwContent.generator.asItem()));
							entries.add(new ItemStack(NbwContent.repairTable.asItem()));
							// 设施武器同时也是方块，按方块归类重复放一次
							entries.add(new ItemStack(NbwContent.ballista.asItem()));
							entries.add(new ItemStack(NbwContent.catapult.asItem()));
							entries.add(new ItemStack(NbwContent.ram.asItem()));
						})
						.build());

		NBW.LOGGER.info("[content] 创造物品栏已注册：工具与消耗品（武器 {} + 远程 4 + 设施武器 3 + 弹药 1 + 工具/消耗品 6）"
						+ "；水世界方块（12 个方块物品）", NbwWeapons.items().size());
	}

	/** 工具页图标：优先砍刀，缺失时退回木桨。 */
	private static Item iconCutlass() {
		WeaponItem cutlass = NbwWeapons.item("cutlass");
		if (cutlass != null) {
			return cutlass;
		}
		WeaponItem paddle = NbwWeapons.item("paddle");
		return paddle != null ? paddle : net.minecraft.item.Items.STONE;
	}

	/** 方块页图标：优先筏心，缺失时退回石头。 */
	private static Item iconBlock() {
		return NbwContent.raftCore == null ? net.minecraft.item.Items.STONE : NbwContent.raftCore.asItem();
	}
}
