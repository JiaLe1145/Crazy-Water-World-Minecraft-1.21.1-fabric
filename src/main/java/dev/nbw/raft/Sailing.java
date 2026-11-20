package dev.nbw.raft;

import dev.nbw.NBW;
import dev.nbw.content.NbwContent;
import dev.nbw.content.SailBlock;
import dev.nbw.domain.raft.RaftLayout;
import dev.nbw.sable.SableRafts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 挂帆与航行读数（M2 航行入口）。
 *
 * <p>帆写在 <b>plot 局部坐标</b> 上：甲板在 y=0，所以帆排在 y=1 那一层、
 * 沿 z 负方向那一排居中展开。筏子会动，任何用世界坐标寻址帆的做法都会随重启失效
 * （这是本项目已经明确否掉的路子）。
 *
 * <p>这一层刻意<b>不</b>尝试推进：Sable 的默认维度物理没有风向矢量，
 * 帆在静风下是阻力面。真要推进得靠桨/螺旋桨（要客户端输入）或我们自己往
 * dimension physics 里加风场——两条都还没批准，先把帆的阻力作用测出来再说。
 */
public final class Sailing {

	/** 一排帆最多几块。超过这个数就超出 7×7 甲板，会往 plot 外扩块。 */
	public static final int MAX_SAILS_PER_ROW = 7;

	/**
	 * 队号 → 已挂帆的 plot 局部坐标。
	 *
	 * <p><b>为什么非要自己记：</b>一开始的实现是扫 {@code plot.getBoundingBox()} 的三重循环
	 * 来数帆和拆帆，结果把服务端冻了 5.2 秒（日志 {@code Can't keep up! ... 103 ticks behind}）。
	 * 原因是 plot 的局部坐标不是我们直觉的 -3..3——它是相对 plot 网格槽位的大数
	 * （实测筏心 BE 的 getPos() 是 x=20481032 这种量级），包围盒跨度可以到几百万格，
	 * 三重循环就是灾难。
	 *
	 * <p>所以：写方块时用"我们传进去的局部偏移"记下来，数与拆都只遍历这张小表。
	 * 表随对局生命周期建与销（{@link #forget}），不跨局残留。
	 */
	private static final Map<Integer, List<SailCell>> sailsByTeam = new LinkedHashMap<>();

	/** 一块帆：plot 局部偏移 + 法向朝向。朝向必须记着，否则算不出风对哪面吹。 */
	public record SailCell(int x, int y, int z, Direction facing) {
	}

	private Sailing() {
	}

	/**
	 * 在筏尾挂一排 n 块帆。
	 *
	 * @return 一行结论；失败时给出具体原因而不是静默少挂
	 */
	public static String rig(ServerWorld world, @Nullable UUID raftId, int count, Direction facing) {
		if (raftId == null) {
			return "这队还没有筏（先 /cww start 或 /cww raft gen）";
		}
		if (NbwContent.sail == null) {
			return "帆方块还没注册";
		}
		if (count < 0 || count > MAX_SAILS_PER_ROW) {
			return "帆数必须在 0.." + MAX_SAILS_PER_ROW + "，给了 " + count;
		}
		int teamIndex = RaftSpawner.teamOf(raftId);
		List<SailCell> cells = sailsByTeam.computeIfAbsent(teamIndex, k -> new ArrayList<>());
		// 帆位不再写死 (i,1,-3)：4×4 的筏上 z=-3 那一圈根本不在甲板上，
		// 挂上去的帆只是贴在空气里的方块。格子一律由 RaftLayout 按边长推导。
		int side = RaftSpawner.deckSide();
		int capacity = RaftLayout.sailCapacity(side);
		if (cells.size() + count > capacity) {
			return "这张筏（边长 " + side + "）最多摆 " + capacity + " 面帆，现有 " + cells.size()
					+ " 面，再加 " + count + " 面塞不下";
		}
		int placed = 0;
		String firstError = null;
		// 基准必须在循环外取一次：cells 会在循环里 append，写在循环里取会让
		// 第 5 面帆拿到序号 8（实测 sea_probe 因此"挂上 4 块帆…帆位 8 算不出格子"）
		int base = cells.size();
		for (int i = 0; i < count; i++) {
			int slot = base + i;
			int[] cell = RaftLayout.sailCell(side, slot);
			if (cell == null) {
				firstError = "帆位 " + slot + " 算不出格子（边长 " + side + " 的甲板铺不下）";
				break;
			}
			if (SableRafts.setPlotBlock(world, raftId, cell[0], cell[1], cell[2],
					NbwContent.sail.getDefaultState().with(SailBlock.FACING, facing))) {
				cells.add(new SailCell(cell[0], cell[1], cell[2], facing));
				placed++;
			} else if (firstError == null) {
				firstError = "plot 写入失败（局部偏移 " + cell[0] + "," + cell[1] + "," + cell[2] + "）";
			}
		}
		String tail = firstError == null ? "" : "；首个失败：" + firstError;
		NBW.LOGGER.info("[sail] 挂上 {} 块帆 facing={} 筏={}", placed, facing, raftId);
		return "挂上 " + placed + " 块帆，朝向 " + facing.asString() + "（当前帆数 "
				+ sailCount(world, raftId) + "）" + tail;
	}

	/** 数一数筏上现在有几块帆（读自己记的表，不扫包围盒）。 */
	public static int sailCount(ServerWorld world, @Nullable UUID raftId) {
		if (raftId == null) {
			return 0;
		}
		List<SailCell> cells = sailsByTeam.get(RaftSpawner.teamOf(raftId));
		return cells == null ? 0 : cells.size();
	}

