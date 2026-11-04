package dev.nbw.raft;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.content.NbwContent;
import dev.nbw.content.RaftCoreBlockEntity;
import dev.nbw.round.RoundManager;
import dev.nbw.sable.SableRafts;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 开局发筏（M2-2）：READY 阶段给每支有人的队生成一艘初始 sub-level 筏，
 * 绑到 {@link RaftSinking}，并上一张 {@code nbw:round_active} 加载票。
 *
 * <p><b>收票与收筏是断言项，不是"记得做"。</b>
 * 实测过：Sable 的加载票会跨重启存活（{@code M2-RESULTS.md} §4），
 * 所以漏收一次，那艘筏就被永久钉在内存里，重启也不会释放；
 * plot 网格只有 16384 个槽位，长期服漏个几十局就开始生成失败。
 * {@link #auditResidue} 因此被接进局后残留复检（S10），漏了就报 FAIL。
 */
public final class RaftSpawner {

	/** arena 没配 initialRaftRadius 时的缺省半径：7×7=49 格甲板。 */
	public static final int DEFAULT_RADIUS = 3;
	public static final String DEFAULT_BLOCK = "oak_planks";
	/** 命名约定，与 {@link RaftSinking} 的兜底查找一致。 */
	public static final String NAME_PREFIX = "nbw_raft_";

	/** 队号 → 本局为它生成的筏。 */
	private static final Map<Integer, UUID> spawned = new LinkedHashMap<>();

	private RaftSpawner() {
	}

	/**
	 * 给所有"有成员"的队生成筏。空队不生成——生成一艘没人住的筏只是白占 plot 槽位。
	 *
	 * @return 一行结论，含成功数与每一条失败原因
	 */
	public static String generateForTeams(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		int radius = arena == null || arena.limits == null || arena.limits.initialRaftRadius <= 0
				? DEFAULT_RADIUS : arena.limits.initialRaftRadius;
		String blockId = arena == null || arena.limits == null
				|| arena.limits.initialRaftBlock == null || arena.limits.initialRaftBlock.isEmpty()
				? DEFAULT_BLOCK : arena.limits.initialRaftBlock;

		Block block = resolveBlock(blockId);
		if (block == null) {
			return "初始筏材质无法解析：" + blockId;
		}
		int deckBlocks = SableRafts.deckCount(radius);
		if (arena != null && arena.limits != null && arena.limits.maxBlocksPerRaft > 0
				&& deckBlocks > arena.limits.maxBlocksPerRaft) {
			return "初始筏 " + deckBlocks + " 格超过 limits.maxBlocksPerRaft=" + arena.limits.maxBlocksPerRaft
					+ "，请调小 initialRaftRadius";
		}

		List<String> failures = new ArrayList<>();
		int created = 0;
		for (var team : RoundManager.machine().teams()) {
			if (team.members().isEmpty()) {
				continue;
			}
			int index = team.index();
			var anchor = RoundManager.anchorAt(index);
			if (anchor == null) {
				failures.add("队 " + index + " 没有出生锚点");
				continue;
			}
			// 甲板铺在水面之上一格：贴水铺会让筏子一开局就被浮力顶一下
			double cx = anchor.x() + 0.5, cy = anchor.y() + 1.0, cz = anchor.z() + 0.5;
			String name = NAME_PREFIX + index;
			String[] err = new String[1];
			UUID id = SableRafts.createRaft(world, cx, cy, cz, radius,
					block.getDefaultState(), name, err);
			if (id == null) {
				failures.add("队 " + index + " 生成失败：" + err[0]);
				continue;
			}
			spawned.put(index, id);
			RaftSinking.bind(index, id);
			RaftCoreService.markIntact(index);
			String coreErr = placeCore(world, id, index);
			if (coreErr != null) {
				failures.add("队 " + index + " " + coreErr);
			}
			if (!SableRafts.holdLoaded(world, id)) {
				failures.add("队 " + index + " 加载票没上成功（筏子可能在中途被卸载）");
			}
			created++;
		}
		String tail = failures.isEmpty() ? "" : "；失败：" + String.join(" | ", failures);
		NBW.LOGGER.info("[raftgen] 生成 {} 艘初始筏（半径 {}，甲板 {} 格，材质 {}，票已上）{}",
				created, radius, deckBlocks, blockId, tail);
		return "生成 " + created + " 艘初始筏，半径 " + radius + "（甲板 " + deckBlocks + " 格，材质 "
				+ blockId + "）" + tail;
	}

	/**
	 * 局后清理：收票 → 收筏 → 返回残留清单。
	 *
	 * <p>顺序是先收票再删筏。反过来做的话，删筏时票还挂在容器上，
	 * 而 {@code removeSubLevel} 不会替你清票（实测：重启后票仍在），漏网概率更高。
	 */
	public static List<String> clearAll(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		List<String> residue = new ArrayList<>();
		for (Map.Entry<Integer, UUID> e : spawned.entrySet()) {
			if (!SableRafts.releaseLoaded(world, e.getValue())) {
				residue.add("队 " + e.getKey() + " 加载票未能释放（id=" + e.getValue() + "）");
			}
			if (!SableRafts.remove(world, e.getValue())) {
				residue.add("队 " + e.getKey() + " 的筏没能销毁（id=" + e.getValue() + "）");
			}
		}
		spawned.clear();
		if (!residue.isEmpty()) {
			NBW.LOGGER.error("[raftgen] 局后清理有残留：{}", residue);
		}
		return residue;
	}

	/**
	 * 残留复检：盘上还挂着我们的票、或还留着 {@code nbw_raft_} 命名的筏，就是漏了。
	 *
	 * <p>这一条必须在 {@link #clearAll} 之后调用才有意义，且必须被接进 S10 断言——
	 * 因为加载票跨重启存活，靠"下次记得清"永远清不干净。
	 */
	public static List<String> auditResidue(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		List<String> issues = new ArrayList<>();
		for (String entry : SableRafts.describeAll(world)) {
			if (entry.startsWith(NAME_PREFIX)) {
				issues.add("仍有以 " + NAME_PREFIX + " 命名的筏：" + entry);
			}
		}
		if (!spawned.isEmpty()) {
			issues.add("spawned 映射未清空，仍记着 " + spawned.size() + " 艘");
		}
		return issues;
	}

	/** 运维手工补生成（比如生成失败后不想重开一局）。 */
	public static String generateOne(MinecraftServer server, int teamIndex) {
		ServerWorld world = server.getOverworld();
		var anchor = RoundManager.anchorAt(teamIndex);
		if (anchor == null) {
			return "队 " + teamIndex + " 没有出生锚点（先 /cww start 或确认 arena 配了 spawns）";
		}
		String[] err = new String[1];
		UUID id = SableRafts.createRaft(world, anchor.x() + 0.5, anchor.y() + 1.0, anchor.z() + 0.5,
				DEFAULT_RADIUS, resolveBlock(DEFAULT_BLOCK).getDefaultState(),
				NAME_PREFIX + teamIndex, err);
		if (id == null) {
			return "生成失败：" + err[0];
		}
		spawned.put(teamIndex, id);
		RaftSinking.bind(teamIndex, id);
		RaftCoreService.markIntact(teamIndex);
		SableRafts.holdLoaded(world, id);
		return "队 " + teamIndex + " 已生成筏 " + id + "（半径 " + DEFAULT_RADIUS + "，票已上）";
	}

	public static @Nullable UUID raftOf(int teamIndex) {
		return spawned.get(teamIndex);
	}

	public static Map<Integer, UUID> current() {
		return Map.copyOf(spawned);
	}

	public static String status(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		if (spawned.isEmpty()) {
			return "[raftgen] 本局还没生成过筏";
		}
		StringBuilder sb = new StringBuilder("[raftgen] ");
		for (Map.Entry<Integer, UUID> e : spawned.entrySet()) {
			sb.append("队").append(e.getKey()).append(" Y=")
					.append(String.format(Locale.ROOT, "%.2f", SableRafts.posY(world, e.getValue())))
					.append(" 质量=").append(SableRafts.mass(world, e.getValue())).append("  ");
		}
		return sb.toString().trim();
	}

	/**
	 * 把筏心放进筏体中心的正上方一格。
	 *
	 * <p>用 <b>plot 局部坐标</b> (0,1,0)：筏子会动，世界坐标寻址 sub-level 里的方块
	 * 是本项目已经否掉的做法。中心是甲板原点，(0,1,0) 正好在甲板之上且不埋进方块里。
	 */
	private static String placeCore(ServerWorld world, UUID raftId, int teamIndex) {
		if (NbwContent.raftCore == null) {
			return "筏心方块还没注册，跳过放置";
		}
		if (!SableRafts.setPlotBlock(world, raftId, 0, 1, 0, NbwContent.raftCore.getDefaultState())) {
			return "筏心没能写进 plot";
		}
		if (SableRafts.plotBlockEntity(world, raftId, 0, 1, 0) instanceof RaftCoreBlockEntity be) {
			be.setTeam(teamIndex);
		} else {
			// 没有 BE 的筏心不会 tick，也就永远不会登记 pos→team，修复与破坏全都失效
			return "筏心方块实体没被创建（方块注册或 BE 类型不匹配）";
		}
		return null;
	}

	private static @Nullable Block resolveBlock(String id) {
		Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		return key == null ? null : Registries.BLOCK.get(key);
	}

	/** 给单测与命令层用：把方块 id 解析成 BlockPos 序列这一步不需要真的起服。 */
	public static List<BlockPos> deckPositions(int radius, int cx, int cy, int cz) {
		List<BlockPos> out = new ArrayList<>(SableRafts.deckCount(radius));
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				out.add(new BlockPos(cx + x, cy, cz + z));
			}
		}
		return out;
	}
}
