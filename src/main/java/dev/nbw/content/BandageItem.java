package dev.nbw.content;

import dev.nbw.survival.SurvivalService;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * 绷带（M3 补，02 §3 / §7）：右键止血 + 骨折 −30s + 治疗 3 HP，消耗 1 个。
 *
 * <p>由医疗箱产出（02 §7：医疗箱 60s → 1 绷带）。
 */
public final class BandageItem extends Item {

	/** 骨折缩短秒数（02 §3：夹板 −30s）。 */
	public static final int FRACTURE_REDUCE_SEC = 30;
	/** 治疗量。 */
	public static final float HEAL = 3.0f;

	public BandageItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		if (!world.isClient() && user instanceof ServerPlayerEntity sp) {
			SurvivalService.cureBleeding(sp);
			SurvivalService.reduceFracture(sp, FRACTURE_REDUCE_SEC);
			SurvivalService.heal(sp, HEAL);
			sp.sendMessage(Text.literal("§a[cww] 绷带：止血 + 接骨 −30s"), true);
			ItemStack stack = sp.getStackInHand(hand);
			if (!sp.isCreative()) {
				stack.decrement(1);
			}
			return TypedActionResult.success(stack);
		}
		return TypedActionResult.success(user.getStackInHand(hand));
	}
}
