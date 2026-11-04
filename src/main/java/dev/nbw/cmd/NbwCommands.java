package dev.nbw.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.cfg.ConfigService;
import dev.nbw.domain.ZoneModel;
import dev.nbw.round.RoundManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

		// ---- 所有玩家：自主分队
		root.then(CommandManager.literal("join")
				.executes(ctx -> join(ctx, null))
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(ctx -> join(ctx, IntegerArgumentType.getInteger(ctx, "team")))));
		root.then(CommandManager.literal("quit").executes(NbwCommands::quit));
		root.then(CommandManager.literal("phase").executes(NbwCommands::phase));

		// ---- 管理员（权限 2）
		root.then(admin(CommandManager.literal("start")
				.executes(ctx -> start(ctx, null, null))
				.then(CommandManager.argument("format", StringArgumentType.word())
						.executes(ctx -> start(ctx, StringArgumentType.getString(ctx, "format"), null))
						.then(CommandManager.argument("arena", StringArgumentType.word())
								.executes(ctx -> start(ctx,
										StringArgumentType.getString(ctx, "format"),
										StringArgumentType.getString(ctx, "arena")))))));
		root.then(admin(CommandManager.literal("stop").executes(ctx -> reply(ctx, RoundManager.stop(), "已终止本局（走结算）"))));
		root.then(admin(CommandManager.literal("abort").executes(ctx -> reply(ctx, RoundManager.abort(), "已紧急中止本局（不判胜负）"))));
		root.then(admin(CommandManager.literal("force")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
								.executes(NbwCommands::force)))));
		root.then(admin(CommandManager.literal("state").executes(NbwCommands::state)));
		root.then(admin(CommandManager.literal("audit")
				.executes(ctx -> audit(ctx, 20))
				.then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 200))
						.executes(ctx -> audit(ctx, IntegerArgumentType.getInteger(ctx, "limit"))))));

		var arena = admin(CommandManager.literal("arena"));
		arena.then(CommandManager.literal("list").executes(NbwCommands::listArenas));
		arena.then(CommandManager.literal("test").then(CommandManager.argument("id", StringArgumentType.word())
				.executes(ctx -> testArena(ctx, StringArgumentType.getString(ctx, "id"), false))));
		arena.then(CommandManager.literal("reload").then(CommandManager.argument("id", StringArgumentType.word())
				.executes(ctx -> testArena(ctx, StringArgumentType.getString(ctx, "id"), true))));
		root.then(arena);

		var format = admin(CommandManager.literal("format"));
		format.then(CommandManager.literal("test").then(CommandManager.argument("id", StringArgumentType.word())
				.executes(NbwCommands::testFormat)));
		root.then(format);

		var lobby = admin(CommandManager.literal("lobby"));
		lobby.then(CommandManager.literal("test").executes(ctx -> testLobby(ctx, false)));
		lobby.then(CommandManager.literal("reload").executes(ctx -> testLobby(ctx, true)));
		root.then(lobby);

		dispatcher.register(root);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> admin(
			com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> node) {
		return node.requires(source -> source.hasPermissionLevel(2));
	}

	// ---------------------------------------------------------------- 对局命令

	private static int start(CommandContext<ServerCommandSource> ctx, String format, String arena) throws CommandSyntaxException {
		String err = RoundManager.start(format, arena);
		return reply(ctx, err, "第 " + RoundManager.phase() + " 阶段已启动（format=" + nz(format) + " arena=" + nz(arena) + "）");
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

	private static String nz(String s) {
		return s == null ? "默认" : s;
	}

	private static int join(CommandContext<ServerCommandSource> ctx, Integer team) throws CommandSyntaxException {
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		String err = RoundManager.join(player, team);
		if (err != null) {
			ctx.getSource().sendError(Text.literal("[cww] " + err));
			return 0;
		}
		var session = dev.nbw.round.RoundManager.phase();
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
		return reply(ctx, err, "已将 " + target + " 分入队伍 " + team);
	}

	private static int state(CommandContext<ServerCommandSource> ctx) {
		String dump = RoundManager.state();
		for (String line : dump.split("\n")) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + line), false);
		}
		return 1;
	}

	private static int audit(CommandContext<ServerCommandSource> ctx, int limit) {
		String log = RoundManager.auditLog(limit);
		if (log.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] 审计日志为空（还没走过阶段转移）"), false);
			return 0;
		}
		for (String line : log.split("\n")) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + line), false);
		}
		return 1;
	}

	// ---------------------------------------------------------------- 配置命令

	private static Path worldRoot() {
		return RoundManager.worldRoot();
	}

	private static int listArenas(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		Path dir = worldRoot().resolve("cww").resolve("arenas");
		List<String> ids = new ArrayList<>();
		if (java.nio.file.Files.isDirectory(dir)) {
			try (Stream<Path> s = java.nio.file.Files.list(dir)) {
				s.filter(p -> p.toString().endsWith(".json"))
						.forEach(p -> ids.add(p.getFileName().toString().replace(".json", "")));
			} catch (IOException e) {
				ctx.getSource().sendFeedback(() -> Text.literal("[cww] 读取 arenas 目录失败：" + e.getMessage()), false);
				return 0;
			}
		}
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] arenas(" + ids.size() + "): " + String.join(", ", ids)), false);
		return ids.size();
	}

	private static int testArena(CommandContext<ServerCommandSource> ctx, String id, boolean reload) {
		if (reload) {
			String blocker = RoundManager.arenaSwapBlocker();
			if (blocker != null) {
				ctx.getSource().sendError(Text.literal("[cww] " + blocker));
				return 0;
			}
		}
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot(), id, reload);
		if (loaded.ok()) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] arena '" + id + "' 校验通过；环带 "
					+ loaded.zone().rings().size() + " 段，初始半径 " + (int) loaded.zone().baseRadius()
					+ (reload ? "，已装入内存" : "")), true);
			NBW.LOGGER.info("[cww] arena '{}' validated (reload={})", id, reload);
			return 1;
		}
		String detail = String.join(" | ", loaded.errors());
		ctx.getSource().sendError(Text.literal("[cww] arena '" + id + "' 被拒绝（不自动纠正）：" + detail));
		NBW.LOGGER.warn("[cww] arena '{}' rejected: {}", id, detail);
		return 0;
	}

	/** /cww lobby test|reload：只校验 world/cww/lobby.json（03 §5）；reload 失败才归档。 */
	private static int testLobby(CommandContext<ServerCommandSource> ctx, boolean reload) {
		ConfigService.LobbyLoaded loaded = ConfigService.loadLobby(worldRoot(), reload);
		if (loaded.ok()) {
			ConfigModels.Lobby l = loaded.lobby();
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] lobby 校验通过；出生点 ("
					+ l.spawn.x + "," + l.spawn.y + "," + l.spawn.z + ") pvp=" + (l.rules == null ? "未配" : l.rules.pvp)
					+ " 分队点=" + (l.teamFlags == null ? 0 : l.teamFlags.size())
					+ (reload ? "，已装入内存" : "")), true);
			NBW.LOGGER.info("[cww] lobby validated (reload={})", reload);
			return 1;
		}
		String detail = String.join(" | ", loaded.errors());
		ctx.getSource().sendError(Text.literal("[cww] lobby 被拒绝（不自动纠正）：" + detail));
		NBW.LOGGER.warn("[cww] lobby rejected: {}", detail);
		return 0;
	}

	private static int testFormat(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		String id = StringArgumentType.getString(ctx, "id");
		try {
			ConfigModels.Format format = ConfigService.loadFormat(worldRoot(), id);
			ConfigModels.Arena arena = ConfigService.loadArena(worldRoot(), "default_sea", false).arena();
			ZoneModel zone = ConfigService.toZone(arena, format);
			List<String> errors = zone.validateStages();
			if (errors.isEmpty()) {
				ctx.getSource().sendFeedback(() -> Text.literal("[cww] format '" + id + "' 校验通过；圈阶段 "
						+ zone.stages().size() + " 段，规格 " + format.teams + "x" + format.teamSize
						+ "，目标局长 " + (format.duration == null ? "?" : format.duration.targetTotal) + "s"), true);
				return 1;
			}
			String detail = String.join(" | ", errors);
			ctx.getSource().sendError(Text.literal("[cww] format '" + id + "' 被拒绝：" + detail));
			return 0;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendError(Text.literal("[cww] format '" + id + "' 读取/校验失败：" + e.getMessage()));
			return 0;
		}
	}
}
