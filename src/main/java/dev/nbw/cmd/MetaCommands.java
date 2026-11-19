package dev.nbw.cmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.nbw.combat.CombatStats;
import dev.nbw.domain.cosmetic.CosmeticRule;
import dev.nbw.domain.survivor.SurvivorJob;
import dev.nbw.loot.LootService;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.round.CareerService;
import dev.nbw.round.EventService;
import dev.nbw.round.GuardService;
import dev.nbw.round.MetricsService;
import dev.nbw.round.PowerService;
import dev.nbw.round.RoundManager;
import dev.nbw.round.SurvivorService;
import dev.nbw.round.ZoneService;
import dev.nbw.survival.SurvivalService;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * 读数与元系统命令（从 {@link NbwCommands} 拆出来）：
 * 圈层/埋点/事件/战绩/幸存者/卫兵/物资/功率池/外观/生存/物资箱/审计。
 *
 * <p>这一组都是"看一眼现在什么状态"或"dev 强行拨一个值"的入口，没有一条会改变玩法流程本身，
 * 所以与 RoundCommands / FacilityCommands 分开：改玩法不碰这里，加读数也不碰玩法。
 *
 * <p>拆分**不改命令树**：注册出来的路径与拆分前逐字一致。
 */
public final class MetaCommands {

	private MetaCommands() {
	}

