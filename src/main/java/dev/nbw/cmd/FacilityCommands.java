package dev.nbw.cmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.nbw.content.NbwContent;
import dev.nbw.raft.RaftCoreService;
import dev.nbw.round.BallistaService;
import dev.nbw.round.CatapultService;
import dev.nbw.round.RamService;
import dev.nbw.round.RoundManager;
import dev.nbw.round.SurvivorService;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;
import java.util.UUID;

/**
 * 筏心与设施武器命令（从 {@link NbwCommands} 拆出来）：
 * {@code raftcore}（沉筏链路）、{@code core}（筏心 HP / 破拆）、
 * {@code ballista} / {@code catapult} / {@code ram}（三件设施武器）。
 *
 * <p>这一组全都在"打家"这条线上：谁的筏心还能撑、破拆吃不吃 ×0.25、设施有没有打出去。
 * 拆分**不改命令树**：注册出来的路径与拆分前逐字一致。
 */
public final class FacilityCommands {

	private FacilityCommands() {
	}

	/** 挂到 {@code /cww} 根节点下：raftcore / core / ballista / catapult / ram。 */
	public static void register(LiteralArgumentBuilder<ServerCommandSource> root) {
		// 筏心 HP / 破拆（反偷家 ×0.25）：读数 + dev 强制打
		var coreNode = CmdSupport.admin(CommandManager.literal("core"));
		coreNode.then(CommandManager.literal("status").executes(FacilityCommands::coreHpStatus));
		coreNode.then(CommandManager.literal("break")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("damage", DoubleArgumentType.doubleArg(0, 1000))
								.executes(FacilityCommands::coreHpBreak))));
		root.then(coreNode);

		// 弩炮（09 §2.2）：读数 + dev 在命令方块位置放一门并登记归属
		var ballista = CmdSupport.admin(CommandManager.literal("ballista"));
		ballista.then(CommandManager.literal("status").executes(FacilityCommands::ballistaStatus));
		ballista.then(CommandManager.literal("place")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(FacilityCommands::ballistaPlace)));
		root.then(ballista);

		// 投石机（09 §2.2）：读数 + dev 在命令源位置放一门并登记归属
		var catapult = CmdSupport.admin(CommandManager.literal("catapult"));
		catapult.then(CommandManager.literal("status").executes(FacilityCommands::catapultStatus));
		catapult.then(CommandManager.literal("place")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(FacilityCommands::catapultPlace)));
		root.then(catapult);

		// 撞角（09 §2.2）：读数 + dev 在命令源位置放一个并登记归属
		var ram = CmdSupport.admin(CommandManager.literal("ram"));
		ram.then(CommandManager.literal("status").executes(FacilityCommands::ramStatus));
		ram.then(CommandManager.literal("place")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(FacilityCommands::ramPlace)));
		root.then(ram);

		// ---- 筏心与残骸修复（01 §4.3.2 A2 / A2-v2）
		var raftcore = CmdSupport.admin(CommandManager.literal("raftcore"));
		raftcore.then(CommandManager.literal("status").executes(FacilityCommands::coreStatus));
		raftcore.then(CommandManager.literal("destroy")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(FacilityCommands::coreDestroy)));
		raftcore.then(CommandManager.literal("hold")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
								.then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, 600))
										.executes(FacilityCommands::coreHold)))));
		root.then(raftcore);
	}

	// ---------------------------------------------------------------- 筏心

	private static int coreStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(RaftCoreService.status()), false);
		return 1;
	}

	private static int coreDestroy(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		RaftCoreService.markDestroyed(team);
		return CmdSupport.tell(ctx, "队 " + team + " 筏心已置为残骸（30s 沉筏倒计时开始）");
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
			return CmdSupport.tell(ctx, "找不到在线玩家 " + name + "（假玩家也要先 /player <名> spawn）");
		}
		int team = IntegerArgumentType.getInteger(ctx, "team");
		int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
		return CmdSupport.tell(ctx, RaftCoreService.hold(ctx.getSource().getServer(), player, team, ticks));
	}

	/** {@code /cww core status}：各队筏心 HP（有幸存者时破拆 ×0.25）+ 世界坐标（探针定位用）。 */
	private static int coreHpStatus(CommandContext<ServerCommandSource> ctx) {
		var sb = new StringBuilder("[core] ");
		var world = ctx.getSource().getServer().getOverworld();
		for (var team : RoundManager.machine().teams()) {
			int i = team.index();
			var pos = RaftCoreService.coreWorldPos(world, i);
			sb.append(String.format(Locale.ROOT, "队%d HP=%.1f 幸存者=%s 坐标=%s｜",
					i, RaftCoreService.hpOf(i),
					SurvivorService.hasSurvivors(i) ? "有" : "无",
					pos == null ? "取不到" : String.format(Locale.ROOT, "(%.1f,%.1f,%.1f)",
							pos.x, pos.y, pos.z)));
		}
		ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
		return 1;
	}

	/** {@code /cww core break <队> <伤害>}：dev 强制破拆（验证 ×0.25）。 */
	private static int coreHpBreak(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		double dmg = DoubleArgumentType.getDouble(ctx, "damage");
		double dealt = RaftCoreService.breakCore(team, dmg);
		ctx.getSource().sendFeedback(() -> Text.literal(String.format(Locale.ROOT,
				"[cww] 队%d 筏心受击 实际=%.2f 剩=%.1f", team, dealt,
				RaftCoreService.hpOf(team))), false);
		return 1;
	}

	// ---------------------------------------------------------------- 设施武器

	/** {@code /cww ballista status}：本局已放置的炮位与归属。 */
	private static int ballistaStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(BallistaService.status()), false);
		return 1;
	}

	/** {@code /cww ballista place <队>}：dev 在命令源位置放一门并登记归属（setblock 不触发 onPlaced）。 */
	private static int ballistaPlace(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		var src = ctx.getSource();
		var pos = BlockPos.ofFloored(src.getPosition());
		src.getWorld().setBlockState(pos, NbwContent.ballista.getDefaultState());
		BallistaService.register(pos, team);
		ctx.getSource().sendFeedback(() -> Text.literal(
				"[cww] 已在 " + pos.toShortString() + " 放置队 " + team + " 的弩炮"), false);
		return 1;
	}

	/** {@code /cww catapult status}：本局已放置的投石机与归属。 */
	private static int catapultStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(CatapultService.status()), false);
		return 1;
	}

	/** {@code /cww catapult place <队>}：dev 在命令源位置放一门并登记归属。 */
	private static int catapultPlace(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		var src = ctx.getSource();
		var pos = BlockPos.ofFloored(src.getPosition());
		src.getWorld().setBlockState(pos, NbwContent.catapult.getDefaultState());
		CatapultService.register(pos, team);
		ctx.getSource().sendFeedback(() -> Text.literal(
				"[cww] 已在 " + pos.toShortString() + " 放置队 " + team + " 的投石机"), false);
		return 1;
	}

	/** {@code /cww ram status}：本局已放置的撞角与归属。 */
	private static int ramStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(RamService.status()), false);
		return 1;
	}

	/** {@code /cww ram place <队>}：dev 在命令源位置放一个并登记归属。 */
	private static int ramPlace(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		var src = ctx.getSource();
		var pos = BlockPos.ofFloored(src.getPosition());
		src.getWorld().setBlockState(pos, NbwContent.ram.getDefaultState());
		RamService.register(pos, team);
		ctx.getSource().sendFeedback(() -> Text.literal(
				"[cww] 已在 " + pos.toShortString() + " 放置队 " + team + " 的撞角"), false);
		return 1;
	}
}
