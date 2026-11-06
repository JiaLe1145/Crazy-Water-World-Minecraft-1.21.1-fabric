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

	public static RaftCoreBlock raftCore;
	public static SailBlock sail;
	public static OarBlock oar;
	public static BlockEntityType<RaftCoreBlockEntity> raftCoreEntity;
	public static BlockEntityType<OarBlockEntity> oarEntity;

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

		// 桨台硬度取 2.0：比帆（1.5）耐拆一点，但远不如筏心（8.0/抗爆 12.0）。
		// 它是"可以被打掉的动力设施"，不该变成第二个队伍命脉——命脉只有一条，那是已批准的设计。
		oar = Registry.register(Registries.BLOCK, Identifier.of(NBW.MOD_ID, OAR),
				new OarBlock(AbstractBlock.Settings.create().strength(2.0f).requiresTool()));
		Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, OAR),
				new BlockItem(oar, new Item.Settings()));

		raftCoreEntity = Registry.register(Registries.BLOCK_ENTITY_TYPE,
				Identifier.of(NBW.MOD_ID, RAFT_CORE),
				net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder
						.create(RaftCoreBlockEntity::new, raftCore).build());

		oarEntity = Registry.register(Registries.BLOCK_ENTITY_TYPE,
				Identifier.of(NBW.MOD_ID, OAR),
				net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder
						.create(OarBlockEntity::new, oar).build());

		NBW.LOGGER.info("[content] registered block={} be={} sail={} oar={} oarBe={}",
				Registries.BLOCK.getId(raftCore), Registries.BLOCK_ENTITY_TYPE.getId(raftCoreEntity),
				Registries.BLOCK.getId(sail), Registries.BLOCK.getId(oar),
				Registries.BLOCK_ENTITY_TYPE.getId(oarEntity));
	}

	/** 给 raft 适配层拿：注册后的方块实例。 */
	public static Block block() {
		return raftCore;
	}
}
