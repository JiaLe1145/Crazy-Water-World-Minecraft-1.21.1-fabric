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
import dev.nbw.combat.WeaponService;
import dev.nbw.construct.BuildService;
import dev.nbw.content.NbwWeapons;
import dev.nbw.content.WeaponItem;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.cfg.ConfigService;
import dev.nbw.domain.ZoneModel;
import dev.nbw.domain.arena.SeaAudit;
import dev.nbw.domain.combat.CombatRule;
import dev.nbw.net.NbwNetworking;
import dev.nbw.raft.OarService;
import dev.nbw.raft.RaftCoreService;
import dev.nbw.raft.RaftSinking;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.raft.Sailing;
import dev.nbw.raft.WindService;
import dev.nbw.rescue.RescueService;
import dev.nbw.round.RoundManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.item.ItemStack;
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
		// 只加载赛制、不开局：报名上限跟着赛制走，没有这一步就只能沿用上一次的规格
		// （实测探针连着跑：上一局 2x2 规格还在内存里，这一局要 2x4，
		//   第 3 个玩家起就被"队伍 已满"拒掉，看着像 #41 复发）
		format.then(CommandManager.literal("reload").then(CommandManager.argument("id", StringArgumentType.word())
				.executes(NbwCommands::reloadFormat)));
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

		// ---- 落水与救援（M2-8 / 01 §8）：自救与伸手救援的时序、配对与拒绝原因的观测面
		var rescue = admin(CommandManager.literal("rescue"));
		rescue.then(CommandManager.literal("status").executes(NbwCommands::rescueStatus));
		rescue.then(CommandManager.literal("last").executes(NbwCommands::rescueLast));
		rescue.then(CommandManager.literal("hold")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, 600))
								.executes(NbwCommands::rescueHold))));
		// reach 登记在<b>救援方</b>身上：被救者不需要任何输入（2026-11-06 用户裁决）
		rescue.then(CommandManager.literal("reach")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, 600))
								.executes(NbwCommands::rescueReach))));
		root.then(rescue);

		// ---- 长按通道自身的判定面（M2-8）：坐标口径这种"错了不报、只是永远判不够"的东西，
		// 只能靠把服务端那道判定原样打出来取证。
		// deck/oar 两种写法由我们自己对上筏的局部格，探针里不出现 2×10^7 那种魔数；
		// at 保留给"手工喂一个存储坐标"的排查场合（x/y/z 不做范围限制，存储坐标本来就很大）
		var net = admin(CommandManager.literal("net"));
		// 逐级 then：三种目标的子节点必须挂在 player 参数下面，挂在 probe 上会让
		// "probe <玩家> deck 0" 解析完玩家就走进死胡同（实测报 Incorrect argument for command）
		var npDeck = CommandManager.literal("deck")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::netProbeDeck));
		var npOar = CommandManager.literal("oar")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("slot", IntegerArgumentType.integer(0, 32))
								.executes(NbwCommands::netProbeOar)));
		var npAt = CommandManager.literal("at")
				.then(CommandManager.argument("x", IntegerArgumentType.integer())
						.then(CommandManager.argument("y", IntegerArgumentType.integer())
								.then(CommandManager.argument("z", IntegerArgumentType.integer())
										.executes(NbwCommands::netProbeAt))));
		var npPlayer = CommandManager.argument("player", StringArgumentType.word());
		npPlayer.then(npDeck);
		npPlayer.then(npOar);
		npPlayer.then(npAt);
		net.then(CommandManager.literal("probe").then(npPlayer));
		root.then(net);

		// ---- 建造规则（M2-6 / 01 §4.2）：上限与归属判定的观测面
		var build = admin(CommandManager.literal("build"));
		build.then(CommandManager.literal("status").executes(NbwCommands::buildStatus));
		build.then(CommandManager.literal("last").executes(NbwCommands::buildLast));
		// 按坐标问判定、不写方块：给探针覆盖"越界 / 悬空 / 上限"这几条靠瞄准很难命中的门。
		// 逐级 then 而不是写成一整条链：这条命令有 6 个必填参数，链式写法上次把 onTeam
		// 挂成了 check 的并列子节点（asTeam 因此成了叶子节点），命令直接解析不动。
		var bcAs = CommandManager.argument("asTeam", IntegerArgumentType.integer(0));
		var bcOn = CommandManager.argument("onTeam", IntegerArgumentType.integer(0));
		var bcKind = CommandManager.argument("kind", StringArgumentType.word());
		var bcLx = CommandManager.argument("lx", IntegerArgumentType.integer(-64, 64));
		var bcLy = CommandManager.argument("ly", IntegerArgumentType.integer(-64, 64));
		var bcLz = CommandManager.argument("lz", IntegerArgumentType.integer(-64, 64));
		// block 用 greedyString：Brigadier 的不带引号 string 不接受冒号，
		// 实测 nbw:sail 会被切成 "nbw" + 尾随数据，而带命名空间正是必传的写法
		var bcBlock = CommandManager.argument("block", StringArgumentType.greedyString());
		bcBlock.executes(NbwCommands::buildCheck);
		bcLz.executes(NbwCommands::buildCheck).then(bcBlock);
		bcLy.then(bcLz);
		bcLx.then(bcLy);
		bcKind.then(bcLx);
		bcOn.then(bcKind);
		bcAs.then(bcOn);
		build.then(CommandManager.literal("check").then(bcAs));
		root.then(build);

		// ---- 武器（09 §2.1，已批准 A17 近战切片）：list / reload / give / status（审计读数）
		var weapon = admin(CommandManager.literal("weapon"));
		weapon.then(CommandManager.literal("list").executes(NbwCommands::weaponList));
		weapon.then(CommandManager.literal("reload").executes(NbwCommands::weaponReload));
		weapon.then(CommandManager.literal("status").executes(NbwCommands::weaponStatus));
		weapon.then(CommandManager.literal("give")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("id", StringArgumentType.word())
								.executes(NbwCommands::weaponGive))));
		root.then(weapon);

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

	private static int rescueStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(RescueService.status(ctx.getSource().getServer())), false);
		return 1;
	}

	private static int rescueLast(CommandContext<ServerCommandSource> ctx) {
		List<String> recent = RescueService.recent();
		if (recent.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] 还没有救援事件（没人扒过边）"), false);
			return 0;
		}
		for (String line : recent) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + line), false);
		}
		return recent.size();
	}

	private static int rescueHold(CommandContext<ServerCommandSource> ctx) {
		String player = StringArgumentType.getString(ctx, "player");
		int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
		String line = RescueService.hold(ctx.getSource().getServer(), player, ticks);
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + line), true);
		return 1;
	}

	/** 伸手救援的模拟入口：登记在<b>救援方</b>身上，返回行里带着配对了谁、距离几格。 */
	private static int rescueReach(CommandContext<ServerCommandSource> ctx) {
		String player = StringArgumentType.getString(ctx, "player");
		int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
		String line = RescueService.reach(ctx.getSource().getServer(), player, ticks);
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + line), true);
		return 1;
	}

	/** 把长按通道的服务端判定原样打出来：坐标口径这种静默失效只能这样取证。 */
	private static int netProbeAt(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		String player = StringArgumentType.getString(ctx, "player");
		var pos = new net.minecraft.util.math.BlockPos(IntegerArgumentType.getInteger(ctx, "x"),
				IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z"));
		return sendProbe(ctx, player, pos);
	}

	/** 甲板中心那一格（筏的局部 (0,0,0)）：有归属、没有方块实体，走的正是救援那一支分派。 */
	private static int netProbeDeck(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		return probeCell(ctx, team, 0, 0, 0, "甲板中心");
	}

	/** 某个桨位：必须认得出 OarBlockEntity，否则"长按桨台推进"这条整条是空的。 */
	private static int netProbeOar(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		int slot = IntegerArgumentType.getInteger(ctx, "slot");
		int[] cell = dev.nbw.domain.raft.RaftLayout.oarCell(RaftSpawner.deckSide(), slot);
		if (cell == null) {
			ctx.getSource().sendError(Text.literal("[cww] 边长 " + RaftSpawner.deckSide() + " 的甲板塞不下桨位 "
					+ slot + "，无从探测"));
			return 0;
		}
		return probeCell(ctx, team, cell[0], cell[1], cell[2], "桨位 " + slot);
	}

	/** 局部格 → 存储坐标（加 plot 中心块），再交给 {@link NbwNetworking#probe}。 */
	private static int probeCell(CommandContext<ServerCommandSource> ctx, int team, int lx, int ly, int lz,
			String what) throws CommandSyntaxException {
		var world = ctx.getSource().getWorld();
		UUID raftId = RaftSpawner.raftOf(team);
		if (raftId == null) {
			ctx.getSource().sendError(Text.literal("[cww] 队 " + team + " 这一局没有筏，探测个空气"));
			return 0;
		}
		var center = dev.nbw.sable.SableRafts.accessorOrigin(world, raftId);
		if (center == null) {
			ctx.getSource().sendError(Text.literal("[cww] 队 " + team + " 的筏读不到 plot 中心（已沉没？）"));
			return 0;
		}
		var pos = new net.minecraft.util.math.BlockPos(center.getX() + lx, center.getY() + ly, center.getZ() + lz);
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] 探测 " + what + "：局部 (" + lx + "," + ly + "," + lz
				+ ") → 存储 (" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
				+ ")（长按包给的就是这一串）"), false);
		return sendProbe(ctx, StringArgumentType.getString(ctx, "player"), pos);
	}

	private static int sendProbe(CommandContext<ServerCommandSource> ctx, String player,
			net.minecraft.util.math.BlockPos pos) {
		String line = NbwNetworking.probe(ctx.getSource().getServer(), player, pos);
		ctx.getSource().sendFeedback(() -> Text.literal(line), false);
		return 1;
	}

	private static int buildStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(BuildService.status(ctx.getSource().getWorld())), false);
		return 1;
	}

	private static int buildLast(CommandContext<ServerCommandSource> ctx) {
		List<String> recent = BuildService.recent();
		if (recent.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] 还没有建造判定记录（没人放过/拆过方块）"), false);
			return 0;
		}
		for (String line : recent) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + line), false);
		}
		return recent.size();
	}

	private static int buildCheck(CommandContext<ServerCommandSource> ctx) {
		int asTeam = IntegerArgumentType.getInteger(ctx, "asTeam");
		int onTeam = IntegerArgumentType.getInteger(ctx, "onTeam");
		String kind = StringArgumentType.getString(ctx, "kind");
		int lx = IntegerArgumentType.getInteger(ctx, "lx");
		int ly = IntegerArgumentType.getInteger(ctx, "ly");
		int lz = IntegerArgumentType.getInteger(ctx, "lz");
		String block;
		try {
			block = StringArgumentType.getString(ctx, "block");
		} catch (IllegalArgumentException missing) {
			block = null;   // 这一版 Brigadier 没有 getOptionalArgument，缺省只能这样问
		}
		String line = BuildService.check(ctx.getSource().getWorld(), asTeam, onTeam, kind, lx, ly, lz, block);
		final String text = "[cww] build check 队" + asTeam + " 对 队" + onTeam + " "
				+ kind + " (" + lx + ", " + ly + ", " + lz + ")：" + line;
		ctx.getSource().sendFeedback(() -> Text.literal(text), true);
		return 1;
	}

	private static int weaponList(CommandContext<ServerCommandSource> ctx) {
		var loaded = WeaponService.current();
		if (!loaded.ok()) {
			ctx.getSource().sendError(Text.literal("[cww] 武器表不可用：" + String.join(" | ", loaded.errors())));
			return 0;
		}
		List<String> lines = new ArrayList<>();
		for (CombatRule.WeaponSpec s : loaded.specs()) {
			lines.add(String.format(Locale.ROOT, "%s 伤害=%.1fHP 冷却=%dtick 击退=%.1f 对筏心=×%.1f",
					s.id(), s.damage(), s.cooldownTicks(), s.knockback(), s.coreMultiplier()));
		}
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] 武器 " + loaded.specs().size() + " 件："
				+ String.join("｜", lines)), false);
		return 1;
	}

	private static int weaponReload(CommandContext<ServerCommandSource> ctx) {
		return tell(ctx, WeaponService.reloadWeapons());
	}

	private static int weaponStatus(CommandContext<ServerCommandSource> ctx) {
		List<String> recent = WeaponService.recent();
		StringBuilder sb = new StringBuilder(WeaponService.status());
		if (!recent.isEmpty()) {
			sb.append("\n最近判定：\n");
			for (String line : recent) {
				sb.append("  ").append(line).append('\n');
			}
		}
		ctx.getSource().sendFeedback(() -> Text.literal(sb.toString().trim()), false);
		return 1;
	}

	private static int weaponGive(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
		if (target == null) {
			return tell(ctx, "找不到在线玩家 " + name + "（假玩家也要先 /player <名> spawn）");
		}
		String id = StringArgumentType.getString(ctx, "id");
		WeaponItem item = NbwWeapons.item(id);
		if (item == null) {
			return tell(ctx, "不认识武器 id：" + id + "（近战：paddle/cutlass/pike/warhammer/twin_blade）");
		}
		CombatRule.WeaponSpec spec = WeaponService.current().spec(id);
		if (spec == null) {
			return tell(ctx, "武器 " + id + " 不在当前武器表里（/cww weapon reload 后重试）");
		}
		String violation = CombatRule.holdViolation(WeaponService.meleeHeld(target), spec.kind());
		if (violation != null) {
			// 拒绝第二件近战：这是 09 §5.1 的持有上限，不是物品本身被禁
			NBW.LOGGER.warn("[cmd] weapon give 拒绝 {} 给 {}：{}", id, name, violation);
			return tell(ctx, "拒绝：" + violation);
		}
		if (!target.getInventory().insertStack(new ItemStack(item))) {
			return tell(ctx, "背包已满，给不进去");
		}
		NBW.LOGGER.info("[cmd] weapon give {} 给 {}（{}）", id, name, spec);
		return tell(ctx, "已给 " + name + " 武器 " + id + "（伤害 " + spec.damage()
				+ "HP，冷却 " + spec.cooldownTicks() + " tick）");
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

	/** 把赛制装进机器但不开局（只在 LOBBY/SETTLE 之外禁止，与 arena 换图的口径一致）。 */
	private static int reloadFormat(CommandContext<ServerCommandSource> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		String blocker = RoundManager.arenaSwapBlocker();
		if (blocker != null) {
			ctx.getSource().sendError(Text.literal("[cww] " + blocker + "（换赛制同样禁止）"));
			return 0;
		}
		ConfigModels.Arena now = RoundManager.arenaOrNull();
		if (now == null || now.id == null) {
			ctx.getSource().sendError(Text.literal("[cww] 当前 arena 未知，先用 /cww start <format> <arena> 或"
					+ " /cww arena reload <id> 定一张图"));
			return 0;
		}
		// loadConfigs 的约定是"成功返回 null，失败返回原因"，别把 null 当空消息打印出去
		String err = RoundManager.loadConfigs(id, now.id);
		if (err != null) {
			ctx.getSource().sendError(Text.literal("[cww] format '" + id + "' 装载失败：" + err));
			return 0;
		}
		var cfg = RoundManager.config();
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] format '" + id + "' 已装入内存（arena="
				+ now.id + "，规格 " + cfg.teamCount() + "x" + cfg.teamSize()
				+ "，下限 " + cfg.minPlayers() + "，容量 " + cfg.capacity() + "）"), true);
		return 1;
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
