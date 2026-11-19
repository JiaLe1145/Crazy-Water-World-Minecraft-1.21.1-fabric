package dev.nbw.cmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nbw.NBW;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/**
 * 子命令类共用的三条小工具（从 {@link NbwCommands} 提出来的）。
 *
 * <p>拆命令时最容易被复制粘贴的就是"加权限 / 成功回话 / 成败回话"这三行；
 * 集中在这里，各命令类只关心自己的业务。
 */
final class CmdSupport {

	private CmdSupport() {
	}

	/** 管理员节点：权限等级 2。 */
	static <T extends LiteralArgumentBuilder<ServerCommandSource>> T admin(T node) {
		node.requires(source -> source.hasPermissionLevel(2));
		return node;
	}

	/** 一律回话（成功也回、失败也回），不抛异常——运维要的是可读结果不是堆栈。 */
	static int tell(CommandContext<ServerCommandSource> ctx, String message) {
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + message), true);
		NBW.LOGGER.info("[cmd] {} (source={})", message, ctx.getSource().getName());
		return 1;
	}

	/** 按 `error == null` 决定成功/失败；成功返回 1、失败返回 0（探针断返回码）。 */
	static int reply(CommandContext<ServerCommandSource> ctx, String error, String okMessage)
			throws CommandSyntaxException {
		if (error == null) {
			ctx.getSource().sendFeedback(() -> Text.literal("[cww] " + okMessage), true);
			NBW.LOGGER.info("[cmd] {} (source={})", okMessage, ctx.getSource().getName());
			return 1;
		}
		ctx.getSource().sendError(Text.literal("[cww] " + error));
		NBW.LOGGER.warn("[cmd] rejected: {} (source={})", error, ctx.getSource().getName());
		return 0;
	}
}
