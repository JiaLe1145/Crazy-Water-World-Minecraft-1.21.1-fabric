package dev.nbw.content;

import dev.nbw.raft.RaftCoreService;
import dev.nbw.raft.RaftSinking;
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
 * 修理台（M4 补，02 §7）：1 电，修筏心 / 重置沉筏倒计时。
 *
 * <p>规格来自 02 §7 的表格：耗电 1、无固定周期、产出"修筏心 / 重置沉筏倒计时"、
 * 需要玩家或工匠操作。这里落成"右键消耗 1 电，把本队筏心从残骸恢复成完好并解除沉筏倒计时"：
 * <ul>
 *   <li>筏心本身已完好且满血 → 不消耗电、给可读提示；</li>
 *   <li>电量不足 → 拒绝（不是减半，修理台没有"半修"这个档）；</li>
 *   <li>冷却 60s，吃幸存者的生产岗位加成（有幸存者 −30% 周期）；</li>
 *   <li>工匠的"修复加速"另有一条路（残骸长按 8s→6s，见 {@code CraftsmanRule}），
 *       两者不冲突：修理台是"有电就能秒修"，长按是"没电也能修"。</li>
 * </ul>
 */
public final class RepairTableBlock extends Block {

	/** 冷却（秒）。与其它生产设施同口径。 */
	public static final int COOLDOWN_SECONDS = 60;

	private static final Map<BlockPos, Integer> lastUse = new HashMap<>();

	public RepairTableBlock(Settings settings) {
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
		var session = RoundManager.machine().session(sp.getUuid());
		if (session == null || session.teamIndex() < 0) {
			sp.sendMessage(Text.literal("§e[cww] 你还没有分队"), true);
			return ActionResult.SUCCESS;
		}
		int team = session.teamIndex();

		int now = sw.getServer().getTicks();
		int cooldownTicks = (int) Math.round(COOLDOWN_SECONDS * 20
				* dev.nbw.round.SurvivorService.productionSpeedFor(sp,
						dev.nbw.domain.survivor.SurvivorJob.CRAFTSMAN));   // 有工匠：−30% 周期
		Integer last = lastUse.get(pos);
		if (last != null && now - last < cooldownTicks) {
			int remain = (cooldownTicks - (now - last)) / 20 + 1;
			sp.sendMessage(Text.literal("§e[cww] 修理台还在冷却（还要 " + remain + "s）"), true);
			return ActionResult.SUCCESS;
		}

		boolean wrecked = RaftCoreService.isWreckage(team);
		double hp = RaftCoreService.hpOf(team);
		if (!wrecked && hp >= RaftCoreService.CORE_HP) {
			sp.sendMessage(Text.literal("§a[cww] 筏心完好，不需要修"), true);
			return ActionResult.SUCCESS;
		}
		if (!PowerService.tryConsume(team, PowerService.FACILITY_COST)) {
			sp.sendMessage(Text.literal("§c[cww] 电量不足（需要 1 电），先去发电机充能"), true);
			return ActionResult.SUCCESS;
		}

		lastUse.put(pos, now);
		RaftCoreService.markIntact(team);   // 残骸 → 完好；HP 回满
		RaftSinking.cancelByCoreRestore(team);   // 解除 30s 沉筏倒计时
		sp.sendMessage(Text.literal("§a[cww] 筏心已修复（残骸→完好，HP 回满，沉筏倒计时解除）"), true);
		return ActionResult.SUCCESS;
	}

	/** 局后清理（冷却表是局内状态）。 */
	public static void reset() {
		lastUse.clear();
	}
}
