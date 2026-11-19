package dev.nbw.cmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nbw.NBW;
import dev.nbw.round.RoundManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 对局生命周期命令（从 {@link NbwCommands} 拆出来）：
 * 玩家侧 {@code join/quit/phase}，管理员侧 {@code start/stop/abort/force/state}，
 * 以及快照落盘 {@code save/statefile}。
 *
 * <p>这一组是所有命令里最常用、也最容易改错的：报名、开局、中止、恢复。
 * 单独成类后，改"开局参数怎么解析"不用再翻整个 1400 行文件。
 *
 * <p>拆分**不改命令树**：注册出来的路径与拆分前逐字一致。
 */
public final class RoundCommands {

	private RoundCommands() {
	}

	/** 挂到 {@code /cww} 根节点下：对局生命周期与快照。 */
	public static void register(LiteralArgumentBuilder<ServerCommandSource> root) {
		// ---- 所有玩家：自主分队
		root.then(CommandManager.literal("join")
				.executes(ctx -> join(ctx, null))
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(ctx -> join(ctx, IntegerArgumentType.getInteger(ctx, "team")))));
		root.then(CommandManager.literal("quit").executes(RoundCommands::quit));
		root.then(CommandManager.literal("phase").executes(RoundCommands::phase));

		// ---- 管理员（权限 2）
		root.then(CmdSupport.admin(CommandManager.literal("start")
				.executes(ctx -> start(ctx, null, null))
				.then(CommandManager.argument("format", StringArgumentType.word())
						.executes(ctx -> start(ctx, StringArgumentType.getString(ctx, "format"), null))
						.then(CommandManager.argument("arena", StringArgumentType.word())
								.executes(ctx -> start(ctx,
										StringArgumentType.getString(ctx, "format"),
										StringArgumentType.getString(ctx, "arena")))))));
		root.then(CmdSupport.admin(CommandManager.literal("stop")
				.executes(ctx -> CmdSupport.reply(ctx, RoundManager.stop(), "已终止本局（走结算）"))));
		root.then(CmdSupport.admin(CommandManager.literal("abort")
				.executes(ctx -> CmdSupport.reply(ctx, RoundManager.abort(), "已紧急中止本局（不判胜负）"))));
		root.then(CmdSupport.admin(CommandManager.literal("force")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
								.executes(RoundCommands::force)))));
		root.then(CmdSupport.admin(CommandManager.literal("state").executes(RoundCommands::state)));

		// ---- 落盘与重启恢复（Gate G1）
		root.then(CmdSupport.admin(CommandManager.literal("save").executes(RoundCommands::saveNow)));
		root.then(CmdSupport.admin(CommandManager.literal("statefile").executes(RoundCommands::stateFile)));
	}

	private static int start(CommandContext<ServerCommandSource> ctx, String format, String arena)
			throws CommandSyntaxException {
		String err = RoundManager.start(format, arena);
		return CmdSupport.reply(ctx, err, "第 " + RoundManager.phase() + " 阶段已启动（format=" + nz(format)
				+ " arena=" + nz(arena) + "）");
	}

	private static int join(CommandContext<ServerCommandSource> ctx, Integer team) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		String err = RoundManager.join(player, team);
		if (err != null) {
			ctx.getSource().sendError(Text.literal("[cww] " + err));
			return 0;
		}
		var session = RoundManager.phase();
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] 已报名（阶段 " + session + "）"
				+ (team == null ? "，进队顺序按报名先后" : "，队伍 " + team)), false);
		return 1;
	}

	private static int quit(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		String err = RoundManager.quit(player);
		if (err != null) {
			ctx.getSource().sendError(Text.literal("[cww] " + err));
			return 0;
		}
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] 已取消报名"), false);
		return 1;
	}

	private static int phase(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] 阶段=" + RoundManager.phase()
				+ " 规格=" + RoundManager.config().teamCount() + "x" + RoundManager.config().teamSize()
				+ " 下限=" + RoundManager.config().minPlayers()), false);
		return 1;
	}

	private static int force(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		String target = StringArgumentType.getString(ctx, "player");
		int team = IntegerArgumentType.getInteger(ctx, "team");
		String err = RoundManager.force(target, team);
		return CmdSupport.reply(ctx, err, "已将 " + target + " 分入队伍 " + team);
	}

	private static int state(CommandContext<ServerCommandSource> ctx) {
		String dump = RoundManager.state();
		for (String line : dump.split("\n")) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + line), false);
		}
		return 1;
	}

	private static int saveNow(CommandContext<ServerCommandSource> ctx) {
		String msg = RoundManager.saveNow();
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + msg), true);
		NBW.LOGGER.info("[cmd] {} (source={})", msg, ctx.getSource().getName());
		return 1;
	}

	/** 读一遍盘上的快照并原样报告——排查"重启到底会不会恢复"时，这比看代码有用。 */
	private static int stateFile(CommandContext<ServerCommandSource> ctx) {
		String msg = RoundManager.describeStateFile();
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + msg), false);
		return 1;
	}

	private static String nz(String s) {
		return s == null ? "默认" : s;
	}
}