	/** 各朝向的帆数，给 {@link WindService} 算合力用。 */
	public static int sailsFacing(int teamIndex, Direction facing) {
		List<SailCell> cells = sailsByTeam.get(teamIndex);
		if (cells == null) {
			return 0;
		}
		int n = 0;
		for (SailCell c : cells) {
			if (c.facing() == facing) {
				n++;
			}
		}
		return n;
	}

	/** 局后清理：忘掉记录。帆方块本身随筏一起销毁，不需要逐个拆。 */
	public static void forget(int teamIndex) {
		sailsByTeam.remove(teamIndex);
	}

	/**
	 * 玩家<b>手动</b>放下一块帆（{@code SailBlock.onPlaced} 调这里）。
	 *
	 * <p>2026-11-21 补：此前帆只能由 {@code /cww sail rig}（权限 2）挂上去，
	 * 于是真实对局里<b>玩家永远挂不了帆</b>——而帆是这套玩法唯一的推进手段
	 * （桨台只剩装饰），等于整局都动不了。现在背包里有 {@code nbw:sail} 直接往甲板上一放就算数。
	 *
	 * <p>同一格重复登记只留一条（先拆后放的顺序可能先把记录摘掉，不需要额外去重，
	 * 但 {@code setBlockState} 与 {@code onPlaced} 之间没有强顺序保证，去重更保险）。
	 *
	 * @param teamIndex 筏所属队（由调用方从 raftId 反查，-1 表示不是任何队的筏，直接不登记）
	 * @return 是否登记成功
	 */
	public static boolean registerPlaced(int teamIndex, int lx, int ly, int lz, Direction facing) {
		if (teamIndex < 0) {
			return false;
		}
		List<SailCell> cells = sailsByTeam.computeIfAbsent(teamIndex, k -> new ArrayList<>());
		for (SailCell c : cells) {
			if (c.x() == lx && c.y() == ly && c.z() == lz) {
				return false;   // 这一格已经记着了
			}
		}
		cells.add(new SailCell(lx, ly, lz, facing));
		NBW.LOGGER.info("[sail] 手放登记 队={} 局部=({},{},{}) facing={} 当前 {} 面",
				teamIndex, lx, ly, lz, facing, cells.size());
		return true;
	}

	/**
	 * 手动拆掉一块帆（{@code SailBlock.onStateReplaced} 调这里）。
	 *
	 * <p>不摘记录的话，拆了帆风速还按旧帆数算——玩家会发现"帆拆了船还受力"。
	 *
	 * @return 是否真的摘掉了一条记录
	 */
	public static boolean unregisterPlaced(int teamIndex, int lx, int ly, int lz) {
		if (teamIndex < 0) {
			return false;
		}
		List<SailCell> cells = sailsByTeam.get(teamIndex);
		if (cells == null) {
			return false;
		}
		boolean removed = cells.removeIf(c -> c.x() == lx && c.y() == ly && c.z() == lz);
		if (removed) {
			NBW.LOGGER.info("[sail] 手拆注销 队={} 局部=({},{},{}) 剩 {} 面",
					teamIndex, lx, ly, lz, cells.size());
		}
		return removed;
	}

	/**
	 * 拆掉所有帆：写回空气，Sable 会从 lift provider 表里移除。
	 *
	 * <p><b>2026-11-21 修</b>：这里原来直接 {@code for (SailCell c : cells)} 里调
	 * {@code clearPlotBlock}。清方块会触发 {@code SailBlock.onStateReplaced} →
	 * {@link #unregisterPlaced} 从同一张表里摘记录，等于边遍历边改集合，
	 * 下一条就 {@code ConcurrentModificationException}。改成先拷贝再遍历，最后统一清表。
	 */
	public static int deRig(ServerWorld world, @Nullable UUID raftId) {
		if (raftId == null) {
			return 0;
		}
		List<SailCell> cells = sailsByTeam.get(RaftSpawner.teamOf(raftId));
		int removed = 0;
		if (cells != null) {
			for (SailCell c : new ArrayList<>(cells)) {
				if (SableRafts.clearPlotBlock(world, raftId, c.x(), c.y(), c.z())) {
					removed++;
				}
			}
			cells.clear();
		}
		NBW.LOGGER.info("[sail] 拆掉 {} 块帆，筏={}", removed, raftId);
		return removed;
	}

	/**
	 * 一行航行读数：速度、质量、帆数。
	 * 探针与运维都靠它判断"帆到底改变了什么"，不靠肉眼。
	 */
	public static String readout(MinecraftServer server, @Nullable UUID raftId) {
		if (raftId == null) {
			return "[sail] 无筏";
		}
		ServerWorld world = server.getOverworld();
		double[] v = SableRafts.linearVelocity(world, raftId);
		return String.format("[sail] 速度=(%.3f, %.3f, %.3f) 速率=%.3f 帆=%d 质量=%.1f Y=%.2f",
				v[0], v[1], v[2], Math.hypot(v[0], v[2]), sailCount(world, raftId),
				SableRafts.mass(world, raftId), SableRafts.posY(world, raftId));
	}

	/**
	 * 局后清理：帆表（队 → 帆格）必须清，否则下一局：
	 * <ul>
	 *   <li>{@code sail read} 会报上一局的帆数（实体早被重生成，读数是假的）；</li>
	 *   <li>{@code rig} 的容量判断拿旧 cells 当基准，明明空船却说"摆不下"。</li>
	 * </ul>
	 * 2026-11-20 复查发现：这张表只有 deRig 会摘条目，整局结束不会走到 deRig。
	 */
	public static void reset() {
		sailsByTeam.clear();
	}
}
