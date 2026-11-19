package dev.nbw.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nbw.NBW;
import dev.nbw.raft.RaftCoreService;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.rescue.RescueService;
import dev.nbw.rescue.RopeService;
import dev.nbw.round.RoundManager;
import dev.nbw.round.ZoneService;
import dev.nbw.round.MetricsService;
import dev.nbw.loot.LootService;
import dev.nbw.survival.SurvivalService;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

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

		// ---- 筏与航行（已拆到 RaftCommands：raft / sail / wind / remote）
		RaftCommands.register(root);

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

		// ---- 风 / 遥控器 / 帆：已并入 RaftCommands，此处不再重复注册

		// ---- 落水与救援（M2-8 / 01 §8）：已拆到 RescueCommands，命令树保持不变
		RescueCommands.register(root);

		// ---- 探针（net/build）：已拆到 ProbeCommands，命令树保持不变
		ProbeCommands.register(root);

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
