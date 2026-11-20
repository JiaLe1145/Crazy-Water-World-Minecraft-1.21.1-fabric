package dev.nbw.cmd;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nbw.NBW;
import dev.nbw.arena.ArenaPool;
import dev.nbw.arena.BuiltinArenaJson;
import dev.nbw.arena.BuiltinConfigJson;
import dev.nbw.arena.MapResetService;
import dev.nbw.arena.SeaTerrain;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.cfg.ConfigService;
import dev.nbw.domain.ZoneModel;
import dev.nbw.domain.arena.SeaAudit;
import dev.nbw.round.RoundManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 地图 / 赛制 / 大厅 / 模板重置 这一组运营命令（从 {@link NbwCommands} 拆出来）。
 *
 * <p>为什么拆：这 9 个方法约 200 行，且只依赖 {@code cfg/arena} 两个包，
 * 与对局、武器、幸存者那几组没有任何交叉——留在 1400 行的 NbwCommands 里只会让人不敢碰。
 * 拆分**不改命令树**：注册出来的路径与拆分前逐字一致。
 */
public final class ArenaCommands {

	private ArenaCommands() {
	}

	/** 挂到 {@code /cww} 根节点下：arena / reset / format / lobby 四组。 */
	public static void register(LiteralArgumentBuilder<ServerCommandSource> root) {
		var arena = CmdSupport.admin(CommandManager.literal("arena"));
		arena.then(CommandManager.literal("list").executes(ArenaCommands::listArenas));
		arena.then(CommandManager.literal("test").then(CommandManager.argument("id", StringArgumentType.word())
				.executes(ctx -> testArena(ctx, StringArgumentType.getString(ctx, "id"), false))));
		arena.then(CommandManager.literal("reload").then(CommandManager.argument("id", StringArgumentType.word())
				.executes(ctx -> testArena(ctx, StringArgumentType.getString(ctx, "id"), true))));
		arena.then(CommandManager.literal("terrain").then(CommandManager.argument("id", StringArgumentType.word())
				.executes(ctx -> arenaTerrain(ctx, StringArgumentType.getString(ctx, "id")))));
		arena.then(CommandManager.literal("pool").executes(ArenaCommands::arenaPool));
		root.then(arena);

		// ---- 模板复制式地图重置（2026-11-08，参照 RicordRailExpress 的 resetTemplateArea 模型）
		var reset = CmdSupport.admin(CommandManager.literal("reset"));
		reset.then(CommandManager.literal("status").executes(ArenaCommands::resetStatus));
		reset.then(CommandManager.literal("buildtemplate").executes(ArenaCommands::resetBuildTemplate));
		root.then(reset);

		var format = CmdSupport.admin(CommandManager.literal("format"));
		format.then(CommandManager.literal("test").then(CommandManager.argument("id", StringArgumentType.word())
				.executes(ArenaCommands::testFormat)));
		// 只加载赛制、不开局：报名上限跟着赛制走，没有这一步就只能沿用上一次的规格
		// （实测探针连着跑：上一局 2x2 规格还在内存里，这一局要 2x4，
		//   第 3 个玩家起就被"队伍 已满"拒掉，看着像 #41 复发）
		format.then(CommandManager.literal("reload").then(CommandManager.argument("id", StringArgumentType.word())
				.executes(ArenaCommands::reloadFormat)));
		root.then(format);

		var lobby = CmdSupport.admin(CommandManager.literal("lobby"));
		lobby.then(CommandManager.literal("test").executes(ctx -> testLobby(ctx, false)));
		lobby.then(CommandManager.literal("reload").executes(ctx -> testLobby(ctx, true)));
		root.then(lobby);
	}

	private static Path worldRoot() {
		return RoundManager.worldRoot();
	}

	// ---------------------------------------------------------------- arena

	private static int listArenas(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		Path dir = worldRoot().resolve("cww").resolve("arenas");
		List<String> ids = new ArrayList<>();
		if (Files.isDirectory(dir)) {
			try (Stream<Path> s = Files.list(dir)) {
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

	/** {@code /cww arena pool}：随机池清单（元数据 + 权重 + 人数 + 可选性）。 */
	private static int arenaPool(CommandContext<ServerCommandSource> ctx) {
		String desc = ArenaPool.describe(worldRoot());
		for (String line : desc.split("\n")) {
			ctx.getSource().sendFeedback(() -> Text.literal(line), false);
		}
		return 1;
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

	// ---------------------------------------------------------------- reset

	/** {@code /cww reset status}：模板复制任务的进度。 */
	private static int resetStatus(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(MapResetService.status()), false);
		return 1;
	}

	/** {@code /cww reset buildtemplate}：按当前地图的 reset 区建一次模板（管理员一次性操作）。 */
	private static int resetBuildTemplate(CommandContext<ServerCommandSource> ctx) {
		var server = ctx.getSource().getServer();
		var world = ctx.getSource().getWorld();
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		if (arena == null) {
			// LOBBY 里还没载入地图：按当前 arenaId 现读一份，才能拿到 reset 区（否则函数落在没加载的区块上）
			ConfigService.Loaded loaded = ConfigService.loadArena(
					worldRoot(), RoundManager.arenaId(), false, BuiltinArenaJson.SOURCE);
			if (loaded.ok()) {
				arena = loaded.arena();
			}
		}
		String where;
		if (arena != null && arena.reset != null && arena.reset.resetTemplateArea != null) {
			MapResetService.forceLoad(world, arena.reset.resetTemplateArea);
			var b = arena.reset.resetTemplateArea;
			where = "模板区 (" + b.minX + "," + b.minY + "," + b.minZ + ")..(" + b.maxX + "," + b.maxY + "," + b.maxZ + ")";
		} else {
			where = "（当前地图没有 reset 配置，按函数内置坐标建）";
		}
		server.getCommandManager().executeWithPrefix(server.getCommandSource(), "function nbw:reset/build_template");
		String finalWhere = where;
		ctx.getSource().sendFeedback(() -> Text.literal("[cww] 已按 nbw:reset/build_template 建模板：" + finalWhere), false);
		return 1;
	}

	// ---------------------------------------------------------------- format / lobby

	private static int testFormat(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		String id = StringArgumentType.getString(ctx, "id");
		try {
			ConfigModels.Format format = ConfigService.loadFormat(worldRoot(), id, BuiltinConfigJson.SOURCE);
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

	/** /cww lobby test|reload：只校验 world/cww/lobby.json（03 §5）；reload 失败才归档。 */
	private static int testLobby(CommandContext<ServerCommandSource> ctx, boolean reload) {
		ConfigService.LobbyLoaded loaded = ConfigService.loadLobby(worldRoot(), reload, BuiltinConfigJson.SOURCE);
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
}
