package dev.nbw.cmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.nbw.rescue.RescueService;
import dev.nbw.rescue.RopeService;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 落水与救援命令（从 {@link NbwCommands} 拆出来）：
 * {@code rescue}（自救/伸手）、{@code rope}（绳索）、{@code grapnel}（钩竿）。
 *
 * <p>这组是"把救援时序、配对结果、拒绝原因原样打出来"的观测面，与 {@code rescue/} 包一一对应。
 * 拆分**不改命令树**：注册出来的路径与拆分前逐字一致。
 */
public final class RescueCommands {

	private RescueCommands() {
	}

	/** 挂到 {@code /cww} 根节点下：rescue / rope / grapnel 三组。 */
	public static void register(LiteralArgumentBuilder<ServerCommandSource> root) {
		// ---- 落水与救援（M2-8 / 01 §8）：自救与伸手救援的时序、配对与拒绝原因的观测面
		var rescue = CmdSupport.admin(CommandManager.literal("rescue"));
		rescue.then(CommandManager.literal("status").executes(RescueCommands::status));
		rescue.then(CommandManager.literal("last").executes(RescueCommands::last));
		rescue.then(CommandManager.literal("hold")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, 600))
								.executes(RescueCommands::hold))));
		// reach 登记在<b>救援方</b>身上：被救者不需要任何输入（2026-11-06 用户裁决）
		rescue.then(CommandManager.literal("reach")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, 600))
								.executes(RescueCommands::reach))));
		root.then(rescue);

		// ---- 抛绳索 / 钩竿（M2 尾巴 / 09 §2.3）：远程道具，一次使用即生效
		var rope = CmdSupport.admin(CommandManager.literal("rope"));
		rope.then(CommandManager.literal("status").executes(RescueCommands::ropeStatus));
		rope.then(CommandManager.literal("cast")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(RescueCommands::ropeCast)));
		var grapnel = CmdSupport.admin(CommandManager.literal("grapnel"));
		grapnel.then(CommandManager.literal("cast")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(RescueCommands::grapnelCast)));
		root.then(rope);
		root.then(grapnel);
	}

	private static int status(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(RescueService.status(ctx.getSource().getServer())), false);
		return 1;
	}

	private static int ropeStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(RopeService.status()), false);
		return 1;
	}

	/** {@code /cww rope cast <玩家>}：模拟"该玩家用绳索抛向最近合格目标"。dev/探针夹具。 */
	private static int ropeCast(CommandContext<ServerCommandSource> ctx) {
		return CmdSupport.tell(ctx, RopeService.cast(ctx.getSource().getServer(),
				StringArgumentType.getString(ctx, "player"), false));
	}

	/** {@code /cww grapnel cast <玩家>}：模拟"该玩家用钩竿拉最近敌方"。dev/探针夹具。 */
	private static int grapnelCast(CommandContext<ServerCommandSource> ctx) {
		return CmdSupport.tell(ctx, RopeService.cast(ctx.getSource().getServer(),
				StringArgumentType.getString(ctx, "player"), true));
	}

	private static int last(CommandContext<ServerCommandSource> ctx) {
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

	private static int hold(CommandContext<ServerCommandSource> ctx) {
		String player = StringArgumentType.getString(ctx, "player");
		int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
		String line = RescueService.hold(ctx.getSource().getServer(), player, ticks);
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + line), true);
		return 1;
	}

	/** 伸手救援的模拟入口：登记在<b>救援方</b>身上，返回行里带着配对了谁、距离几格。 */
	private static int reach(CommandContext<ServerCommandSource> ctx) {
		String player = StringArgumentType.getString(ctx, "player");
		int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
		String line = RescueService.reach(ctx.getSource().getServer(), player, ticks);
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + line), true);
		return 1;
	}
}
