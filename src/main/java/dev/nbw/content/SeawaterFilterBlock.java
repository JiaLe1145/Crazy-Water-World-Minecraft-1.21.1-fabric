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
 * 海水过滤器（M4-4，01 §10）：放在筏上，右键运转一次，产出 1 份淡水（{@code nbw:fresh_water}）。
 *
 * <p>这是"水分 3.5/min"的唯一本地解——补给箱的淡水有限，过滤器让队伍能持续生产。
 * 冷却按<b>方块位置</b>记（{@code lastUse}），同一台过滤器 30s 出一份，逼玩家安排人手看管。
 * （01 §10 的"功率池/耗电"本轮不做，先落地"过滤器 → 淡水"这条最小闭环。）
 */
public final class SeawaterFilterBlock extends Block {

	/** 一台过滤器两次产出的间隔（秒）。01 §10：一次运转 30–45s 出 2–3 份，这里取 30s 一份的简化档。 */
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
		Integer last = lastUse.get(pos);
		if (last != null && now - last < COOLDOWN_SECONDS * 20) {
			int remain = (COOLDOWN_SECONDS * 20 - (now - last)) / 20 + 1;
			sp.sendMessage(Text.literal("§e[cww] 过滤器还在运转（还要 " + remain + "s）"), true);
			return ActionResult.SUCCESS;
		}
		lastUse.put(pos, now);
		sp.getInventory().insertStack(new ItemStack(NbwContent.freshWater));
		sp.sendMessage(Text.literal("§b[cww] 过滤器产出 1 份淡水"), true);
		return ActionResult.SUCCESS;
	}

	/** 局后清理：冷却表是局内的（下一局同一格不该继承上一局的冷却）。 */
	public static void reset() {
		lastUse.clear();
	}
}
