package dev.nbw.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nbw.NBW;
import dev.nbw.construct.BuildService;
import dev.nbw.net.NbwNetworking;
import dev.nbw.raft.RaftCoreService;
import dev.nbw.raft.RaftSinking;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.raft.RemoteService;
import dev.nbw.raft.Sailing;
import dev.nbw.raft.WindService;
import dev.nbw.rescue.RescueService;
import dev.nbw.rescue.RopeService;
import dev.nbw.round.RoundManager;
import dev.nbw.round.ZoneService;
import dev.nbw.round.MetricsService;
import dev.nbw.loot.LootService;
import dev.nbw.survival.SurvivalService;
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
		// 黑海圈层（M3-1）：只读读数，给真人核对"现在半径多少、圈外掉多少血"
		root.then(admin(CommandManager.literal("zone").executes(NbwCommands::zoneStatus)));
		// 埋点与配平回路（M3-3）：局时长/首战/生产参与率
		root.then(admin(CommandManager.literal("metrics").executes(NbwCommands::metricsStatus)));
		// 事件池（M5-1）：读数 + dev 强制触发
		var eventNode = admin(CommandManager.literal("event").executes(NbwCommands::eventStatus));
		eventNode.then(CommandManager.literal("fire")
				.then(CommandManager.argument("name", StringArgumentType.word())
						.executes(NbwCommands::eventFire)));
		root.then(eventNode);
		// 战绩（M5-3）：读数
		root.then(admin(CommandManager.literal("stats").executes(NbwCommands::statsStatus)));
		// 幸存者（M5-2）：读数 + dev 夹具改职业（验舵手/工匠/卫兵效果用）
		var survivorNode = admin(CommandManager.literal("survivor").executes(NbwCommands::survivorStatus));
		survivorNode.then(CommandManager.literal("job")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("index", IntegerArgumentType.integer(0))
								.then(CommandManager.argument("job", StringArgumentType.word())
										.executes(NbwCommands::survivorSetJob)))));
		root.then(survivorNode);
		// 卫兵守筏读数（01 §7 A6）
		root.then(admin(CommandManager.literal("guard").executes(NbwCommands::guardStatus)));
		// 队伍物资箱读数（01 §4.1）
		root.then(admin(CommandManager.literal("supply").executes(NbwCommands::supplyStatus)));
		// 功率池（M4 补）：读数 + dev 充能
		var powerNode = admin(CommandManager.literal("power"));
		powerNode.then(CommandManager.literal("status").executes(NbwCommands::powerStatus));
		powerNode.then(CommandManager.literal("add")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0, 500))
								.executes(NbwCommands::powerAdd))));
		root.then(powerNode);
		// 筏心 HP / 破拆（反偷家 ×0.25）：读数 + dev 强制打
		var coreNode = admin(CommandManager.literal("core"));
		coreNode.then(CommandManager.literal("status").executes(NbwCommands::coreHpStatus));
		coreNode.then(CommandManager.literal("break")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("damage", DoubleArgumentType.doubleArg(0, 1000))
								.executes(NbwCommands::coreHpBreak))));
		root.then(coreNode);
		// 弩炮（09 §2.2）：读数 + dev 在命令方块位置放一门并登记归属
		var ballista = admin(CommandManager.literal("ballista"));
		ballista.then(CommandManager.literal("status").executes(NbwCommands::ballistaStatus));
		ballista.then(CommandManager.literal("place")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::ballistaPlace)));
		root.then(ballista);
		// 投石机（09 §2.2）：读数 + dev 在命令源位置放一门并登记归属
		var catapult = admin(CommandManager.literal("catapult"));
		catapult.then(CommandManager.literal("status").executes(NbwCommands::catapultStatus));
		catapult.then(CommandManager.literal("place")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::catapultPlace)));
		root.then(catapult);
		// 撞角（09 §2.2）：读数 + dev 在命令源位置放一个并登记归属
		var ram = admin(CommandManager.literal("ram"));
		ram.then(CommandManager.literal("status").executes(NbwCommands::ramStatus));
		ram.then(CommandManager.literal("place")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::ramPlace)));
		root.then(ram);
		// 外观/称号（01 §A11）：查看/列出/选择，无数值
		var cosmetic = admin(CommandManager.literal("cosmetic"));
		cosmetic.then(CommandManager.literal("status").executes(NbwCommands::cosmeticStatus));
		cosmetic.then(CommandManager.literal("list").executes(NbwCommands::cosmeticList));
		cosmetic.then(CommandManager.literal("select")
				.then(CommandManager.argument("id", StringArgumentType.word())
						.executes(NbwCommands::cosmeticSelect)));
		root.then(cosmetic);
		// 生存（M4-1）：营养/水分读数 + dev 强制设值
		var survival = admin(CommandManager.literal("survival"));
		survival.then(CommandManager.literal("status")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(NbwCommands::survivalStatus)));
		survival.then(CommandManager.literal("set")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("nutrition", DoubleArgumentType.doubleArg(0, 100))
								.then(CommandManager.argument("hydration", DoubleArgumentType.doubleArg(0, 100))
										.executes(NbwCommands::survivalSet)))));
		root.then(survival);
		// 海面物资（M3-2）：dev 手动撒箱子 + 读数
		var loot = admin(CommandManager.literal("loot"));
		loot.then(CommandManager.literal("status").executes(NbwCommands::lootStatus));
		loot.then(CommandManager.literal("spawn")
				.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 200))
						.executes(NbwCommands::lootSpawn)));
		root.then(loot);
		root.then(admin(CommandManager.literal("audit")
				.executes(ctx -> audit(ctx, 20))
				.then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 200))
						.executes(ctx -> audit(ctx, IntegerArgumentType.getInteger(ctx, "limit"))))));

		// ---- 地图 / 赛制 / 大厅 / 模板重置（已拆到 ArenaCommands，命令树保持不变）
		ArenaCommands.register(root);

		// ---- 公告（P1，参照 RicordRailExpress）：post / short / list / del / clear
		AnnounceCommands.register(root);

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

		// ---- 遥控器（问题③）：每筏一个，WASD 开船。give / status 给运维与探针取证
		var remote = admin(CommandManager.literal("remote"));
		remote.then(CommandManager.literal("status")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(NbwCommands::remoteStatus)));
		remote.then(CommandManager.literal("give")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(NbwCommands::remoteGive)));
		// dev/自动化夹具：/cww remote move <队> <keys> <tick>，keys 是 w/s/a/d 的组合（如 wa = 前进+左转）
		remote.then(CommandManager.literal("move")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("keys", StringArgumentType.word())
								.then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, 600))
										.executes(NbwCommands::remoteMove)))));
		root.then(remote);

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

		// ---- 抛绳索 / 钩竿（M2 尾巴 / 09 §2.3）：远程道具，一次使用即生效
		var rope = admin(CommandManager.literal("rope"));
		rope.then(CommandManager.literal("status").executes(NbwCommands::ropeStatus));
		rope.then(CommandManager.literal("cast")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(NbwCommands::ropeCast)));
		var grapnel = admin(CommandManager.literal("grapnel"));
		grapnel.then(CommandManager.literal("cast")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(NbwCommands::grapnelCast)));
		root.then(rope);
		root.then(grapnel);

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
		var npAt = CommandManager.literal("at")
				.then(CommandManager.argument("x", IntegerArgumentType.integer())
						.then(CommandManager.argument("y", IntegerArgumentType.integer())
								.then(CommandManager.argument("z", IntegerArgumentType.integer())
										.executes(NbwCommands::netProbeAt))));
		var npPlayer = CommandManager.argument("player", StringArgumentType.word());
		npPlayer.then(npDeck);
		npPlayer.then(npAt);
		net.then(CommandManager.literal("probe").then(npPlayer));
		// 包洪泛的自动化替身（Gate G5）：走 hold/move 接收器同一个限速闸门。
		// 它证明"入口接了限速、跨秒重置"，但不是 wire-level 伪造包，证据里要写明区别。
		net.then(CommandManager.literal("flood")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 100000))
								.executes(NbwCommands::netFlood))));
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
		// 扩建收费的确定性入口：不瞄准、不放方块，只跑真实扣费函数（dev/探针）。
		var bchPlayer = CommandManager.argument("player", StringArgumentType.word());
		var bchTeam = CommandManager.argument("team", IntegerArgumentType.integer(0));
		var bchLx = CommandManager.argument("lx", IntegerArgumentType.integer(-64, 64));
		var bchLy = CommandManager.argument("ly", IntegerArgumentType.integer(-64, 64));
		var bchLz = CommandManager.argument("lz", IntegerArgumentType.integer(-64, 64));
		var bchBlock = CommandManager.argument("block", StringArgumentType.greedyString());
		bchBlock.executes(NbwCommands::buildCharge);
		bchLz.then(bchBlock);
		bchLy.then(bchLz);
		bchLx.then(bchLy);
		bchTeam.then(bchLx);
		bchPlayer.then(bchTeam);
		build.then(CommandManager.literal("charge").then(bchPlayer));
		root.then(build);

		// ---- 武器（09 §2.1）：已拆到 WeaponCommands，命令树保持不变
		WeaponCommands.register(root);

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

	// ---------------------------------------------------------------- 遥控器（问题③）

	private static int remoteStatus(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		ctx.getSource().sendFeedback(() -> Text.literal(
				RemoteService.readout(ctx.getSource().getServer(), team)), false);
		return 1;
	}

	private static int remoteGive(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
		if (target == null) {
			return tell(ctx, "找不到在线玩家 " + name + "（假玩家也要先 /player <名> spawn）");
		}
		if (dev.nbw.content.NbwContent.remote == null) {
			return tell(ctx, "遥控器还没注册");
		}
		if (!target.getInventory().insertStack(new ItemStack(dev.nbw.content.NbwContent.remote))) {
			return tell(ctx, "背包已满，给不进去");
		}
		return tell(ctx, "已给 " + name + " 遥控器（主手拿着右键进入移动模式，WASD 开船）");
	}

	/** {@code /cww remote move <队> <keys> <tick>}：dev 夹具，模拟"某队按着 w/s/a/d 组合 N tick"。 */
	private static int remoteMove(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		String keys = StringArgumentType.getString(ctx, "keys").toLowerCase(Locale.ROOT);
		int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
		return tell(ctx, RemoteService.hold(team, keys.contains("w"), keys.contains("s"),
				keys.contains("a"), keys.contains("d"), ticks));
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

	private static int ropeStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(RopeService.status()), false);
		return 1;
	}

	/** {@code /cww rope cast <玩家>}：模拟"该玩家用绳索抛向最近合格目标"。dev/探针夹具。 */
	private static int ropeCast(CommandContext<ServerCommandSource> ctx) {
		return tell(ctx, RopeService.cast(ctx.getSource().getServer(),
				StringArgumentType.getString(ctx, "player"), false));
	}

	/** {@code /cww grapnel cast <玩家>}：模拟"该玩家用钩竿拉最近敌方"。dev/探针夹具。 */
	private static int grapnelCast(CommandContext<ServerCommandSource> ctx) {
		return tell(ctx, RopeService.cast(ctx.getSource().getServer(),
				StringArgumentType.getString(ctx, "player"), true));
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

	/**
	 * {@code /cww net flood <玩家> <次数>}：走 hold/move 共用的限速闸门，报告放行/丢弃。
	 * 只在 dev/管理员命令树里，生产玩家用不到。
	 */
	private static int netFlood(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		String playerName = StringArgumentType.getString(ctx, "player");
		int count = IntegerArgumentType.getInteger(ctx, "count");
		ServerPlayerEntity player = ctx.getSource().getServer().getPlayerManager().getPlayer(playerName);
		if (player == null) {
			ctx.getSource().sendError(Text.literal("[cww] 找不到在线玩家 " + playerName));
			return 0;
		}
		String line = NbwNetworking.floodProbe(ctx.getSource().getServer(), player, count);
		ctx.getSource().sendFeedback(() -> Text.literal(line), false);
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

	/** {@code /cww build charge <玩家> <队> <lx> <ly> <lz> <方块>}：扩建扣费的 dev 夹具。 */
	private static int buildCharge(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "player");
		int team = IntegerArgumentType.getInteger(ctx, "team");
		int lx = IntegerArgumentType.getInteger(ctx, "lx");
		int ly = IntegerArgumentType.getInteger(ctx, "ly");
		int lz = IntegerArgumentType.getInteger(ctx, "lz");
		String block = StringArgumentType.getString(ctx, "block");
		return tell(ctx, "build charge → " + BuildService.chargeProbe(
				ctx.getSource().getServer(), name, team, lx, ly, lz, block));
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

	/** {@code /cww zone}：黑海圈层的只读读数（半径 / 每秒伤害 / 圈心）。 */
	private static int zoneStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(ZoneService.status()), false);
		return 1;
	}

	/** {@code /cww metrics}：埋点读数（进行中的局 + 上一局的一行结论）。 */
	private static int metricsStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(MetricsService.status()), false);
		ctx.getSource().sendFeedback(() -> Text.literal("[metrics] 上局结论：" + MetricsService.lastSummary()), false);
		return 1;
	}

	/** {@code /cww event}：事件池读数（池大小 / 已触发 / 下一发倒计时）。 */
	private static int eventStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(
				dev.nbw.round.EventService.status(ctx.getSource().getServer())), false);
		return 1;
	}

	/** {@code /cww event fire <name>}：强制触发指定事件（dev/管理员）。 */
	private static int eventFire(CommandContext<ServerCommandSource> ctx) {
		String out = dev.nbw.round.EventService.forceFire(ctx.getSource().getServer(),
				StringArgumentType.getString(ctx, "name"));
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + out), false);
		return 1;
	}

	/** {@code /cww stats}：本局击杀/阵亡读数。 */
	private static int statsStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(
				dev.nbw.combat.CombatStats.status(ctx.getSource().getServer())), false);
		return 1;
	}

	/** {@code /cww survivor}：各队幸存者人数 + 口粮读数。 */
	private static int survivorStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(
				dev.nbw.round.SurvivorService.status()), false);
		return 1;
	}

	/** {@code /cww survivor job <队> <序号> <职业>}：dev 夹具，改某名幸存者的职业。 */
	private static int survivorSetJob(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		int index = IntegerArgumentType.getInteger(ctx, "index");
		String raw = StringArgumentType.getString(ctx, "job");
		dev.nbw.domain.survivor.SurvivorJob job =
				dev.nbw.domain.survivor.SurvivorJob.byName(raw);
		if (job == null) {
			return tell(ctx, "未知职业 " + raw
					+ "（可用：sailor/cook/medic/craftsman/helmsman/guard，或中文名）");
		}
		return tell(ctx, dev.nbw.round.SurvivorService.setJob(team, index, job));
	}

	/** {@code /cww supply}：各队初始物资箱的内容读数。 */
	private static int supplyStatus(CommandContext<ServerCommandSource> ctx) {
		var srv = ctx.getSource().getServer();
		StringBuilder sb = new StringBuilder("[supply] ");
		for (var team : RoundManager.machine().teams()) {
			if (team.members().isEmpty()) {
				continue;
			}
			sb.append(dev.nbw.raft.RaftSpawner.supplyStatus(srv, team.index())).append("｜");
		}
		if (sb.length() == "[supply] ".length()) {
			sb.append("本局没有队伍");
		}
		String out = sb.toString();
		ctx.getSource().sendFeedback(() -> Text.literal(out), false);
		return 1;
	}

	/** {@code /cww guard}：各队卫兵数与本局命中数。 */
	private static int guardStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(
				dev.nbw.round.GuardService.status()), false);
		return 1;
	}

	/** {@code /cww power}：各队电量池读数。 */
	private static int powerStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(
				dev.nbw.round.PowerService.status()), false);
		return 1;
	}

	/** {@code /cww power add <队> <电量>}：dev 充能（探针用，免去摆发电机）。 */
	private static int powerAdd(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		double amount = DoubleArgumentType.getDouble(ctx, "amount");
		dev.nbw.round.PowerService.add(team, amount);
		ctx.getSource().sendFeedback(() -> Text.literal(String.format(java.util.Locale.ROOT,
				"[cww] 队%d 电量 +%.1f → %.1f", team, amount,
				dev.nbw.round.PowerService.get(team))), false);
		return 1;
	}

	/** {@code /cww core status}：各队筏心 HP（有幸存者时破拆 ×0.25）+ 世界坐标（探针定位用）。 */
	private static int coreHpStatus(CommandContext<ServerCommandSource> ctx) {
		var sb = new StringBuilder("[core] ");
		var world = ctx.getSource().getServer().getOverworld();
		for (var team : RoundManager.machine().teams()) {
			int i = team.index();
			var pos = dev.nbw.raft.RaftCoreService.coreWorldPos(world, i);
			sb.append(String.format(java.util.Locale.ROOT, "队%d HP=%.1f 幸存者=%s 坐标=%s｜",
					i, dev.nbw.raft.RaftCoreService.hpOf(i),
					dev.nbw.round.SurvivorService.hasSurvivors(i) ? "有" : "无",
					pos == null ? "取不到" : String.format(java.util.Locale.ROOT, "(%.1f,%.1f,%.1f)",
							pos.x, pos.y, pos.z)));
		}
		ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
		return 1;
	}

	/** {@code /cww ballista status}：本局已放置的炮位与归属。 */
	private static int ballistaStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(
				dev.nbw.round.BallistaService.status()), false);
		return 1;
	}

	/** {@code /cww ballista place <队>}：dev 在命令源位置放一门并登记归属（setblock 不触发 onPlaced）。 */
	private static int ballistaPlace(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		var src = ctx.getSource();
		var pos = net.minecraft.util.math.BlockPos.ofFloored(src.getPosition());
		src.getWorld().setBlockState(pos, dev.nbw.content.NbwContent.ballista.getDefaultState());
		dev.nbw.round.BallistaService.register(pos, team);
		ctx.getSource().sendFeedback(() -> Text.literal(
				"[cww] 已在 " + pos.toShortString() + " 放置队 " + team + " 的弩炮"), false);
		return 1;
	}

	/** {@code /cww catapult status}：本局已放置的投石机与归属。 */
	private static int catapultStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(
				dev.nbw.round.CatapultService.status()), false);
		return 1;
	}

	/** {@code /cww catapult place <队>}：dev 在命令源位置放一门并登记归属。 */
	private static int catapultPlace(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		var src = ctx.getSource();
		var pos = net.minecraft.util.math.BlockPos.ofFloored(src.getPosition());
		src.getWorld().setBlockState(pos, dev.nbw.content.NbwContent.catapult.getDefaultState());
		dev.nbw.round.CatapultService.register(pos, team);
		ctx.getSource().sendFeedback(() -> Text.literal(
				"[cww] 已在 " + pos.toShortString() + " 放置队 " + team + " 的投石机"), false);
		return 1;
	}

	/** {@code /cww ram status}：本局已放置的撞角与归属。 */
	private static int ramStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(
				dev.nbw.round.RamService.status()), false);
		return 1;
	}

	/** {@code /cww ram place <队>}：dev 在命令源位置放一个并登记归属。 */
	private static int ramPlace(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		var src = ctx.getSource();
		var pos = net.minecraft.util.math.BlockPos.ofFloored(src.getPosition());
		src.getWorld().setBlockState(pos, dev.nbw.content.NbwContent.ram.getDefaultState());
		dev.nbw.round.RamService.register(pos, team);
		ctx.getSource().sendFeedback(() -> Text.literal(
				"[cww] 已在 " + pos.toShortString() + " 放置队 " + team + " 的撞角"), false);
		return 1;
	}

	/** {@code /cww cosmetic status}：自己的跨局战绩与当前称号。 */
	private static int cosmeticStatus(CommandContext<ServerCommandSource> ctx) {
		var p = ctx.getSource().getPlayer();
		if (p == null) {
			return tell(ctx, "这条只能玩家自己执行（RCON 用 cosmetic list 看全部称号）");
		}
		ctx.getSource().sendFeedback(() -> Text.literal(
				dev.nbw.round.CareerService.describe(p.getUuid())), false);
		return 1;
	}

	/** {@code /cww cosmetic list}：列出全部称号与门槛。 */
	private static int cosmeticList(CommandContext<ServerCommandSource> ctx) {
		var sb = new StringBuilder("[cww] 称号（胜场/击杀门槛）：");
		for (var t : dev.nbw.domain.cosmetic.CosmeticRule.TITLES) {
			sb.append(String.format(java.util.Locale.ROOT, "%s=%s(%d胜/%d杀) ",
					t.id(), t.display(), t.minWins(), t.minKills()));
		}
		ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
		return 1;
	}

	/** {@code /cww cosmetic select <id>}：选一个已解锁的称号（只改显示，不改面板）。 */
	private static int cosmeticSelect(CommandContext<ServerCommandSource> ctx) {
		var p = ctx.getSource().getPlayer();
		if (p == null) {
			return tell(ctx, "这条只能玩家自己执行");
		}
		String id = StringArgumentType.getString(ctx, "id");
		var t = dev.nbw.domain.cosmetic.CosmeticRule.byId(id);
		if (t == null) {
			return tell(ctx, "没有这个称号：" + id + "（用 /cww cosmetic list 看全部）");
		}
		String applied = dev.nbw.round.CareerService.selectTitle(p.getUuid(), id);
		if (applied == null) {
			return tell(ctx, "还没解锁「" + t.display() + "」，需要 " + t.minWins() + " 胜 / " + t.minKills() + " 杀");
		}
		dev.nbw.round.CareerService.save(dev.nbw.round.RoundManager.worldRoot());
		// 称号只改显示（01 §A11：外观解锁无数值）。1.21.1 Yarn 这版没有可直接调的
		// "改 tab 列表显示名"的公开 API（setPlayerListName / PlayerListEntry 都不可用），
		// 所以本轮只落到"存档 + 可查"，视觉显示留给后续的客户端渲染层。
		ctx.getSource().sendFeedback(() -> Text.literal(
				"[cww] 已切换称号：" + t.display() + "（存档已写入，视觉显示待客户端层接入）"), false);
		return 1;
	}

	/** {@code /cww core break <队> <伤害>}：dev 强制破拆（验证 ×0.25）。 */
	private static int coreHpBreak(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		double dmg = DoubleArgumentType.getDouble(ctx, "damage");
		double dealt = dev.nbw.raft.RaftCoreService.breakCore(team, dmg);
		ctx.getSource().sendFeedback(() -> Text.literal(String.format(java.util.Locale.ROOT,
				"[cww] 队%d 筏心受击 实际=%.2f 剩=%.1f", team, dealt,
				dev.nbw.raft.RaftCoreService.hpOf(team))), false);
		return 1;
	}

	/** {@code /cww survival status <玩家>}：营养/水分读数。 */
	private static int survivalStatus(CommandContext<ServerCommandSource> ctx) {
		String out = SurvivalService.status(ctx.getSource().getServer(), StringArgumentType.getString(ctx, "player"));
		ctx.getSource().sendFeedback(() -> Text.literal("[survival] " + out), false);
		return 1;
	}

	/** {@code /cww survival set <玩家> <营养> <水分>}：dev 强制设值（探针断言低值 debuff 用）。 */
	private static int survivalSet(CommandContext<ServerCommandSource> ctx) {
		String out = SurvivalService.set(ctx.getSource().getServer(),
				StringArgumentType.getString(ctx, "player"),
				DoubleArgumentType.getDouble(ctx, "nutrition"),
				DoubleArgumentType.getDouble(ctx, "hydration"));
		ctx.getSource().sendFeedback(() -> Text.literal("[survival] " + out), false);
		return 1;
	}

	/** {@code /cww loot status}：已撒补给箱数量。 */
	private static int lootStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(LootService.status()), false);
		return 1;
	}

	/** {@code /cww loot spawn <n>}：dev 手动撒 n 个补给箱（整片海随机，不受 prefill 环带限制）。 */
	private static int lootSpawn(CommandContext<ServerCommandSource> ctx) {
		int n = IntegerArgumentType.getInteger(ctx, "count");
		String out = LootService.spawn(ctx.getSource().getServer(), n, 0, 60);
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + out), false);
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
}