	/** 挂到 {@code /cww} 根节点下。 */
	public static void register(LiteralArgumentBuilder<ServerCommandSource> root) {
		// 黑海圈层（M3-1）：只读读数，给真人核对"现在半径多少、圈外掉多少血"
		root.then(CmdSupport.admin(CommandManager.literal("zone").executes(MetaCommands::zoneStatus)));
		// 埋点与配平回路（M3-3）：局时长/首战/生产参与率
		root.then(CmdSupport.admin(CommandManager.literal("metrics").executes(MetaCommands::metricsStatus)));
		// 事件池（M5-1）：读数 + dev 强制触发
		var eventNode = CmdSupport.admin(CommandManager.literal("event").executes(MetaCommands::eventStatus));
		eventNode.then(CommandManager.literal("fire")
				.then(CommandManager.argument("name", StringArgumentType.word())
						.executes(MetaCommands::eventFire)));
		root.then(eventNode);
		// 战绩（M5-3）：读数
		root.then(CmdSupport.admin(CommandManager.literal("stats").executes(MetaCommands::statsStatus)));
		// 幸存者（M5-2）：读数 + dev 夹具改职业（验舵手/工匠/卫兵效果用）
		var survivorNode = CmdSupport.admin(CommandManager.literal("survivor").executes(MetaCommands::survivorStatus));
		survivorNode.then(CommandManager.literal("job")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("index", IntegerArgumentType.integer(0))
								.then(CommandManager.argument("job", StringArgumentType.word())
										.executes(MetaCommands::survivorSetJob)))));
		root.then(survivorNode);
		// 卫兵守筏读数（01 §7 A6）
		root.then(CmdSupport.admin(CommandManager.literal("guard").executes(MetaCommands::guardStatus)));
		// 队伍物资箱读数（01 §4.1）
		root.then(CmdSupport.admin(CommandManager.literal("supply").executes(MetaCommands::supplyStatus)));
		// 功率池（M4 补）：读数 + dev 充能
		var powerNode = CmdSupport.admin(CommandManager.literal("power"));
		powerNode.then(CommandManager.literal("status").executes(MetaCommands::powerStatus));
		powerNode.then(CommandManager.literal("add")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0, 500))
								.executes(MetaCommands::powerAdd))));
		root.then(powerNode);
		// 外观/称号（01 §A11）：查看/列出/选择，无数值
		var cosmetic = CmdSupport.admin(CommandManager.literal("cosmetic"));
		cosmetic.then(CommandManager.literal("status").executes(MetaCommands::cosmeticStatus));
		cosmetic.then(CommandManager.literal("list").executes(MetaCommands::cosmeticList));
		cosmetic.then(CommandManager.literal("select")
				.then(CommandManager.argument("id", StringArgumentType.word())
						.executes(MetaCommands::cosmeticSelect)));
		root.then(cosmetic);
		// 生存（M4-1）：营养/水分读数 + dev 强制设值
		var survival = CmdSupport.admin(CommandManager.literal("survival"));
		survival.then(CommandManager.literal("status")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(MetaCommands::survivalStatus)));
		survival.then(CommandManager.literal("set")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("nutrition", DoubleArgumentType.doubleArg(0, 100))
								.then(CommandManager.argument("hydration", DoubleArgumentType.doubleArg(0, 100))
										.executes(MetaCommands::survivalSet)))));
		root.then(survival);
		// 海面物资（M3-2）：dev 手动撒箱子 + 读数
		var loot = CmdSupport.admin(CommandManager.literal("loot"));
		loot.then(CommandManager.literal("status").executes(MetaCommands::lootStatus));
		loot.then(CommandManager.literal("spawn")
				.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 200))
						.executes(MetaCommands::lootSpawn)));
		root.then(loot);
		root.then(CmdSupport.admin(CommandManager.literal("audit")
				.executes(ctx -> audit(ctx, 20))
				.then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 200))
						.executes(ctx -> audit(ctx, IntegerArgumentType.getInteger(ctx, "limit"))))));
	}

	// ---------------------------------------------------------------- 圈层/埋点/事件/战绩

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
				EventService.status(ctx.getSource().getServer())), false);
		return 1;
	}

	/** {@code /cww event fire <name>}：强制触发指定事件（dev/管理员）。 */
	private static int eventFire(CommandContext<ServerCommandSource> ctx) {
		String out = EventService.forceFire(ctx.getSource().getServer(),
				StringArgumentType.getString(ctx, "name"));
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + out), false);
		return 1;
	}

	/** {@code /cww stats}：本局击杀/阵亡读数。 */
	private static int statsStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(
				CombatStats.status(ctx.getSource().getServer())), false);
		return 1;
	}

	// ---------------------------------------------------------------- 幸存者/卫兵/物资/功率

	/** {@code /cww survivor}：各队幸存者人数 + 口粮读数。 */
	private static int survivorStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(SurvivorService.status()), false);
		return 1;
	}

	/** {@code /cww survivor job <队> <序号> <职业>}：dev 夹具，改某名幸存者的职业。 */
	private static int survivorSetJob(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		int index = IntegerArgumentType.getInteger(ctx, "index");
		String raw = StringArgumentType.getString(ctx, "job");
		SurvivorJob job = SurvivorJob.byName(raw);
		if (job == null) {
			return CmdSupport.tell(ctx, "未知职业 " + raw
					+ "（可用：sailor/cook/medic/craftsman/helmsman/guard，或中文名）");
		}
		return CmdSupport.tell(ctx, SurvivorService.setJob(team, index, job));
	}

	/** {@code /cww supply}：各队初始物资箱的内容读数。 */
	private static int supplyStatus(CommandContext<ServerCommandSource> ctx) {
		var srv = ctx.getSource().getServer();
		StringBuilder sb = new StringBuilder("[supply] ");
		for (var team : RoundManager.machine().teams()) {
			if (team.members().isEmpty()) {
				continue;
			}
			sb.append(RaftSpawner.supplyStatus(srv, team.index())).append("｜");
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
		ctx.getSource().sendFeedback(() -> Text.literal(GuardService.status()), false);
		return 1;
	}

	/** {@code /cww power}：各队电量池读数。 */
	private static int powerStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(PowerService.status()), false);
		return 1;
	}

	/** {@code /cww power add <队> <电量>}：dev 充能（探针用，免去摆发电机）。 */
	private static int powerAdd(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		double amount = DoubleArgumentType.getDouble(ctx, "amount");
		PowerService.add(team, amount);
		ctx.getSource().sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
				"[cww] 队%d 电量 +%.1f → %.1f", team, amount,
				PowerService.get(team))), false);
		return 1;
	}

	// ---------------------------------------------------------------- 外观/生存/物资箱/审计

	/** {@code /cww cosmetic status}：自己的跨局战绩与当前称号。 */
	private static int cosmeticStatus(CommandContext<ServerCommandSource> ctx) {
		var p = ctx.getSource().getPlayer();
		if (p == null) {
			return CmdSupport.tell(ctx, "这条只能玩家自己执行（RCON 用 cosmetic list 看全部称号）");
		}
		ctx.getSource().sendFeedback(() -> Text.literal(CareerService.describe(p.getUuid())), false);
		return 1;
	}

	/** {@code /cww cosmetic list}：列出全部称号与门槛。 */
	private static int cosmeticList(CommandContext<ServerCommandSource> ctx) {
		var sb = new StringBuilder("[cww] 称号（胜场/击杀门槛）：");
		for (var t : CosmeticRule.TITLES) {
			sb.append(String.format(Locale.ROOT, "%s=%s(%d胜/%d杀) ",
					t.id(), t.display(), t.minWins(), t.minKills()));
		}
		ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
		return 1;
	}

	/** {@code /cww cosmetic select <id>}：选一个已解锁的称号（只改显示，不改面板）。 */
	private static int cosmeticSelect(CommandContext<ServerCommandSource> ctx) {
		var p = ctx.getSource().getPlayer();
		if (p == null) {
			return CmdSupport.tell(ctx, "这条只能玩家自己执行");
		}
		String id = StringArgumentType.getString(ctx, "id");
		var t = CosmeticRule.byId(id);
		if (t == null) {
			return CmdSupport.tell(ctx, "没有这个称号：" + id + "（用 /cww cosmetic list 看全部）");
		}
		String applied = CareerService.selectTitle(p.getUuid(), id);
		if (applied == null) {
			return CmdSupport.tell(ctx, "还没解锁「" + t.display() + "」，需要 " + t.minWins() + " 胜 / " + t.minKills() + " 杀");
		}
		CareerService.save(RoundManager.worldRoot());
		// 称号只改显示（01 §A11：外观解锁无数值）。1.21.1 Yarn 这版没有可直接调的
		// "改 tab 列表显示名"的公开 API（setPlayerListName / PlayerListEntry 都不可用），
		// 所以本轮只落到"存档 + 可查"，视觉显示留给后续的客户端渲染层。
		ctx.getSource().sendFeedback(() -> Text.literal(
				"[cww] 已切换称号：" + t.display() + "（存档已写入，视觉显示待客户端层接入）"), false);
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
