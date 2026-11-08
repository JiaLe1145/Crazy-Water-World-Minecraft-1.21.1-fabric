package dev.nbw.content;

import dev.nbw.NBW;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 我们的方块与方块实体注册（M2-4）。
 *
 * <p>注册写法照旧仓库 {@code SpikeContent}（那套在 1.21.1 + Yarn build.3 下编译并跑通过），
 * 不照教程、不照 Mojmap 名——本项目用 Yarn，抄错过 4 次映射名之后已经定死规矩。
 */
public final class NbwContent {

	public static final String RAFT_CORE = "raft_core";
	public static final String SAIL = "sail";
	public static final String OAR = "oar";
	public static final String REMOTE = "remote";
	public static final String ROPE = "rope";
	public static final String GRAPNEL = "grapnel";
	public static final String FRESH_WATER = "fresh_water";
	public static final String SEAWATER_FILTER = "seawater_filter";
	public static final String MEDICAL_KIT = "medical_kit";

	public static RaftCoreBlock raftCore;
	public static SailBlock sail;
	public static OarBlock oar;
	public static Item remote;
	public static Item rope;
	public static Item grapnel;
	public static Item freshWater;
	public static SeawaterFilterBlock seawaterFilter;
	public static MedicalKitBlock medicalKit;
	public static BlockEntityType<RaftCoreBlockEntity> raftCoreEntity;

	private NbwContent() {
	}

	public static void register() {
		raftCore = Registry.register(Registries.BLOCK, Identifier.of(NBW.MOD_ID, RAFT_CORE),
				new RaftCoreBlock(AbstractBlock.Settings.create()
						.strength(8.0f, 12.0f)     // 炸不轻易掉：筏心是队伍命脉，不是随手拆的
						.requiresTool()
						.luminance(s -> s.get(RaftCoreBlock.WRECKAGE) == Boolean.TRUE ? 2 : 10)));
		Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, RAFT_CORE),
				new BlockItem(raftCore, new Item.Settings()));

		sail = Registry.register(Registries.BLOCK, Identifier.of(NBW.MOD_ID, SAIL),
				new SailBlock(AbstractBlock.Settings.create()
						.strength(1.5f).requiresTool().nonOpaque()));
		Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, SAIL),
				new BlockItem(sail, new Item.Settings()));

		// 桨台（问题③起只剩装饰）：推进功能已随遥控器删除，方块与物品注册保留（旧存档不炸），局内拿不到。
		oar = Registry.register(Registries.BLOCK, Identifier.of(NBW.MOD_ID, OAR),
				new OarBlock(AbstractBlock.Settings.create().strength(2.0f).requiresTool()));
		Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, OAR),
				new BlockItem(oar, new Item.Settings()));

		// 海水过滤器（M4-4，01 §10）：放在筏上右键运转，产出淡水。功率池本轮不做。
		seawaterFilter = Registry.register(Registries.BLOCK, Identifier.of(NBW.MOD_ID, SEAWATER_FILTER),
				new SeawaterFilterBlock(AbstractBlock.Settings.create().strength(2.5f).requiresTool()));
		Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, SEAWATER_FILTER),
				new BlockItem(seawaterFilter, new Item.Settings()));

		// 医疗箱（M4-4）：止血 + 接骨 + 治疗 5 HP，60s 冷却。
		medicalKit = Registry.register(Registries.BLOCK, Identifier.of(NBW.MOD_ID, MEDICAL_KIT),
				new MedicalKitBlock(AbstractBlock.Settings.create().strength(2.0f).requiresTool()));
		Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, MEDICAL_KIT),
				new BlockItem(medicalKit, new Item.Settings()));

		// 遥控器（问题③）：每筏一个，拿在手上右键进入/退出移动模式。不是武器、不是方块，纯物品。
		remote = Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, REMOTE),
				new Item(new Item.Settings().maxCount(1)));

		// 绳索 / 钩竿（09 §2.3 控制工具）：右键使用，绳索拉落水队友、钩竿拉敌方。
		rope = Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, ROPE),
				new ToolItem(false, new Item.Settings().maxCount(1)));
		grapnel = Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, GRAPNEL),
				new ToolItem(true, new Item.Settings().maxCount(1)));

		// 淡水（M4-2）：右键喝一口，水分 +40；从补给箱里出。
		freshWater = Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, FRESH_WATER),
				new FreshWaterItem(new Item.Settings().maxCount(16)));

		raftCoreEntity = Registry.register(Registries.BLOCK_ENTITY_TYPE,
				Identifier.of(NBW.MOD_ID, RAFT_CORE),
				net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder
						.create(RaftCoreBlockEntity::new, raftCore).build());

		NBW.LOGGER.info("[content] registered block={} be={} sail={} oar={} remote={}",
				Registries.BLOCK.getId(raftCore), Registries.BLOCK_ENTITY_TYPE.getId(raftCoreEntity),
				Registries.BLOCK.getId(sail), Registries.BLOCK.getId(oar),
				Registries.ITEM.getId(remote));
	}

	/** 给 raft 适配层拿：注册后的方块实例。 */
	public static Block block() {
		return raftCore;
	}
}
