package dev.nbw.content;

import dev.nbw.survival.SurvivalService;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * 水弹枪（09 §2.3）：不造成伤害，清除着火与流血。
 *
 * <p>设计口径是"splash_potion 类"——朝队友打一发水，把火浇灭、把血止住。
 * 这里落成"右键：4 格内所有玩家（含自己）灭火 + 止血"，消耗 1 个。
 * 与绷带的区别：绷带是自用单目标 + 接骨 −30s；水弹枪是群体灭火/止血，救人更快但没有接骨。
 */
public final class WaterPistolItem extends Item {

	/** 水花半径（格）。 */
	public static final double RADIUS = 4.0;

	public WaterPistolItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		if (!world.isClient() && user instanceof ServerPlayerEntity sp && world instanceof ServerWorld sw) {
			int n = 0;
			for (ServerPlayerEntity p : sw.getPlayers()) {
				if (p.squaredDistanceTo(sp) <= RADIUS * RADIUS) {
					p.extinguish();                 // 灭火
					SurvivalService.cureBleeding(p); // 止血
					n++;
					if (!p.equals(sp)) {
						p.sendMessage(Text.literal("§b[cww] 你被水弹洗过：灭火 + 止血"), true);
					}
				}
			}
			sp.sendMessage(Text.literal("§b[cww] 水弹枪：灭火 + 止血（" + n + " 人）"), true);
			ItemStack stack = sp.getStackInHand(hand);
			if (!sp.isCreative()) {
				stack.decrement(1);
			}
			return TypedActionResult.success(stack);
		}
		return TypedActionResult.success(user.getStackInHand(hand));
	}
}
