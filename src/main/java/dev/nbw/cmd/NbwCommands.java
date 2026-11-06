package dev.nbw.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nbw.NBW;
import dev.nbw.arena.BuiltinArenaJson;
import dev.nbw.arena.SeaTerrain;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.cfg.ConfigService;
import dev.nbw.domain.ZoneModel;
import dev.nbw.domain.arena.SeaAudit;
import dev.nbw.raft.OarService;
import dev.nbw.raft.RaftCoreService;
import dev.nbw.raft.RaftSinking;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.raft.Sailing;
import dev.nbw.raft.WindService;
import dev.nbw.round.RoundManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
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
		arena.then(CommandManager.literal("terrain").then(CommandManager.argument("id", StringArgumentType.word())
				.executes(ctx -> arenaTerrain(ctx, StringArgumentType.getString(ctx, "id")))));
		root.then(arena);

		var format = admin(CommandManager.literal("format"));
		format.then(CommandManager.literal("test").then(CommandManager.argument("id", StringArgumentType.word())
				.executes(NbwCommands::testFormat)));
		root.then(format);

		var lobby = admin(CommandManager.literal("lobby"));
		lobby.then(CommandManager.literal("test").executes(ctx -> testLobby(ctx, false)));
		lobby.then(CommandManager.literal("reload").executes(ctx -> testLobby(ctx, true)));
		root.then(lobby);

		// ---- 沉筏运维（01 §4.3.1 A3-v4）。命令层只碰 RaftSinking，不碰 Sable 类型。
		var raft = admin(CommandManager.literal("raft"));
		raft.then(CommandManager.literal("status").executes(NbwCommands::raftStatus));
		raft.then(CommandManager.literal("all").executes(NbwCommands::raftAll));
		raft.then(CommandManager.literal("gen")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::raftGen)));
		raft.then(CommandManager.literal("bind")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::raftBindNearest)
						.then(CommandManager.argument("name", StringArgumentType.word())
								.executes(NbwCommands::raftBindByName))));
		raft.then(CommandManager.literal("repair")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::raftRepair)));
		raft.then(CommandManager.literal("sink")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::raftSink)));
		raft.then(CommandManager.literal("weight")
				.then(CommandManager.argument("multiplier", DoubleArgumentType.doubleArg(1.0, 4.0))
						.executes(NbwCommands::raftWeight)));
		root.then(raft);

		// ---- 筏心与残骸修复（01 §4.3.2 A2 / A2-v2）
		var core = admin(CommandManager.literal("raftcore"));
		core.then(CommandManager.literal("status").executes(NbwCommands::coreStatus));
		core.then(CommandManager.literal("destroy")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::coreDestroy)));
		core.then(CommandManager.literal("hold")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
								.then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, 600))
										.executes(NbwCommands::coreHold)))));
		root.then(core);

		// ---- 航行（M2）：挂帆 / 拆帆 / 读数
		var sail = admin(CommandManager.literal("sail"));
		sail.then(CommandManager.literal("read")
				.executes(NbwCommands::sailRead));
		sail.then(CommandManager.literal("rig")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("count", IntegerArgumentType.integer(0, 7))
								.then(CommandManager.argument("facing", StringArgumentType.word())
										.executes(NbwCommands::sailRig)))));
		sail.then(CommandManager.literal("strip")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::sailStrip)));
		root.then(sail);

		// ---- 风（M2-5）：全局单值，所有队同一时刻受同一阵风
		var wind = admin(CommandManager.literal("wind"));
		wind.then(CommandManager.literal("read")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::windRead)));
		wind.then(CommandManager.literal("set")
				.then(CommandManager.argument("dir", StringArgumentType.word())
						.then(CommandManager.argument("strength", DoubleArgumentType.doubleArg(0.0, 4.0))
								.executes(NbwCommands::windSet))));
		root.then(wind);

		// ---- 桨台（M2-5 动力第二源）：挂桨 / 拆桨 / 读数 / 模拟划桨
		var oar = admin(CommandManager.literal("oar"));
		oar.then(CommandManager.literal("read")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::oarRead)));
		oar.then(CommandManager.literal("rig")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("count",
								IntegerArgumentType.integer(0, OarService.MAX_OARS_PER_TEAM))
								.then(CommandManager.argument("facing", StringArgumentType.word())
										.executes(NbwCommands::oarRig)))));
		oar.then(CommandManager.literal("strip")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::oarStrip)));
		oar.then(CommandManager.literal("hold")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
								.then(CommandManager.argument("facing", StringArgumentType.word())
										.then(CommandManager.argument("ticks",
												IntegerArgumentType.integer(1, 600))
												.executes(NbwCommands::oarHold))))));
		root.then(oar);

		// ---- 落盘与重启恢复（Gate G1）
		root.then(admin(CommandManager.literal("save").executes(NbwCommands::saveNow)));
		root.then(admin(CommandManager.literal("statefile").executes(NbwCommands::stateFile)));

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

	// ---------------------------------------------------------------- 沉筏命令

	private static int raftAll(CommandContext<ServerCommandSource> ctx) {
		String msg = RaftSpawner.status(ctx.getSource().getServer());
		ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
		return 1;
	}

	private static int raftGen(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		return tell(ctx, RaftSpawner.generateOne(ctx.getSource().getServer(), team));
	}

	// ---------------------------------------------------------------- 航行

	private static int windRead(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		ctx.getSource().sendFeedback(() -> Text.literal(WindService.readout(ctx.getSource().getServer(), team)), false);
		return 1;
	}

	private static int windSet(CommandContext<ServerCommandSource> ctx) {
		String dir = StringArgumentType.getString(ctx, "dir");
		double strength = DoubleArgumentType.getDouble(ctx, "strength");
		return tell(ctx, WindService.set(dir, strength) + " | "
				+ WindService.readout(ctx.getSource().getServer(), 0));
	}

	private static int sailRead(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(
				Sailing.readout(ctx.getSource().getServer(), RaftSpawner.raftOf(0))), false);
		return 1;
	}

	private static int sailRig(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		int count = IntegerArgumentType.getInteger(ctx, "count");
		String raw = StringArgumentType.getString(ctx, "facing");
		Direction facing;
		try {
			facing = Direction.byName(raw.toLowerCase(Locale.ROOT));
		} catch (RuntimeException e) {
			facing = null;
		}
		if (facing == null || facing.getAxis() == Direction.Axis.Y) {
			return tell(ctx, "朝向只能是 north/south/east/west，给了 " + raw);
		}
		UUID raft = RaftSpawner.raftOf(team);
		if (raft == null) {
			return tell(ctx, "队 " + team + " 没有筏；先 /cww start");
		}
		return tell(ctx, Sailing.rig(ctx.getSource().getServer().getOverworld(), raft, count, facing)
				+ " | " + Sailing.readout(ctx.getSource().getServer(), raft));
	}

	private static int sailStrip(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		UUID raft = RaftSpawner.raftOf(team);
		if (raft == null) {
			return tell(ctx, "队 " + team + " 没有筏");
		}
		int n = Sailing.deRig(ctx.getSource().getServer().getOverworld(), raft);
		return tell(ctx, "拆掉 " + n + " 块帆 | " + Sailing.readout(ctx.getSource().getServer(), raft));
	}

	// ---------------------------------------------------------------- 桨台（M2-5 动力第二源）

	private static int oarRead(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		ctx.getSource().sendFeedback(() -> Text.literal(
				OarService.readout(ctx.getSource().getServer(), team)), false);
		return 1;
	}

	/**
	 * /cww oar rig &lt;队&gt; &lt;数量&gt; &lt;朝向&gt;
	 *
	 * <p>命令层只把字符串解析成 {@code Direction}，<b>不重复校验</b>合法朝向与数量上限：
	 * 校验口径留在 {@link OarService}，两处各写一份迟早会出现"命令收了、服务不认"的分叉。
	 */
	private static int oarRig(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		int count = IntegerArgumentType.getInteger(ctx, "count");
		String raw = StringArgumentType.getString(ctx, "facing");
		Direction facing;
		try {
			facing = Direction.byName(raw.toLowerCase(Locale.ROOT));
		} catch (RuntimeException e) {
			facing = null;
		}
		return tell(ctx, OarService.rig(ctx.getSource().getServer().getOverworld(),
				RaftSpawner.raftOf(team), team, count, facing)
				+ " | " + OarService.readout(ctx.getSource().getServer(), team));
	}

	private static int oarStrip(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		UUID raft = RaftSpawner.raftOf(team);
		int n = OarService.strip(ctx.getSource().getServer().getOverworld(), raft, team);
		return tell(ctx, "拆掉 " + n + " 台桨台 | " + OarService.readout(ctx.getSource().getServer(), team));
	}

	/**
	 * /cww oar hold &lt;玩家&gt; &lt;队&gt; &lt;朝向&gt; &lt;tick&gt;
	 *
	 * <p>模拟"在某队一台朝该方向的桨上持续按住 N tick"。<b>这不是玩法入口，是自动化测试夹具</b>：
	 * 真人路径要走长按自定义包（假玩家发不了），但服务端从这里往后的登记与推进完全同一条，
	 * 所以桨力曲线、方向、断输入即失力这三件事可以不等真人就验完。
	 */
	private static int oarHold(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		UUID player = RaftCoreService.playerByName(ctx.getSource().getServer(), name);
		if (player == null) {
			return tell(ctx, "找不到在线玩家 " + name + "（假玩家也要先 /player <名> spawn）");
		}
		int team = IntegerArgumentType.getInteger(ctx, "team");
		String facing = StringArgumentType.getString(ctx, "facing");
		int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
		return tell(ctx, OarService.hold(player, team, facing, ticks));
	}

	// ---------------------------------------------------------------- 筏心与残骸修复

	private static int coreStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(RaftCoreService.status()), false);
		return 1;
	}

	private static int coreDestroy(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		RaftCoreService.markDestroyed(team);
		return tell(ctx, "队 " + team + " 筏心已置为残骸（30s 沉筏倒计时开始）");
	}

	/**
	 * /cww raftcore hold &lt;玩家&gt; &lt;队&gt; &lt;tick&gt;
	 *
	 * <p>模拟"持续按住右键 N tick"。这不是玩法入口，是自动化测试夹具：
	 * 真实客户端长按要走自定义包（还没做），但服务端那条路径与这里完全相同，
	 * 所以 8 秒持续与受伤打断可以不等真人就验完。
	 */
	private static int coreHold(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		UUID player = RaftCoreService.playerByName(ctx.getSource().getServer(), name);
		if (player == null) {
			return tell(ctx, "找不到在线玩家 " + name + "（假玩家也要先 /player <名> spawn）");
		}
		int team = IntegerArgumentType.getInteger(ctx, "team");
		int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
		return tell(ctx, RaftCoreService.hold(ctx.getSource().getServer(), player, team, ticks));
	}

	// ---------------------------------------------------------------- 落盘

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

	private static int raftStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(RaftSinking.status()), false);
		return 1;
	}

	private static int raftBindNearest(CommandContext<ServerCommandSource> ctx) {
		var src = ctx.getSource();
		int team = IntegerArgumentType.getInteger(ctx, "team");
		var pos = src.getPosition();
		return tell(ctx, RaftSinking.bindNearest(src.getWorld(), team, pos.x, pos.y, pos.z));
	}

	private static int raftBindByName(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		return tell(ctx, RaftSinking.bindByName(ctx.getSource().getWorld(), team,
				StringArgumentType.getString(ctx, "name")));
	}

	private static int raftRepair(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		return tell(ctx, RaftSinking.repair(team)
				? "队 " + team + " 筏心已重修，沉筏倒计时回到 30s（每局限 1 次）"
				: "队 " + team + " 重修未生效：要么不在倒计时中，要么本局额度已用完");
	}

	private static int raftSink(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		return tell(ctx, RaftSinking.forceSink(ctx.getSource().getWorld(), team));
	}

	private static int raftWeight(CommandContext<ServerCommandSource> ctx) {
		double multiplier = DoubleArgumentType.getDouble(ctx, "multiplier");
		RaftSinking.sinkWeightMultiplier = multiplier;
		return tell(ctx, "沉筏有效重量倍数 = " + multiplier + "（上限 4.0：实测 6.0 会把筏压穿池底石头）");
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
		ids.sort(null);
		List<String> builtinOnly = new ArrayList<>();
		List<String> shadowed = new ArrayList<>();
		for (String id : BuiltinArenaJson.IDS) {
			if (ids.contains(id)) {
				shadowed.add(id);
			} else {
				builtinOnly.add(id);
			}
		}
		int total = ids.size() + builtinOnly.size();
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] arenas(" + total + ") world=[" + String.join(", ", ids)
				+ "] 内置=[" + String.join(", ", builtinOnly) + "]"
				+ (shadowed.isEmpty() ? "" : " 内置被本地同名文件覆盖=" + String.join(", ", shadowed)
						+ "（改内置图不会生效，测的就是这条）")), false);
		return total;
	}

	private static int testArena(CommandContext<ServerCommandSource> ctx, String id, boolean reload) {
		if (reload) {
			String blocker = RoundManager.arenaSwapBlocker();
			if (blocker != null) {
				ctx.getSource().sendError(Text.literal("[cww] " + blocker));
				return 0;
			}
		}
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot(), id, reload, BuiltinArenaJson.SOURCE);
		if (loaded.ok()) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] arena '" + id + "' 校验通过；来源="
					+ loaded.source().label() + "；环带 "
					+ loaded.zone().rings().size() + " 段，初始半径 " + (int) loaded.zone().baseRadius()
					+ (reload ? "，已装入内存" : "")), true);
			NBW.LOGGER.info("[cww] arena '{}' validated from {} (reload={})", id, loaded.source(), reload);
			return 1;
		}
		String detail = String.join(" | ", loaded.errors());
		ctx.getSource().sendError(Text.literal("[cww] arena '" + id + "' 被拒绝（不自动纠正）：" + detail));
		NBW.LOGGER.warn("[cww] arena '{}' rejected: {}", id, detail);
		return 0;
	}

	/**
	 * /cww arena terrain &lt;id&gt;：把"配置要求的海"和"世界上实际的地形"对着读一遍。
	 * 只读，不建、不改、不填。返回问题列数（0 = 这张图的地形与配置一致），探针就断这个数。
	 */
	private static int arenaTerrain(CommandContext<ServerCommandSource> ctx, String id) throws CommandSyntaxException {
		ConfigService.Loaded loaded = ConfigService.loadArena(worldRoot(), id, false, BuiltinArenaJson.SOURCE);
		if (!loaded.ok()) {
			ctx.getSource().sendError(Text.literal("[cww] arena '" + id + "' 配置本身就没过，先别谈地形："
					+ String.join(" | ", loaded.errors())));
			return -1;
		}
		ConfigModels.Arena arena = loaded.arena();
		int teamCount = arena.spawns != null && arena.spawns.teams != null && !arena.spawns.teams.isEmpty()
				? arena.spawns.teams.size() : 4;
		ServerWorld world = ctx.getSource().getServer().getOverworld();
		List<SeaAudit.Reading> readings = SeaTerrain.sample(world, arena, teamCount);

		final int problems = (int) readings.stream().filter(r -> r.verdict().problem()).count();
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] arena '" + id + "' 地形审计（来源="
				+ loaded.source().label() + "）：水面 y=" + arena.sea.waterLevel
				+ "，海床顶 y=" + (arena.sea.waterLevel - SeaAudit.WATER_DEPTH)
				+ "，采样 " + readings.size() + " 列，问题 " + problems + " 列"), false);
		for (SeaAudit.Reading r : readings) {
			final String line = SeaAudit.describe(r);
			ctx.getSource().sendFeedback(() -> Text.literal("      " + line), false);
		}
		if (problems > 0) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] 这张图还没建，或者地形与 JSON 不同源："
					+ "跑 /function nbw:arena/" + id + "/build（随包地形命令）之后再来看一次。"
					+ "对局不会因此被拦下；实测传送会同步生成区块，代价是一次约 2 秒的服务端冻结。"), false);
		}
		NBW.LOGGER.info("[cww] arena '{}' terrain audit: {} 列 / {} 处问题", id, readings.size(), problems);
		return problems;
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
			ConfigModels.Arena arena = ConfigService.loadArena(worldRoot(), "default_sea", false,
					BuiltinArenaJson.SOURCE).arena();
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
