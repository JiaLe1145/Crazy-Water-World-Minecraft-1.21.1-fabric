package dev.nbw.cmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.nbw.round.AnnouncementService;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/**
 * 公告命令（从 RicordRailExpress 的 AnnouncementCommand 学来的形态）：
 * {@code post / list / del / clear}。
 *
 * <p>服务端权威 + 落盘（见 {@link AnnouncementService}）；命令层只管参数与回话。
 */
public final class AnnounceCommands {

	private AnnounceCommands() {
	}

	/** 挂到 {@code /cww} 根节点下：{@code /cww announce ...}。 */
	public static void register(LiteralArgumentBuilder<ServerCommandSource> root) {
		var announce = CmdSupport.admin(CommandManager.literal("announce"));
		announce.then(CommandManager.literal("list").executes(AnnounceCommands::list));
		announce.then(CommandManager.literal("post")
				.then(CommandManager.argument("title", StringArgumentType.string())
						.then(CommandManager.argument("body", StringArgumentType.greedyString())
								.executes(AnnounceCommands::post))));
		// 只给标题、不写正文的短公告
		announce.then(CommandManager.literal("short")
				.then(CommandManager.argument("title", StringArgumentType.greedyString())
						.executes(ctx -> doPost(ctx, StringArgumentType.getString(ctx, "title"), ""))));
		announce.then(CommandManager.literal("del")
				.then(CommandManager.argument("index", IntegerArgumentType.integer(1))
						.executes(AnnounceCommands::del)));
		announce.then(CommandManager.literal("clear").executes(ctx -> {
			String r = AnnouncementService.clear(ctx.getSource().getServer());
			return CmdSupport.tell(ctx, r);
		}));
		root.then(announce);
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		for (String line : AnnouncementService.describe()) {
			ctx.getSource().sendFeedback(() -> Text.literal(line), false);
		}
		return 1;
	}

	private static int post(CommandContext<ServerCommandSource> ctx) {
		return doPost(ctx, StringArgumentType.getString(ctx, "title"),
				StringArgumentType.getString(ctx, "body"));
	}

	private static int doPost(CommandContext<ServerCommandSource> ctx, String title, String body) {
		String r = AnnouncementService.publish(ctx.getSource().getServer(),
				stripQuotes(title), stripQuotes(body), ctx.getSource().getName());
		return CmdSupport.tell(ctx, r);
	}

	/** greedyString 会把引号原样带进来（`"正文"` → `"正文"`），这里去掉首尾成对引号。 */
	private static String stripQuotes(String s) {
		if (s == null) {
			return "";
		}
		String t = s.trim();
		if (t.length() >= 2 && ((t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"')
				|| (t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\''))) {
			return t.substring(1, t.length() - 1).trim();
		}
		return t;
	}

	private static int del(CommandContext<ServerCommandSource> ctx) {
		int index = IntegerArgumentType.getInteger(ctx, "index");
		String r = AnnouncementService.remove(ctx.getSource().getServer(), index);
		return CmdSupport.tell(ctx, r);
	}
}
