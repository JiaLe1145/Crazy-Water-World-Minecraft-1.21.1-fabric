package dev.nbw.content;

import dev.nbw.round.BallistaService;
import dev.nbw.round.RoundManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 弩炮方块（09 §2.2 设施武器）：放上筏后由 {@link BallistaService} 每 tick 驱动，
 * 自动打 32 格内最近的敌方玩家。
 *
 * <p>放置时把"归属队伍"记进服务；被拆/被替换时注销。归属取<b>放置者所在队</b>——
 * 因为 {@code BuildService} 已经保证了"只有本队能改本队筏"，所以放置者队伍就是筏的队。
 */
public final class BallistaBlock extends Block {

	public BallistaBlock(Settings settings) {
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
			BallistaService.register(pos, session.teamIndex());
		}
	}

	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		super.onStateReplaced(state, world, pos, newState, moved);
		if (!world.isClient() && !state.isOf(newState.getBlock())) {
			BallistaService.unregister(pos);
		}
	}
}
