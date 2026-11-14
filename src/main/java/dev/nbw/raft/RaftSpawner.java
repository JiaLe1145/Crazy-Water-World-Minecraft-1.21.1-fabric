package dev.nbw.raft;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.content.NbwContent;
import dev.nbw.content.NbwWeapons;
import dev.nbw.content.RaftCoreBlockEntity;
import dev.nbw.domain.raft.RaftLayout;
import dev.nbw.round.RoundManager;
import dev.nbw.sable.SableRafts;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
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

	/** arena 没配 initialRaftSide 时的缺省边长：4×4=16 格甲板（用户 2026-11-06 削弱到这里）。 */
	public static final int DEFAULT_SIDE = 4;
	public static final String DEFAULT_BLOCK = "oak_planks";
	/** 命名约定，与 {@link RaftSinking} 的兜底查找一致。 */
	public static final String NAME_PREFIX = "nbw_raft_";

	/**
	 * 本局某队的筏体唯一名：{@code nbw_raft_<队>_<局>}。
	 *
	 * <p>2026-11-07 起生产环境给筏体唯一名（#38 残留）：崩溃/异常收尾时上一局的
	 * {@code nbw_raft_<队>} 会留在盘上，下一局再生成同名筏就产生重名歧义（"取第一只"会让
	 * 桨位身份、位置读数、加载票回收分别指向不同个体）。名字带 {@code matchIndex} 后每一局
	 * 的名字都不同，重名在源头上被掐掉；残留复检仍按 {@link #NAME_PREFIX} 前缀匹配兜底。
	 */
	public static String raftName(int teamIndex) {
		return NAME_PREFIX + teamIndex + "_" + RoundManager.machine().matchIndex();
	}

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
		String name = raftName(teamIndex);
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
		int side = deckSide();
		String blockId = arena == null || arena.limits == null
				|| arena.limits.initialRaftBlock == null || arena.limits.initialRaftBlock.isEmpty()
				? DEFAULT_BLOCK : arena.limits.initialRaftBlock;

		Block block = resolveBlock(blockId);
		if (block == null) {
			return "初始筏材质无法解析：" + blockId;
		}
		// 上一局/重启残留的旧名是 nbw_raft_<队>_<旧局>，当前局的 clearNameSlot 只认精确名，
		// 清不到它们；旧筏与出生点上的新筏重叠会把新筏顶住/顶歪。开局按前缀统一清一次。
		List<String> stale = SableRafts.removeByPrefix(world, NAME_PREFIX);
		if (!stale.isEmpty()) {
			NBW.LOGGER.warn("[raftgen] 开局清掉 {} 艘上一局/重启残留的旧筏：{}", stale.size(), stale);
		}
		int deckBlocks = SableRafts.deckCount(side);
		if (arena != null && arena.limits != null && arena.limits.maxBlocksPerRaft > 0
				&& deckBlocks > arena.limits.maxBlocksPerRaft) {
			return "初始筏 " + deckBlocks + " 格超过 limits.maxBlocksPerRaft=" + arena.limits.maxBlocksPerRaft
					+ "，请调小 initialRaftSide";
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
			String name = raftName(index);
			String occupied = clearNameSlot(world, index);
			if (occupied != null) {
				failures.add("队 " + index + " " + occupied);
				continue;
			}
			String[] err = new String[1];
			UUID id = SableRafts.createRaft(world, cx, cy, cz, side,
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
			String chestErr = placeSupplyBox(world, id, index, side);
			if (chestErr != null) {
				failures.add("队 " + index + " " + chestErr);
			}
			if (!SableRafts.holdLoaded(world, id)) {
				failures.add("队 " + index + " 加载票没上成功（筏子可能在中途被卸载）");
			}
			created++;
		}
		String tail = failures.isEmpty() ? "" : "；失败：" + String.join(" | ", failures);
		NBW.LOGGER.info("[raftgen] 生成 {} 艘初始筏（边长 {}，甲板 {} 格，材质 {}，票已上）{}",
				created, side, deckBlocks, blockId, tail);
		return "生成 " + created + " 艘初始筏，边长 " + side + "（甲板 " + deckBlocks + " 格，材质 "
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
				DEFAULT_SIDE, resolveBlock(DEFAULT_BLOCK).getDefaultState(),
				raftName(teamIndex), err);
		if (id == null) {
			return "生成失败：" + err[0];
		}
		spawned.put(teamIndex, id);
		RaftSinking.bind(teamIndex, id);
		RaftCoreService.markIntact(teamIndex);
		SableRafts.holdLoaded(world, id);
		return "队 " + teamIndex + " 已生成筏 " + id + "（边长 " + DEFAULT_SIDE + "，票已上）";
	}

	/**
	 * 重启恢复后重新认领筏子。
	 *
	 * <p>为什么需要：sub-level 本身跨重启存活（实测位置与质量都保真），
	 * 但 {@link #spawned} 是运行时映射，没进快照。少了这一步，恢复进 PLAY 的那一局
	 * 会变成"筏子在水里漂但我们的沉筏/挂帆/清理全找不到它"——
	 * 而且 {@code /cww sail rig} 只会回一句"队 0 没有筏"，看不出筏子其实就在眼前。
	 *
	 * <p>靠命名约定 {@code nbw_raft_<队>_<局>} 反查，不额外加一层持久化：
	 * 名字已经写进 sub-level 的存储里，比再存一份 UUID 更不容易与 Sable 自己的状态漂移；
	 * 恢复时 {@code matchIndex} 随快照还原，所以反查名与生成时一致。顺手把加载票也补回来——
	 * 票虽然会跨重启存活，但补一次是幂等的。
	 *
	 * @return 一行结论
	 */
	public static String rebindFromWorld(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		List<String> missing = new ArrayList<>();
		int found = 0;
		for (var team : RoundManager.machine().teams()) {
			int index = team.index();
			UUID id = SableRafts.findByName(world, raftName(index));
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

	/**
	 * 队伍物资箱（01 §4.1）：初始筏角落放一个原版箱子，内含 1 木桨 + 8 木板 + 2 淡水。
	 *
	 * <p>位置取甲板角落的设施层（{@code deckStart, FACILITY_Y, deckStart}）：角落是帆位序列的最后一格，
	 * 4×4 开局只开放 2 个设施位，正常玩家不会把帆摆到那里，物资箱因此不会挡掉"开局两件设施"。
	 *
	 * <p><b>已知边界</b>：原版箱子没有队伍锁——敌人登筏后也能打开。这是"队伍物资箱"的最小实现，
	 * 队伍锁/开箱日志留作后续（01 §4.1 的 A1 待批项）。
	 */
	/**
	 * 物资箱的局部格：x=0、桨族空排（桨台已删）。**不能放筏角**——
	 * 0.5 质量的横向偏置会给筏子一个持续偏航力矩，实测把 7 帆满配的终端速度从
	 * 0.2205 压到 0.1308（`sea_probe` §8），并让 7 帆筏打转。
	 * 中线 + 桨族排列既避开帆位（帆取偶数排），也避开筏心 (0,1,0)。
	 *
	 * @return {@code {x, y, z}}；边长 < 4 的退化筏返回 null（没有不放筏心的中线桨格）
	 */
	public static @Nullable int[] supplyBoxLocal(int side) {
		if (side < 4) {
			return null;
		}
		return new int[]{0, RaftLayout.FACILITY_Y, RaftLayout.deckStart(side) + 1};
	}

	private static String placeSupplyBox(ServerWorld world, UUID raftId, int teamIndex, int side) {
		int[] cell = supplyBoxLocal(side);
		if (cell == null) {
			NBW.LOGGER.warn("[raftgen] 队 {} 初始筏边长 {} 太小，跳过物资箱", teamIndex, side);
			return null;
		}
		int x = cell[0], y = cell[1], z = cell[2];
		if (!SableRafts.setPlotBlock(world, raftId, x, y, z,
				Blocks.CHEST.getDefaultState())) {
			return "物资箱没能写进 plot";
		}
		if (!(SableRafts.plotBlockEntity(world, raftId, x, y, z)
				instanceof ChestBlockEntity chest)) {
			return "物资箱方块实体没被创建（方块实体类型不匹配）";
		}
		int slot = 0;
		var paddle = NbwWeapons.item("paddle");
		if (paddle != null) {
			chest.setStack(slot++, new ItemStack(paddle));
		}
		chest.setStack(slot++, new ItemStack(Items.OAK_PLANKS, 8));
		if (NbwContent.freshWater != null) {
			chest.setStack(slot++, new ItemStack(NbwContent.freshWater, 2));
		}
		chest.markDirty();
		NBW.LOGGER.info("[raftgen] 队 {} 物资箱落位 local=({}, {}, {})，内容 木桨={} 木板=8 淡水={}",
				teamIndex, x, y, z, paddle != null, NbwContent.freshWater != null);
		return null;
	}

	/**
	 * 运维/探针读数：某队物资箱里还有什么（按注册 id 计数）。
	 *
	 * <p>箱子是原版方块实体，内容不在我们的内存表里；读数必须从 plot BE 现读，
	 * 否则"箱子发出来了"与"里面真有东西"会各说各话。
	 */
	public static String supplyStatus(MinecraftServer server, int teamIndex) {
		ServerWorld world = server.getOverworld();
		UUID raft = raftOf(teamIndex);
		if (raft == null) {
			return "队 " + teamIndex + " 没有筏";
		}
		int side = deckSide();
		int[] cell = supplyBoxLocal(side);
		if (cell == null || !(SableRafts.plotBlockEntity(world, raft, cell[0], cell[1], cell[2])
				instanceof ChestBlockEntity chest)) {
			return "队 " + teamIndex + " 的物资箱读不到（可能已被拆掉）";
		}
		StringBuilder sb = new StringBuilder("队").append(teamIndex).append(" 物资箱[");
		for (int i = 0; i < chest.size(); i++) {
			ItemStack stack = chest.getStack(i);
			if (stack.isEmpty()) {
				continue;
			}
			sb.append(Registries.ITEM.getId(stack.getItem()).getPath())
					.append("×").append(stack.getCount()).append(' ');
		}
		return sb.append("]").toString();
	}

	private static @Nullable Block resolveBlock(String id) {
		Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		return key == null ? null : Registries.BLOCK.get(key);
	}

	/**
	 * 这张图发给每队的初始筏边长（4×4 就是现在的默认）。
	 *
	 * <p>帆位、桨位、甲板铺设三处都问这里要同一个数：设施格子由 {@code RaftLayout} 按边长推导，
	 * 一旦谁自己记住"半径 3"，4×4 的筏上就会出现"帆挂在甲板外面的空气里"这种错。
	 */
	public static int deckSide() {
		ConfigModels.Arena arena = RoundManager.arenaOrNull();
		return arena == null || arena.limits == null || arena.limits.initialRaftSide <= 0
				? DEFAULT_SIDE : arena.limits.initialRaftSide;
	}
}
