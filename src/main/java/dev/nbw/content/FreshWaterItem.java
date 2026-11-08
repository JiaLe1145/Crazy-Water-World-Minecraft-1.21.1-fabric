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
 * 淡水（M4-2）：右键喝一口，水分 +40（02 §2 的"淡水 +40"）。
 *
 * <p>海水必须过滤后才能喝——过滤器/净水器归后续生产链；现在淡水先从补给箱里出，
 * 让"水分 3.5/min"这条压力有解。
 */
public final class FreshWaterItem extends Item {

	/** 一口回复的水分（02 §2）。 */
	public static final double HYDRATION_GAIN = 40.0;

	public FreshWaterItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		if (!world.isClient() && user instanceof ServerPlayerEntity sp) {
			SurvivalService.recoverHydration(sp, HYDRATION_GAIN);
			sp.sendMessage(Text.literal("§b[cww] 喝下淡水：水分 +" + (int) HYDRATION_GAIN), true);
			// 显式扣一个：Carpet 假玩家的 use 不走原版消耗流程，靠 consume 返回值会漏扣
			ItemStack stack = sp.getStackInHand(hand);
			if (!sp.isCreative()) {
				stack.decrement(1);
			}
			return TypedActionResult.success(stack);
		}
		return TypedActionResult.success(user.getStackInHand(hand));
	}
}
