package dev.nbw.content;

import dev.nbw.round.CatapultService;
import dev.nbw.round.RoundManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * 投石机方块（09 §2.2 设施武器）：放上筏后由 {@link CatapultService} 每 5 秒对
 * 40 格内最近的敌方筏心打 24 HP。放置登记归属、被拆注销，与弩炮同范式。
 */
public final class CatapultBlock extends Block {

	/**
	 * 2×2 碰撞箱（09 §2.2 投石机按现实器械建模，视觉占 2×2 格）。
	 *
	 * <p>模型 X/Z 以方块原点为中心铺开（约 -13..22），碰撞与描边用完整的 -16..16，
	 * 玩家不能再从空隙穿过机身。Y 取 0..32 覆盖模型的 31.2 高度。
	 */
	private static final VoxelShape SHAPE = Block.createCuboidShape(-16.0, 0.0, -16.0, 16.0, 32.0, 16.0);

	public CatapultBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world.isClient() || !(placer instanceof ServerPlayerEntity sp)) {
			return;
		}
		var session = RoundManager.machine().session(sp.getUuid());
		if (session != null && session.teamIndex() >= 0) {
			CatapultService.register(pos, session.teamIndex());
		}
	}

	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		super.onStateReplaced(state, world, pos, newState, moved);
		if (!world.isClient() && !state.isOf(newState.getBlock())) {
			CatapultService.unregister(pos);
		}
	}
}
