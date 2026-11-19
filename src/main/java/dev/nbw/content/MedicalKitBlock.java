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
 * 医疗箱（M4-4 第二件，02 §7）：右键产 <b>1 个绷带</b>，60s 冷却（有医护幸存者 −30%）。
 *
 * <p>02 §7 的口径是"医疗箱 60s → 1 绷带"，所以它<b>不直接治疗</b>：
 * 止血/接骨/治疗 3 HP 都在 {@link BandageItem} 里，玩家要用绷带才生效。
 *
 * <p>为什么这么拆：直接治疗会把"医疗箱"变成免费回血点，谁路过都能蹭；
 * 产绷带则把治疗变成一份<b>可携带、可分配</b>的物资，才符合 01 §10 的"需要人手去生产"。
 */
public final class MedicalKitBlock extends Block {

	public static final int COOLDOWN_SECONDS = 60;

	private static final Map<BlockPos, Integer> lastUse = new HashMap<>();

	public MedicalKitBlock(Settings settings) {
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
				* dev.nbw.round.SurvivorService.productionSpeedFor(sp, SurvivorJob.MEDIC));   // 有医护：−30% 周期
		Integer last = lastUse.get(pos);
		if (last != null && now - last < cooldownTicks) {
			int remain = (cooldownTicks - (now - last)) / 20 + 1;
			sp.sendMessage(Text.literal("§e[cww] 医疗箱冷却中（还要 " + remain + "s）"), true);
			return ActionResult.SUCCESS;
		}
		lastUse.put(pos, now);
		// 02 §7：医疗箱 60s -> 1 绷带（绷带自己去止血/接骨，见 BandageItem）
		sp.getInventory().insertStack(new ItemStack(NbwContent.bandage));
		sp.sendMessage(Text.literal("§a[cww] 医疗箱产出 1 个绷带"), true);
		return ActionResult.SUCCESS;
	}

	/** 局后清理。 */
	public static void reset() {
		lastUse.clear();
	}
}
