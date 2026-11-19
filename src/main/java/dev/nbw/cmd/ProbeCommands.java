package dev.nbw.cmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nbw.construct.BuildService;
import dev.nbw.net.NbwNetworking;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.sable.SableRafts;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * 探针类命令（从 {@link NbwCommands} 拆出来）：长按通道（{@code net}）与建造规则（{@code build}）。
 *
 * <p>这两组是"给探针取证用"的确定性入口：不瞄准、不放方块，直接调服务端真实判定函数，
 * 把结果原样打出来。它们与玩法逻辑无关，单独成类是自然的。
 *
 * <p>拆分**不改命令树**：注册出来的路径与拆分前逐字一致。
 */
public final class ProbeCommands {

	private ProbeCommands() {
	}

	/** 挂到 {@code /cww} 根节点下：net / build 两组。 */
	public static void register(LiteralArgumentBuilder<ServerCommandSource> root) {
		// ---- 长按通道自身的判定面（M2-8）：坐标口径这种"错了不报、只是永远判不够"的东西，
		// 只能靠把服务端那道判定原样打出来取证。
		// deck/oar 两种写法由我们自己对上筏的局部格，探针里不出现 2×10^7 那种魔数；
		// at 保留给"手工喂一个存储坐标"的排查场合（x/y/z 不做范围限制，存储坐标本来就很大）
		var net = CmdSupport.admin(CommandManager.literal("net"));
		// 逐级 then：三种目标的子节点必须挂在 player 参数下面，挂在 probe 上会让
		// "probe <玩家> deck 0" 解析完玩家就走进死胡同（实测报 Incorrect argument for command）
		var npDeck = CommandManager.literal("deck")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(ProbeCommands::netProbeDeck));
		var npAt = CommandManager.literal("at")
				.then(CommandManager.argument("x", IntegerArgumentType.integer())
						.then(CommandManager.argument("y", IntegerArgumentType.integer())
								.then(CommandManager.argument("z", IntegerArgumentType.integer())
										.executes(ProbeCommands::netProbeAt))));
		var npPlayer = CommandManager.argument("player", StringArgumentType.word());
		npPlayer.then(npDeck);
		npPlayer.then(npAt);
		net.then(CommandManager.literal("probe").then(npPlayer));
		// 包洪泛的自动化替身（Gate G5）：走 hold/move 接收器同一个限速闸门。
		// 它证明"入口接了限速、跨秒重置"，但不是 wire-level 伪造包，证据里要写明区别。
		net.then(CommandManager.literal("flood")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 100000))
								.executes(ProbeCommands::netFlood))));
		root.then(net);

		// ---- 建造规则（M2-6 / 01 §4.2）：上限与归属判定的观测面
		var build = CmdSupport.admin(CommandManager.literal("build"));
		build.then(CommandManager.literal("status").executes(ProbeCommands::buildStatus));
		build.then(CommandManager.literal("last").executes(ProbeCommands::buildLast));
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
		bcBlock.executes(ProbeCommands::buildCheck);
		bcLz.executes(ProbeCommands::buildCheck).then(bcBlock);
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
		bchBlock.executes(ProbeCommands::buildCharge);
		bchLz.then(bchBlock);
		bchLy.then(bchLz);
		bchLx.then(bchLy);
		bchTeam.then(bchLx);
		bchPlayer.then(bchTeam);
		build.then(CommandManager.literal("charge").then(bchPlayer));
		root.then(build);
	}

	// ---------------------------------------------------------------- net

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
		var pos = new BlockPos(IntegerArgumentType.getInteger(ctx, "x"),
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
		var center = SableRafts.accessorOrigin(world, raftId);
		if (center == null) {
			ctx.getSource().sendError(Text.literal("[cww] 队 " + team + " 的筏读不到 plot 中心（已沉没？）"));
			return 0;
		}
		var pos = new BlockPos(center.getX() + lx, center.getY() + ly, center.getZ() + lz);
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] 探测 " + what + "：局部 (" + lx + "," + ly + "," + lz
				+ ") → 存储 (" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
				+ ")（长按包给的就是这一串）"), false);
		return sendProbe(ctx, StringArgumentType.getString(ctx, "player"), pos);
	}

	private static int sendProbe(CommandContext<ServerCommandSource> ctx, String player, BlockPos pos) {
		String line = NbwNetworking.probe(ctx.getSource().getServer(), player, pos);
		ctx.getSource().sendFeedback(() -> Text.literal(line), false);
		return 1;
	}

	// ---------------------------------------------------------------- build

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
		return CmdSupport.tell(ctx, "build charge → " + BuildService.chargeProbe(
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
}
