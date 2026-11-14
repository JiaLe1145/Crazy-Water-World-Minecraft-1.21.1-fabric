package dev.nbw.content;

import dev.nbw.domain.survivor.SurvivorJob;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 产出型设施方块（M4-4，01 §10）：右键运转一次，给玩家一个固定物品，按方块位置冷却。
 *
 * <p>堆肥农场（产面包/营养原料）与厨台（产熟食）共用这条；海水过滤器因为要写进背包的是自己的
 * {@code nbw:fresh_water}，仍用它自己的类（同一个冷却模式）。
 */
public final class ItemProductBlock extends Block {

	private final Supplier<ItemStack> product;
	private final String productName;
	private final int cooldownSeconds;
	private final Map<BlockPos, Integer> lastUse = new HashMap<>();

	public ItemProductBlock(Supplier<ItemStack> product, String productName, int cooldownSeconds, Settings settings) {
		super(settings);
		this.product = product;
		this.productName = productName;
		this.cooldownSeconds = cooldownSeconds;
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
			BlockHitResult hit) {
		if (world.isClient()) {
			return ActionResult.SUCCESS;
		}
		if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) {
			return ActionResult.PASS;
		}
		int now = sw.getServer().getTicks();
		int cooldownTicks = (int) Math.round(cooldownSeconds * 20
				* dev.nbw.round.SurvivorService.productionSpeedFor(sp, SurvivorJob.COOK));   // 有厨师：−30% 周期
		Integer last = lastUse.get(pos);
		if (last != null && now - last < cooldownTicks) {
			int remain = (cooldownTicks - (now - last)) / 20 + 1;
			sp.sendMessage(Text.literal("§e[cww] 设施冷却中（还要 " + remain + "s）"), true);
			return ActionResult.SUCCESS;
		}
		lastUse.put(pos, now);
		ItemStack out = product.get();
		double mult = dev.nbw.round.PowerService.multiplierFor(sp);   // 功率不足减半
		int count = (int) Math.max(1, Math.round(out.getCount() * mult));
		out.setCount(count);
		sp.getInventory().insertStack(out);
		dev.nbw.round.SurvivorService.addProvisions(sp, count);
		sp.sendMessage(Text.literal("§a[cww] 产出 " + count + " × " + productName), true);
		return ActionResult.SUCCESS;
	}

	/** 局后清理（静态冷却表跨局不该残留）。 */
	public void resetCooldown() {
		lastUse.clear();
	}
}
