package dev.nbw.content;

import dev.nbw.rescue.RopeService;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * 控制/工具类物品（09 §2.3）：绳索（抛绳索救队友）与钩竿（拉敌方）。
 *
 * <p>右键使用 = 一次动作，服务端挑最近合格目标并拉人；判定全在 {@code domain/rescue/LineRule}，
 * 本类只把"用了道具"转发给 {@link RopeService}。不消耗耐久、不消耗物品（可反复用）。
 */
public final class ToolItem extends Item {

	/** true = 钩竿（拉敌方）；false = 绳索（拉落水队友）。 */
	private final boolean grapnel;

	public ToolItem(boolean grapnel, Settings settings) {
		super(settings);
		this.grapnel = grapnel;
	}

	public boolean grapnel() {
		return grapnel;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		if (!world.isClient() && user instanceof ServerPlayerEntity sp) {
			RopeService.cast(sp, grapnel);
		}
		return TypedActionResult.success(user.getStackInHand(hand));
	}
}
