package dev.nbw.raft;

import dev.nbw.NBW;
import dev.nbw.content.NbwContent;
import dev.nbw.content.OarBlock;
import dev.nbw.content.OarBlockEntity;
import dev.nbw.domain.raft.OarModel;
import dev.nbw.domain.raft.WindModel;
import dev.nbw.domain.round.PlayerSession;
import dev.nbw.round.RoundManager;
import dev.nbw.sable.SableRafts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 桨台推进的运行时（M2-5，动力第二源）。
 *
 * <p>与 {@link WindService} 的分工要说清：<b>风是全局单值、每 tick 自动作用；
 * 桨是每人一次输入、必须有人持续按着。</b>所以这里的状态是"哪些桨位正被哪些人划着"，
 * 而 {@code WindService} 的状态只有"现在什么风"。两条通道算出的合力最后都乘同一个
 * 航速余量（{@link OarModel#scaledByCap}），因此桨风并用不会越过 0.45 格/tick。
 *
 * <p><b>"一支桨一个人、一个人一支桨"是双向唯一约束</b>，漏掉任何一边都只会悄悄不公平：
 * 少了前者，五个人挤在同一台桨上等于一队多装动力；少了后者，一个客户端对多个桨位续包
 * 就能把力叠在自己身上。判定全在 {@link #keepalive}。
 *
 * <p><b>权威身份在 BE 的 NBT 里，这张表只是运行时索引。</b>桨位与朝向由
 * {@link OarBlockEntity} 第一次被 Sable tick 时调 {@link #registerOar} 回填，
 * 所以重启后不用重新挂桨也不会出现"方块在、桨位表空、怎么按都没动力"。
 * 这也是不用方块坐标当键的原因：sub-level 里两套坐标空间不保证一致，
 * 一旦错位表现就是"按了没反应"且不报日志（同 {@code RaftCoreBlock.onUse}）。
 *
 * <p>划桨的<b>代价</b>目前只有"占用一个人的双手与站位"。02 §营养 批准的"划桨时营养 1.6/min"
 * 要等 M3 营养系统落地再接；在那之前挂机式划桨没有资源惩罚。这条缺口记在
 * {@code docs/dev/M2-RESULTS.md} 的待办里，不假装已实现。
 */
public final class OarService {

	/**
	 * 一队最多几台桨。
	 *
	 * <p>取 5 是几何决定的，不是拍的：桨排在甲板西侧 {@code x=-3} 那一列的 {@code z=-2..2}；
	 * {@code z=-3} 整排是帆的位置（{@link Sailing#rig}），两边抢同一个格子会互相覆盖，
	 * 表现成"装了帆桨就没了"这种极难归因的问题。
	 */
	public static final int MAX_OARS_PER_TEAM = 5;

	/** keepalive 窗口直接沿用长按通道的值：续包频率只能有一个口径。 */
	public static final int KEEPALIVE_TICKS = RaftCoreService.KEEPALIVE_TICKS;

	/**
	 * 一台已挂好的桨：桨位号 + plot 局部偏移 + 推力分量。
	 * 朝向存成分量而不是 {@code Direction}，是为了让重启回填（BE 里只有分量）与
	 * 首次挂桨（来自方块朝向）走同一个形状，不用两边各写一遍换算。
	 */
	public record OarCell(int slot, int x, int y, int z, double dirX, double dirZ) {
	}

	/** 一次正在进行的划桨：谁在划、哪台桨、推力方向、有效到第几 tick。 */
	private record Stroke(UUID player, int team, int slot, double dirX, double dirZ, int untilTick) {
	}

	/** 桨位标识（队号 + 桨位号）。 */
	private record SlotRef(int team, int slot) {
	}

	/** 队号 → 桨位表（运行时索引，由 rig 与 BE 回填共同维护）。 */
	private static final Map<Integer, List<OarCell>> oarsByTeam = new LinkedHashMap<>();

	/** 桨位 → 正在划它的玩家。一支桨只认一个人。 */
	private static final Map<SlotRef, Stroke> strokes = new LinkedHashMap<>();

	/** 玩家 → 他占的桨位。一个人只能划一支桨，防止对多个桨位续包叠力。 */
	private static final Map<UUID, SlotRef> slotOfPlayer = new LinkedHashMap<>();

	/** 桨位 → 认领它的 sub-level 标识。用于把"同号重复筏体"从静默合并变成一条 ERROR。 */
	private static final Map<SlotRef, String> claimer = new LinkedHashMap<>();

	/** 已经报过冲突的桨位，防止每 tick 刷屏。 */
	private static final java.util.Set<SlotRef> claimedConflictReported = new java.util.HashSet<>();

	private static int tick;

	private OarService() {
	}

	// ---------------------------------------------------------------- 挂桨 / 拆桨

	/**
	 * 在甲板西侧挂 n 台桨。
	 *
	 * @return 一行结论；失败给出具体原因而不是静默少挂（与 {@link Sailing#rig} 同一条纪律）
	 */
	public static String rig(ServerWorld world, @Nullable UUID raftId, int teamIndex, int count,
			Direction facing) {
		if (raftId == null) {
			return "这队还没有筏（先 /cww start 或 /cww raft gen）";
		}
		if (NbwContent.oar == null) {
			return "桨台方块还没注册";
		}
		if (facing == null || facing.getAxis() == Direction.Axis.Y) {
			return "桨台朝向只能是 north/south/east/west（给了 " + facing + "）";
		}
		if (count < 0 || count > MAX_OARS_PER_TEAM) {
			return "桨数必须在 0.." + MAX_OARS_PER_TEAM + "，给了 " + count;
		}
		double dirX = facing.getAxis() == Direction.Axis.X ? facing.getOffsetX() : 0.0;
		double dirZ = facing.getAxis() == Direction.Axis.Z ? facing.getOffsetZ() : 0.0;
		List<OarCell> cells = oarsByTeam.computeIfAbsent(teamIndex, k -> new ArrayList<>());
		int placed = 0;
		String failure = "";
		for (int i = 0; i < count; i++) {
			int slot = cells.size();
			if (slot >= MAX_OARS_PER_TEAM) {
				failure = "；已达上限 " + MAX_OARS_PER_TEAM + " 台";
				break;
			}
			int z = slot - 2;   // x=-3 列上 z=-2..2 这五个连续格
			if (!SableRafts.setPlotBlock(world, raftId, -3, 1, z,
					NbwContent.oar.getDefaultState().with(OarBlock.FACING, facing))) {
				failure = "；桨位 " + slot + " 的 plot 写入失败";
				break;
			}
			if (!(SableRafts.plotBlockEntity(world, raftId, -3, 1, z) instanceof OarBlockEntity be)) {
				// 没有 BE 的桨台永远读不到归属：方块在、看着能按，但一句动力都不会有
				failure = "；桨位 " + slot + " 的方块实体没被创建（注册或 BE 类型不匹配）";
				break;
			}
			be.setRig(teamIndex, slot, dirX, dirZ, -3, 1, z);
			cells.add(new OarCell(slot, -3, 1, z, dirX, dirZ));
			placed++;
		}
		NBW.LOGGER.info("[oar] 挂上 {} 台桨 facing={} 队={} 现有 {} 台{}", placed, facing, teamIndex,
				cells.size(), failure);
		return "挂上 " + placed + " 台桨台，朝向 " + facing.asString().toUpperCase(Locale.ROOT)
				+ "（当前桨数 " + cells.size() + "）" + failure;
	}

	/** 拆掉一队所有桨：写回空气，并把还挂在这队桨位上的人一起清掉。 */
	public static int strip(ServerWorld world, @Nullable UUID raftId, int teamIndex) {
		List<OarCell> cells = oarsByTeam.remove(teamIndex);
		dropTeam(teamIndex);
		if (cells == null || cells.isEmpty()) {
			return 0;
		}
		int removed = 0;
		for (OarCell c : cells) {
			if (raftId != null && SableRafts.clearPlotBlock(world, raftId, c.x(), c.y(), c.z())) {
				removed++;
			}
		}
		NBW.LOGGER.info("[oar] 拆掉 {}/{} 台桨，队={}，同时清掉在划的桨位", removed, cells.size(), teamIndex);
		return removed;
	}

	public static int oarCount(int teamIndex) {
		List<OarCell> cells = oarsByTeam.get(teamIndex);
		return cells == null ? 0 : cells.size();
	}

	/**
	 * BE 第一次被 Sable tick 时回填索引（重启恢复走的就是这条路）。
	 *
	 * @param subLevelId 认领这个桨位的 sub-level 标识，由 BE 从 {@code sable$tick} 参数里取
	 * @return false 表示这个桨位身份不合法（手工摆的桨台、脏方向），不占索引
	 */
	public static boolean registerOar(int teamIndex, int slot, double dirX, double dirZ,
			int offX, int offY, int offZ, String subLevelId) {
		if (teamIndex < 0 || slot < 0 || !OarModel.isAxisAligned(dirX, dirZ)) {
			return false;   // 手工摆的桨台没有归属，不占索引
		}
		// 同一个 (队, 桨位) 被两个不同 sub-level 认领时必须吵出来。dev 世界会残留上一局
		// 没清干净的同号筏体（nbw_raft_0 重名），静默合并的现场表现是
		// "表里 2 台、场上 4 个方块、拆桨拆不到"——一句日志都不报的话根本归因不到。
		SlotRef claimedRef = new SlotRef(teamIndex, slot);
		String prior = claimer.putIfAbsent(claimedRef, subLevelId);
		if (prior != null && !prior.equals(subLevelId) && claimedConflictReported.add(claimedRef)) {
			NBW.LOGGER.error("[oar] 队 {} 桨位 {} 已被 sub-level {} 认领，现在 {} 也来认领同一个桨位；"
					+ "场上存在重复筏体（nbw_raft_{} 重名），索引只保留先到者",
					teamIndex, slot, prior, subLevelId, teamIndex);
		}
		List<OarCell> cells = oarsByTeam.computeIfAbsent(teamIndex, k -> new ArrayList<>());
		for (OarCell c : cells) {
			if (c.slot() == slot) {
				if (Math.abs(c.dirX() - dirX) > 1e-9 || Math.abs(c.dirZ() - dirZ) > 1e-9) {
					NBW.LOGGER.error("[oar] 队 {} 桨位 {} 已登记为 ({}, {})，BE 回填的却是 ({}, {})",
							teamIndex, slot, c.dirX(), c.dirZ(), dirX, dirZ);
					return false;
				}
				return true;   // 已经在这张表里，重复回填是幂等的
			}
		}
		if (slot >= MAX_OARS_PER_TEAM) {
			NBW.LOGGER.error("[oar] 队 {} 回填了桨位 {}，超过上限 {}；这台桨不会被计入",
					teamIndex, slot, MAX_OARS_PER_TEAM);
			return false;
		}
		cells.add(new OarCell(slot, offX, offY, offZ, dirX, dirZ));
		return true;
	}

	private static @Nullable OarCell cell(int teamIndex, int slot) {
		List<OarCell> cells = oarsByTeam.get(teamIndex);
		if (cells == null) {
			return null;
		}
		for (OarCell c : cells) {
			if (c.slot() == slot) {
				return c;
			}
		}
		return null;
	}

	/** 某队某推力方向的第一个空桨位；没有返回 -1。 */
	private static int freeSlot(int teamIndex, double dirX, double dirZ) {
		List<OarCell> cells = oarsByTeam.get(teamIndex);
		if (cells == null) {
			return -1;
		}
		for (OarCell c : cells) {
			if (Math.abs(c.dirX() - dirX) < 1e-9 && Math.abs(c.dirZ() - dirZ) < 1e-9
					&& !strokes.containsKey(new SlotRef(teamIndex, c.slot()))) {
				return c.slot();
			}
		}
		return -1;
	}

	// ---------------------------------------------------------------- 输入

	/**
	 * 一次有效的长按输入（客户端续包、方块右键、或 {@link #hold} 登记）。
	 *
	 * <p><b>参数里没有方向</b>：客户端只能选"哪台桨"，推力方向取自挂桨时定下的朝向。
	 * 否则改包就能自造斜向推力，把"各桨之和"的标定整个作废。
	 *
	 * <p>四道闸门（都在"不报错、只悄悄不公平"那一类）：桨位必须属于这队；划的人必须在这队；
	 * 一支桨只认一个人；一个人只占一支桨（想换桨必须先松手）。
	 */
	public static void keepalive(UUID player, int teamIndex, int slot) {
		gate(player, teamIndex, slot, KEEPALIVE_TICKS);
	}

	/**
	 * 划的人必须属于这台桨所在的队；返回 null 表示通过。
	 *
	 * <p>不查这一条的话，敌队可以右键占满你的桨位——自己不得动力，却把你能划的位置全堵住。
	 * 长按包与右键都能指到任意方块坐标，所以这道闸必须在服务端。
	 */
	private static String membershipReason(UUID player, int teamIndex) {
		PlayerSession ps = RoundManager.machine().session(player);
		int myTeam = ps == null ? -1 : ps.teamIndex();
		if (myTeam != teamIndex) {
			return "这人不在队 " + teamIndex + "（他的队号=" + myTeam + "），划不了这队的桨";
		}
		return null;
	}

	/** 登记一次划桨；返回 null 表示成功，否则是"这一下为什么不算"。 */
	private static String gate(UUID player, int teamIndex, int slot, int windowTicks) {
		if (player == null || slot < 0) {
			return "缺在线玩家或桨位号";
		}
		OarCell cell = cell(teamIndex, slot);
		if (cell == null) {
			return "队 " + teamIndex + " 没有桨位 " + slot + "（手工摆的桨台、别队的桨台或已拆掉的桨位）";
		}
		String outsider = membershipReason(player, teamIndex);
		if (outsider != null) {
			return outsider;
		}
		SlotRef ref = new SlotRef(teamIndex, slot);
		Stroke current = strokes.get(ref);
		if (current != null && !current.player().equals(player)) {
			return "这台桨已经有人在划";
		}
		SlotRef mine = slotOfPlayer.get(player);
		if (mine != null && !mine.equals(ref)) {
			return "这个人正在划队 " + mine.team() + " 的桨位 " + mine.slot() + "，换桨得先松手";
		}
		strokes.put(ref, new Stroke(player, teamIndex, slot, cell.dirX(), cell.dirZ(), tick + windowTicks));
		slotOfPlayer.put(player, ref);
		return null;
	}

	/** 玩家主动松手（长按通道的 {@code active=false}）。 */
	public static void release(UUID player) {
		if (player == null) {
			return;
		}
		SlotRef ref = slotOfPlayer.remove(player);
		if (ref != null) {
			strokes.remove(ref);
		}
	}

	/**
	 * dev / 自动化测试入口：模拟某个玩家在某队一台朝该方向的桨上持续按 {@code ticks} tick。
	 *
	 * <p>与真实客户端的区别只有"输入从哪来"：挑一个空桨位后走的仍是 {@link #keepalive}
	 * 背后的同一套登记（{@code gate}），服务端推进路径完全同一条。所以桨力曲线不必等真人
	 * 就验得完（假玩家发不了自定义包，这是本项目已经确认过的边界）。
	 *
	 * @return 一行结论；没有空桨位时给出原因并且<b>不改状态</b>
	 */
	public static String hold(@Nullable UUID player, int teamIndex, String facingName, int ticks) {
		if (player == null) {
			return "必须指定在线玩家";
		}
		if (oarCount(teamIndex) == 0) {
			return "队 " + teamIndex + " 一台桨都没有（先 /cww oar rig " + teamIndex + " <数量> <朝向>）";
		}
		// 顺序刻意如此：先判归属。跨队的人如果收到"没有空着的桨台"，
		// 真正的拒绝理由就被掩盖了 —— 探针与运维都只能看到误导性的结论。
		String outsider = membershipReason(player, teamIndex);
		if (outsider != null) {
			return outsider + "（状态未改动）";
		}
		SlotRef mine = slotOfPlayer.get(player);
		if (mine != null && mine.team() == teamIndex) {
			return "这个人正在划队 " + teamIndex + " 的桨位 " + mine.slot() + "，换桨得先松手（状态未改动）";
		}
		Direction facing;
		try {
			facing = Direction.byName(facingName.toLowerCase(Locale.ROOT));
		} catch (RuntimeException e) {
			return "不认识的方向 " + facingName + "（可用 north/south/east/west；状态未改动）";
		}
		if (facing == null || facing.getAxis() == Direction.Axis.Y) {
			return "朝向只能是 north/south/east/west，给了 " + facingName + "（状态未改动）";
		}
		double dirX = facing.getAxis() == Direction.Axis.X ? facing.getOffsetX() : 0.0;
		double dirZ = facing.getAxis() == Direction.Axis.Z ? facing.getOffsetZ() : 0.0;
		int slot = freeSlot(teamIndex, dirX, dirZ);
		if (slot < 0) {
			return "队 " + teamIndex + " 没有空着的朝 " + facing.asString().toUpperCase(Locale.ROOT)
					+ " 桨台（现有 " + oarCount(teamIndex) + " 台，全被占或朝向不符）";
		}
		String err = gate(player, teamIndex, slot, ticks);
		if (err != null) {
			return "登记失败：" + err + "（状态未改动）";
		}
		return "已登记模拟划桨 桨位 " + slot + " 方向 " + facing.asString().toUpperCase(Locale.ROOT)
				+ " 共 " + ticks + " tick";
	}

	// ---------------------------------------------------------------- 每 tick 推进

	/**
	 * 每 tick 施加，与帆并列在 {@code RoundManager} 的 PLAY 分支里调用。
	 *
	 * @return 本 tick 实际推了几艘筏（给探针与日志用）
	 */
	public static int onEndTick(MinecraftServer server) {
		tick++;
		// 窗口过期：没人续包的桨位自动脱手。"持续"两个字的全部实现就在这里——
		// 输入一断，下一 tick 就没有推力，挂机客户端停发立即失去动力。
		strokes.entrySet().removeIf(e -> {
			boolean expired = e.getValue().untilTick() <= tick;
			if (expired) {
				slotOfPlayer.remove(e.getValue().player());
			}
			return expired;
		});
		if (strokes.isEmpty()) {
			return 0;
		}
		ServerWorld world = server.getOverworld();
		Map<Integer, double[]> sumByTeam = new LinkedHashMap<>();
		for (Stroke s : strokes.values()) {
			sumByTeam.merge(s.team(), OarModel.thrustOf(s.dirX(), s.dirZ()),
					(a, b) -> new double[]{a[0] + b[0], a[1] + b[1]});
		}
		int pushed = 0;
		for (Map.Entry<Integer, double[]> e : sumByTeam.entrySet()) {
			UUID raft = RaftSpawner.raftOf(e.getKey());
			if (raft == null) {
				continue;
			}
			double[] v = SableRafts.linearVelocity(world, raft);
			double[] f = OarModel.scaledByCap(e.getValue()[0], e.getValue()[1], Math.hypot(v[0], v[2]));
			if (Math.abs(f[0]) < 1e-9 && Math.abs(f[1]) < 1e-9) {
				continue;   // 方向相反刚好抵消干净：不该再发一次"零力"推挤
			}
			if (SableRafts.push(world, raft, f[0], 0.0, f[1])) {
				pushed++;
			}
		}
		return pushed;
	}

	/**
	 * 一行读数：桨数、在划的人数、合力、速度、上限与余量。
	 * 探针靠它判断"桨到底改变了什么"，不靠肉眼。
	 */
	public static String readout(MinecraftServer server, int teamIndex) {
		double[] sum = new double[]{0.0, 0.0};
		int rowers = 0;
		for (Stroke s : strokes.values()) {
			if (s.team() != teamIndex) {
				continue;
			}
			double[] t = OarModel.thrustOf(s.dirX(), s.dirZ());
			sum[0] += t[0];
			sum[1] += t[1];
			rowers++;
		}
		UUID raft = RaftSpawner.raftOf(teamIndex);
		double[] v = raft == null ? new double[]{0, 0, 0}
				: SableRafts.linearVelocity(server.getOverworld(), raft);
		double speed = Math.hypot(v[0], v[2]);
		return String.format(Locale.ROOT,
				"[oar] 队%d 桨=%d 在划=%d | 合力=(%.3f, %.3f) | 速度=(%.3f, %.3f) 速率=%.4f"
						+ " | 单桨推力=%.4f 上限=%.3f 余量=%.2f",
				teamIndex, oarCount(teamIndex), rowers, sum[0], sum[1], v[0], v[2], speed,
				OarModel.THRUST_PER_OAR, OarModel.MAX_SPEED, WindModel.headroom(speed, OarModel.MAX_SPEED));
	}

	// ---------------------------------------------------------------- 清理

	/** 桨位与玩家双向清掉；拆桨、筏沉、局后三条路都走这里。 */
	private static void dropTeam(int teamIndex) {
		strokes.entrySet().removeIf(e -> {
			if (e.getKey().team() == teamIndex) {
				slotOfPlayer.remove(e.getValue().player());
				return true;
			}
			return false;
		});
		// 认领表必须一起清：留着旧认领，重新挂桨会被自己的残留挡成"已被别的 sub-level 认领"
		claimer.entrySet().removeIf(e -> e.getKey().team() == teamIndex);
		claimedConflictReported.removeIf(ref -> ref.team() == teamIndex);
	}

	/** 局后清理：桨方块随筏一起销毁，这里只销表，不许跨局残留"还在划"的状态。 */
	public static void reset() {
		oarsByTeam.clear();
		strokes.clear();
		slotOfPlayer.clear();
		claimer.clear();
		claimedConflictReported.clear();
		tick = 0;
	}
}
