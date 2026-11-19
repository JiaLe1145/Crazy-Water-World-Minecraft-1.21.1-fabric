package dev.nbw.cmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.nbw.raft.RaftSinking;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.raft.RemoteService;
import dev.nbw.raft.Sailing;
import dev.nbw.raft.WindService;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;

import java.util.Locale;
import java.util.UUID;

/**
 * 筏与航行命令（从 {@link NbwCommands} 拆出来）：
 * {@code raft}（沉筏运维）、{@code sail}（挂帆/拆帆）、{@code wind}（风）、{@code remote}（遥控器）。
 *
 * <p>这一组全都只碰 {@code raft/} 包的类型，与对局、武器、幸存者没有交叉。
 * 拆分**不改命令树**：注册出来的路径与拆分前逐字一致。
 */
public final class RaftCommands {

	private RaftCommands() {
	}

	/** 挂到 {@code /cww} 根节点下：raft / sail / wind / remote 四组。 */
	public static void register(LiteralArgumentBuilder<ServerCommandSource> root) {
		// ---- 沉筏运维（01 §4.3.1 A3-v4）。命令层只碰 RaftSinking，不碰 Sable 类型。
		var raft = CmdSupport.admin(CommandManager.literal("raft"));
		raft.then(CommandManager.literal("status").executes(RaftCommands::status));
		raft.then(CommandManager.literal("all").executes(RaftCommands::all));
		raft.then(CommandManager.literal("gen")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(RaftCommands::gen)));
		raft.then(CommandManager.literal("bind")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(RaftCommands::bindNearest)
						.then(CommandManager.argument("name", StringArgumentType.word())
								.executes(RaftCommands::bindByName))));
		raft.then(CommandManager.literal("sink")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(RaftCommands::sink)));
		raft.then(CommandManager.literal("weight")
				.then(CommandManager.argument("multiplier", DoubleArgumentType.doubleArg(1.0, 4.0))
						.executes(RaftCommands::weight)));
		root.then(raft);

		// ---- 航行（M2）：挂帆 / 拆帆 / 读数
		var sail = CmdSupport.admin(CommandManager.literal("sail"));
		sail.then(CommandManager.literal("read").executes(RaftCommands::sailRead));
		sail.then(CommandManager.literal("rig")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("count", IntegerArgumentType.integer(0, 7))
								.then(CommandManager.argument("facing", StringArgumentType.word())
										.executes(RaftCommands::sailRig)))));
		sail.then(CommandManager.literal("strip")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(RaftCommands::sailStrip)));
		root.then(sail);

		// ---- 风（M2-5）：全局单值，所有队同一时刻受同一阵风
		var wind = CmdSupport.admin(CommandManager.literal("wind"));
		wind.then(CommandManager.literal("read")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(RaftCommands::windRead)));
		wind.then(CommandManager.literal("set")
				.then(CommandManager.argument("dir", StringArgumentType.word())
						.then(CommandManager.argument("strength", DoubleArgumentType.doubleArg(0.0, 4.0))
								.executes(RaftCommands::windSet))));
		root.then(wind);

		// ---- 遥控器（问题③）：每筏一个，WASD 开船。give / status 给运维与探针取证
		var remote = CmdSupport.admin(CommandManager.literal("remote"));
		remote.then(CommandManager.literal("status")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.executes(RaftCommands::remoteStatus)));
		remote.then(CommandManager.literal("give")
				.then(CommandManager.argument("player", StringArgumentType.word())
						.executes(RaftCommands::remoteGive)));
		// dev/自动化夹具：/cww remote move <队> <keys> <tick>，keys 是 w/s/a/d 的组合（如 wa = 前进+左转）
		remote.then(CommandManager.literal("move")
				.then(CommandManager.argument("team", IntegerArgumentType.integer(0))
						.then(CommandManager.argument("keys", StringArgumentType.word())
								.then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, 600))
										.executes(RaftCommands::remoteMove)))));
		root.then(remote);
	}

	// ---------------------------------------------------------------- raft

	private static int status(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(RaftSinking.status()), false);
		return 1;
	}

	private static int all(CommandContext<ServerCommandSource> ctx) {
		String msg = RaftSpawner.status(ctx.getSource().getServer());
		ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
		return 1;
	}

	private static int gen(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		return CmdSupport.tell(ctx, RaftSpawner.generateOne(ctx.getSource().getServer(), team));
	}

	private static int bindNearest(CommandContext<ServerCommandSource> ctx) {
		var src = ctx.getSource();
		int team = IntegerArgumentType.getInteger(ctx, "team");
		var pos = src.getPosition();
		return CmdSupport.tell(ctx, RaftSinking.bindNearest(src.getWorld(), team, pos.x, pos.y, pos.z));
	}

	private static int bindByName(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		return CmdSupport.tell(ctx, RaftSinking.bindByName(ctx.getSource().getWorld(), team,
				StringArgumentType.getString(ctx, "name")));
	}

	private static int sink(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		return CmdSupport.tell(ctx, RaftSinking.forceSink(ctx.getSource().getWorld(), team));
	}

	private static int weight(CommandContext<ServerCommandSource> ctx) {
		double multiplier = DoubleArgumentType.getDouble(ctx, "multiplier");
		RaftSinking.sinkWeightMultiplier = multiplier;
		return CmdSupport.tell(ctx, "沉筏有效重量倍数 = " + multiplier + "（上限 4.0：实测 6.0 会把筏压穿池底石头）");
	}

	// ---------------------------------------------------------------- sail

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
			return CmdSupport.tell(ctx, "朝向只能是 north/south/east/west，给了 " + raw);
		}
		UUID raft = RaftSpawner.raftOf(team);
		if (raft == null) {
			return CmdSupport.tell(ctx, "队 " + team + " 没有筏；先 /cww start");
		}
		return CmdSupport.tell(ctx, Sailing.rig(ctx.getSource().getServer().getOverworld(), raft, count, facing)
				+ " | " + Sailing.readout(ctx.getSource().getServer(), raft));
	}

	private static int sailStrip(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		UUID raft = RaftSpawner.raftOf(team);
		if (raft == null) {
			return CmdSupport.tell(ctx, "队 " + team + " 没有筏");
		}
		int n = Sailing.deRig(ctx.getSource().getServer().getOverworld(), raft);
		return CmdSupport.tell(ctx, "拆掉 " + n + " 块帆 | " + Sailing.readout(ctx.getSource().getServer(), raft));
	}

	// ---------------------------------------------------------------- wind

	private static int windRead(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		ctx.getSource().sendFeedback(() -> Text.literal(
				WindService.readout(ctx.getSource().getServer(), team)), false);
		return 1;
	}

	private static int windSet(CommandContext<ServerCommandSource> ctx) {
		String dir = StringArgumentType.getString(ctx, "dir");
		double strength = DoubleArgumentType.getDouble(ctx, "strength");
		return CmdSupport.tell(ctx, WindService.set(dir, strength) + " | "
				+ WindService.readout(ctx.getSource().getServer(), 0));
	}

	// ---------------------------------------------------------------- remote

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
			return CmdSupport.tell(ctx, "找不到在线玩家 " + name + "（假玩家也要先 /player <名> spawn）");
		}
		if (dev.nbw.content.NbwContent.remote == null) {
			return CmdSupport.tell(ctx, "遥控器还没注册");
		}
		if (!target.getInventory().insertStack(new ItemStack(dev.nbw.content.NbwContent.remote))) {
			return CmdSupport.tell(ctx, "背包已满，给不进去");
		}
		return CmdSupport.tell(ctx, "已给 " + name + " 遥控器（主手拿着右键进入移动模式，WASD 开船）");
	}

	/** {@code /cww remote move <队> <keys> <tick>}：dev 夹具，模拟"某队按着 w/s/a/d 组合 N tick"。 */
	private static int remoteMove(CommandContext<ServerCommandSource> ctx) {
		int team = IntegerArgumentType.getInteger(ctx, "team");
		String keys = StringArgumentType.getString(ctx, "keys").toLowerCase(Locale.ROOT);
		int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
		return CmdSupport.tell(ctx, RemoteService.hold(team, keys.contains("w"), keys.contains("s"),
				keys.contains("a"), keys.contains("d"), ticks));
	}
}
