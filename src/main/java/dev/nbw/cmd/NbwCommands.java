package dev.nbw.cmd;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

/**
 * /cww 命令的装配点（04 §10）。
 *
 * <p>本类只做一件事：把 {@code /cww} 根节点建出来，再按域把各子命令类挂上去。
 * 具体命令的实现与权限在各自的 {@code XxxCommands} 里；共用的 admin/tell/reply 在 {@link CmdSupport}。
 *
 * <p>三条约束（拆类后仍然成立）：
 * <ul>
 *   <li>管理命令走原版权限级别 2；报名/选队（join/quit）对所有玩家开放（01 §2 自主分队）；</li>
 *   <li>test 只校验不加载，reload 才装入内存；对局进行中禁止替换在用的 arena；</li>
 *   <li>任何失败只返回可读信息，绝不抛到崩服（03 §6.2）。</li>
 * </ul>
 *
 * <p><b>加新命令组时注意</b>：这里少一行 {@code XxxCommands.register(root)}，整组命令就会
 * 静默消失——2026-11-20 拆 MetaCommands 时漏掉 FacilityCommands 一行，五组命令全没了，
 * 最后由 craftsman_probe / core_probe 抓到。改动后请用 {@code /help cww} 核对整棵树。
 */
public final class NbwCommands {

	private NbwCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> register0(dispatcher));
	}

	private static void register0(CommandDispatcher<ServerCommandSource> dispatcher) {
		var root = CommandManager.literal("cww");

		RoundCommands.register(root);      // join/quit/phase/start/stop/abort/force/state/save/statefile
		MetaCommands.register(root);       // zone/metrics/event/stats/survivor/guard/supply/power/cosmetic/survival/loot/audit
		FacilityCommands.register(root);   // core/ballista/catapult/ram/raftcore
		ArenaCommands.register(root);      // arena/reset/format/lobby
		AnnounceCommands.register(root);   // announce
		RaftCommands.register(root);       // raft/sail/wind/remote
		RescueCommands.register(root);     // rescue/rope/grapnel
		ProbeCommands.register(root);      // net/build
		WeaponCommands.register(root);     // weapon

		dispatcher.register(root);
	}

}
