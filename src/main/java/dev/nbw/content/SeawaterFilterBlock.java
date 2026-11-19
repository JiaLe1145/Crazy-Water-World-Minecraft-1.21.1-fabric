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

/**
 * 海水过滤器（M4-4，02 §7）：放在筏上，右键运转一次，产出 2 份淡水（{@code nbw:fresh_water}）。
 *
 * <p>这是"水分 3.5/min"的唯一本地解——补给箱的淡水有限，过滤器让队伍能持续生产。
 * 冷却按<b>方块位置</b>记（{@code lastUse}），同一台过滤器 30s 出一轮，逼玩家安排人手看管。
 *
 * <p><b>2026-11-19 修正</b>：产出按 02 §7 的"30s → 2 淡水"（此前是 1 份）；
 * 功率池已落地，所以这里走 {@code PowerService.multiplierFor}——没电只产一半（1 份）。
 */
public final class SeawaterFilterBlock extends Block {

	/** 一台过滤器两次产出的间隔（秒）。02 §7：30s → 2 淡水。 */
	public static final int COOLDOWN_SECONDS = 30;

	private static final Map<BlockPos, Integer> lastUse = new HashMap<>();

	public SeawaterFilterBlock(Settings settings) {
		super(settings);
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
		int cooldownTicks = (int) Math.round(COOLDOWN_SECONDS * 20
				* dev.nbw.round.SurvivorService.productionSpeedFor(sp, SurvivorJob.SAILOR));   // 有水手：−30% 周期
		Integer last = lastUse.get(pos);
		if (last != null && now - last < cooldownTicks) {
			int remain = (cooldownTicks - (now - last)) / 20 + 1;
			sp.sendMessage(Text.literal("§e[cww] 过滤器还在运转（还要 " + remain + "s）"), true);
			return ActionResult.SUCCESS;
		}
		lastUse.put(pos, now);
		// 02 §7：30s -> 2 淡水；功率不足时效率减半（1 份）
		double mult = dev.nbw.round.PowerService.multiplierFor(sp);
		int count = (int) Math.max(1, Math.round(2 * mult));
		sp.getInventory().insertStack(new ItemStack(NbwContent.freshWater, count));
		dev.nbw.round.SurvivorService.addProvisions(sp, count);
		sp.sendMessage(Text.literal("§b[cww] 过滤器产出 " + count + " 份淡水"), true);
		return ActionResult.SUCCESS;
	}

	/** 局后清理：冷却表是局内的（下一局同一格不该继承上一局的冷却）。 */
	public static void reset() {
		lastUse.clear();
	}
}
