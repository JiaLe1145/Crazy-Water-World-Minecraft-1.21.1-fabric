package dev.nbw.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nbw.NBW;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;

/**
 * /cww 命令与权限（04 §10）。三条约束：
 * - 管理命令走原版权限级别 2；报名/选队（join/quit）对所有玩家开放（01 §2 自主分队）；
 * - test 只校验不加载，reload 才装入内存；对局进行中禁止替换在用的 arena；
 * - 任何失败只返回可读信息，绝不抛到崩服（03 §6.2）。
 */
public final class NbwCommands {

	private NbwCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> register0(dispatcher));
	}

	private static void register0(CommandDispatcher<ServerCommandSource> dispatcher) {
		var root = CommandManager.literal("cww");

		// ---- 对局生命周期（join/quit/phase/start/stop/abort/force/state/save）：已拆到 RoundCommands
		RoundCommands.register(root);
		// 读数与元系统（zone/metrics/event/stats/survivor/supply/guard/power/cosmetic/survival/loot/audit）
		MetaCommands.register(root);

		// 筏心与设施武器（core/ballista/catapult/ram/raftcore）
		// 注意：拆 MetaCommands 时这行曾被误删，导致整组命令消失（2026-11-20 craftsman_probe 抓到）
		FacilityCommands.register(root);

		// ---- 地图 / 赛制 / 大厅 / 模板重置（已拆到 ArenaCommands，命令树保持不变）
		ArenaCommands.register(root);

		// ---- 公告（P1，参照 RicordRailExpress）：post / short / list / del / clear
		AnnounceCommands.register(root);

		// ---- 筏与航行（已拆到 RaftCommands：raft / sail / wind / remote）
		RaftCommands.register(root);

		// ---- 筏心与残骸修复（01 §4.3.2 A2 / A2-v2）：已并入 FacilityCommands

		// ---- 风 / 遥控器 / 帆：已并入 RaftCommands，此处不再重复注册

		// ---- 落水与救援（M2-8 / 01 §8）：已拆到 RescueCommands，命令树保持不变
		RescueCommands.register(root);

		// ---- 探针（net/build）：已拆到 ProbeCommands，命令树保持不变
		ProbeCommands.register(root);

		// ---- 武器（09 §2.1）：已拆到 WeaponCommands，命令树保持不变
		WeaponCommands.register(root);

		// ---- 落盘与重启恢复（Gate G1）：已并入 RoundCommands

		dispatcher.register(root);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> admin(
			com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> node) {
		return node.requires(source -> source.hasPermissionLevel(2));
	}

	/** 沉筏命令一律"成功也回话、失败也回话"，不抛异常——运维需要的是可读结果不是堆栈。 */
	private static int tell(CommandContext<ServerCommandSource> ctx, String message) {
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + message), true);
		NBW.LOGGER.info("[cmd] {} (source={})", message, ctx.getSource().getName());
		return 1;
	}

	private static int reply(CommandContext<ServerCommandSource> ctx, String error, String okMessage) throws CommandSyntaxException {
		if (error == null) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + okMessage), true);
			NBW.LOGGER.info("[cmd] {} (source={})", okMessage, ctx.getSource().getName());
			return 1;
		}
		ctx.getSource().sendError(Text.literal("[cww] " + error));
		NBW.LOGGER.warn("[cmd] rejected: {} (source={})", error, ctx.getSource().getName());
		return 0;
	}

}
