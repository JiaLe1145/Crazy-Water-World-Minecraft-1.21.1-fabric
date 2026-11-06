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

	/** 已经报过"这队没找到筏"的队号。缺失重试是常态（sub-level 载入晚于恢复），不能每试一次刷一行。 */
	private static final java.util.Set<Integer> missingReported = new java.util.HashSet<>();

	/**
	 * 恢复出来的这一局是否还需要重试认领筏子。
	 *
	 * <p>存在的理由：实测重启时 {@code rebindFromWorld} 跑在 Sable 把 sub-level 载进容器之前
	 * （日志顺序：12:20:57 "重认领 0 艘筏"，12:20:59 那些 sub-level 才开始 tick 自己的方块实体）。
	 * 一次性认领因此必然扑空，恢复出来的对局就"看不见自己的筏"。
	 */
	public static boolean needsRebind() {
		if (RoundManager.machine() == null) {
			return false;
		}
		for (var team : RoundManager.machine().teams()) {
			if (!spawned.containsKey(team.index())) {
				return true;
			}
		}
		return false;
	}

	private RaftSpawner() {
	}

	/**
	 * 生成新筏之前，先把"这个名字已经被谁占了"处理干净。返回 null 表示可以继续生成。
	 *
	 * <p><b>不做这件事的后果是实测过的，不是推测的：</b>上一局如果崩溃或异常收尾，
	 * {@link #spawned} 里已经没有它，但 Sable 的 sub-level 还活在盘上并继续持有
	 * {@code nbw:round_active} 票。于是每开一局就多一只同名 {@code nbw_raft_0}
	 * （最多同时存在 3 只），而 {@link SableRafts#findByName} 与探针的
	 * {@code @e[name=...]} 都<b>只能取第一只</b> —— 桨位身份、位置读数、加载票回收
	 * 分别指向不同个体。现场表现有三层：{@code sea_probe} 的"局后不再挂票"断言直接红、
	 * "重启后桨位自己回来了"是被残骸撑出来的假绿、速率 0.1078 而漂行 0 格。
	 *
	 * <p>残留拆不掉时（多半是它所在的区块还没加载）选择<b>放行 + ERROR</b>：
	 * 为一时清不掉的孤儿把整局开局堵死，运维代价更大。
	 */
	private static String clearNameSlot(ServerWorld world, int teamIndex) {
		UUID tracked = spawned.get(teamIndex);
		if (tracked != null) {
			return "队 " + teamIndex + " 本局已经有筏（id=" + tracked
					+ "），不重复生成——那只会留下一只没人认领的同名筏";
		}
		String name = NAME_PREFIX + teamIndex;
		UUID orphan = SableRafts.findByName(world, name);
		if (orphan == null) {
			return null;
		}
		if (SableRafts.remove(world, orphan)) {
			NBW.LOGGER.warn("[raftgen] 清掉上一局残留的同名筏 {}（id={}），队 {} 可以干净地生成新筏",
					name, orphan, teamIndex);
			return null;
		}
		NBW.LOGGER.error("[raftgen] {} 位置上有只没被记录的残留筏（id={}），现在拆不掉"
				+ "（可能所在区块未加载）；本局仍会生成新筏，但按名字反查会有歧义，请运维处理", name, orphan);
		return null;
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
			String occupied = clearNameSlot(world, index);
			if (occupied != null) {
				failures.add("队 " + index + " " + occupied);
				continue;
			}
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
		for (Integer team : spawned.keySet()) {
			Sailing.forget(team);
		}
		spawned.clear();
		missingReported.clear();   // 下一局的缺失必须还能报出来
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
		UUID tracked = spawned.get(teamIndex);
		if (tracked != null && !SableRafts.present(world, tracked)) {
			// 记着的那只其实已经没了（沉没 / 被 shatter / 被卸载）。
			// 不先丢开这条失效映射，运维补生成会被自己的旧账堵死。
			NBW.LOGGER.warn("[raftgen] 队 {} 记着的筏 {} 已经不在场，丢掉失效映射后重新生成", teamIndex, tracked);
			spawned.remove(teamIndex);
			RaftSinking.forget(teamIndex);
		}
		String occupied = clearNameSlot(world, teamIndex);
		if (occupied != null) {
			return occupied;
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

	/**
	 * 重启恢复后重新认领筏子。
	 *
	 * <p>为什么需要：sub-level 本身跨重启存活（实测位置与质量都保真），
	 * 但 {@link #spawned} 是运行时映射，没进快照。少了这一步，恢复进 PLAY 的那一局
	 * 会变成"筏子在水里漂但我们的沉筏/挂帆/清理全找不到它"——
	 * 而且 {@code /cww sail rig} 只会回一句"队 0 没有筏"，看不出筏子其实就在眼前。
	 *
	 * <p>靠命名约定 {@code nbw_raft_<队>} 反查，不额外加一层持久化：
	 * 名字已经写进 sub-level 的存储里，比再存一份 UUID 更不容易与 Sable 自己的状态漂移。
	 * 顺手把加载票也补回来——票虽然会跨重启存活，但补一次是幂等的。
	 *
	 * @return 一行结论
	 */
	public static String rebindFromWorld(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		List<String> missing = new ArrayList<>();
		int found = 0;
		for (var team : RoundManager.machine().teams()) {
			int index = team.index();
			UUID id = SableRafts.findByName(world, NAME_PREFIX + index);
			if (id == null) {
				// 只报第一次缺失：这一步现在会被 tick 循环重试（sub-level 载入比恢复晚），
				// 不去重的话每 20 tick 就重复一行同样的缺失清单，日志再也读不出东西。
				if (missingReported.add(index)) {
					missing.add("队 " + index);
				}
				continue;
			}
			missingReported.remove(index);
			spawned.put(index, id);
			RaftSinking.bind(index, id);
			SableRafts.holdLoaded(world, id);
			found++;
		}
		String tail = missing.isEmpty() ? "" : "；没找到：" + String.join(" ", missing);
		NBW.LOGGER.info("[raftgen] 重启重认领 {} 艘筏{}", found, tail);
		return "重认领 " + found + " 艘筏" + tail;
	}

	/** 反查：由筏 UUID 找队号。找不到返回 -1。 */
	public static int teamOf(UUID raftId) {
		for (Map.Entry<Integer, UUID> e : spawned.entrySet()) {
			if (e.getValue().equals(raftId)) {
				return e.getKey();
			}
		}
		return -1;
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
