package dev.nbw.content;

import dev.nbw.round.PowerService;
import dev.nbw.round.RoundManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
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
 * 电鳗发电机（M4 补，02 §7）：右键充能，给本队电量池 +5，30s 冷却。
 *
 * <p>功率池是队级数字（不拉电线），设施用它决定"满效/减半"。原型见 {@link PowerService}。
 */
public final class GeneratorBlock extends Block {

	public static final int COOLDOWN_SECONDS = 30;
	private static final Map<BlockPos, Integer> lastUse = new HashMap<>();

	public GeneratorBlock(Settings settings) {
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
			sp.sendMessage(Text.literal("§e[cww] 发电机冷却中（还要 " + remain + "s）"), true);
			return ActionResult.SUCCESS;
		}
		var session = RoundManager.machine().session(sp.getUuid());
		if (session == null || session.teamIndex() < 0) {
			return ActionResult.PASS;
		}
		lastUse.put(pos, now);
		PowerService.add(session.teamIndex(), PowerService.GENERATOR_CHARGE);
		sp.sendMessage(Text.literal("§e[cww] 发电机充能 +" + (int) PowerService.GENERATOR_CHARGE
				+ "（队 " + session.teamIndex() + " 电量 " + (int) PowerService.get(session.teamIndex()) + "）"), true);
		return ActionResult.SUCCESS;
	}

	/** 局后清理。 */
	public static void reset() {
		lastUse.clear();
	}
}
