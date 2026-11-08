package dev.nbw.content;

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
 * 医疗箱（M4-4 第二件，01 §10）：右键使用，止血 + 接骨 + 治疗 5 HP，60s 冷却。
 *
 * <p>这是"流血/骨折"的唯一本地解——没有它，被弯刀/铁头锤打中就只能硬扛到 1 血。
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
		Integer last = lastUse.get(pos);
		if (last != null && now - last < COOLDOWN_SECONDS * 20) {
			int remain = (COOLDOWN_SECONDS * 20 - (now - last)) / 20 + 1;
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
