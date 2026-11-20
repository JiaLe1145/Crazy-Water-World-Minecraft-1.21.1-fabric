package dev.nbw.content;

import dev.nbw.round.BallistaService;
import dev.nbw.round.RoundManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
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

	/**
	 * 装填弹药（09 §2.2）：主手拿 {@code nbw:ballista_bolt} 对炮位右键，一次装 1 发。
	 *
	 * <p>弹药记在<b>队</b>上而不是方块上：一门炮的弹药由全队共享，
	 * 造第二门炮不会把第一门的存量分走，拆炮也不会丢弹药。
	 */
	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) {
			return ActionResult.SUCCESS;
		}
		var session = RoundManager.machine().session(sp.getUuid());
		if (session == null || session.teamIndex() < 0) {
			return ActionResult.SUCCESS;
		}
		ItemStack held = sp.getMainHandStack();
		if (!held.isOf(NbwContent.ballistaBolt)) {
			sp.sendMessage(Text.literal("§7[cww] 手持弩炮弹右键可装填（本队现有 "
					+ BallistaService.ammoCount(session.teamIndex()) + " 发）"), true);
			return ActionResult.SUCCESS;
		}
		BallistaService.addAmmo(session.teamIndex(), 1);
		held.decrement(1);
		sp.sendMessage(Text.literal("§a[cww] 装填 1 发，本队弹药 "
				+ BallistaService.ammoCount(session.teamIndex()) + " 发"), true);
		return ActionResult.SUCCESS;
	}
}
