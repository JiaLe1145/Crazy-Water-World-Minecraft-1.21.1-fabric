package dev.nbw.content;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.math.Direction;

/**
 * 桨台（遗留装饰方块，问题③：推进功能已删除，只保留注册与外观）。
 *
 * <p>2026-11-07 起木筏操控改为"每筏一个遥控器"，原来的"多人站在桨台持续划桨"整套删掉
 * （{@code OarService}、桨台方块实体都已移除）。按用户裁定<b>不删物品注册</b>——
 * 方块与物品仍在注册表里（旧世界/旧存档不炸），但不再有任何推进交互，局内也拿不到。
 * 保留 {@code FACING} 只是为了模型朝向（4 个水平方向），不承载任何玩法。
 */
public class OarBlock extends Block {

	public static final DirectionProperty FACING = DirectionProperty.of("facing", Direction.Type.HORIZONTAL);

	public OarBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}
}
