package dev.nbw.content;

import dev.nbw.round.CatapultService;
import dev.nbw.round.RoundManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 投石机方块（09 §2.2 设施武器）：放上筏后由 {@link CatapultService} 每 5 秒对
 * 40 格内最近的敌方筏心打 24 HP。放置登记归属、被拆注销，与弩炮同范式。
 */
public final class CatapultBlock extends Block {

	public CatapultBlock(Settings settings) {
		super(settings);
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
