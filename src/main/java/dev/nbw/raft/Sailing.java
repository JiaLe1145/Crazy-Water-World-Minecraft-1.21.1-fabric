package dev.nbw.raft;

import dev.nbw.NBW;
import dev.nbw.content.NbwContent;
import dev.nbw.content.SailBlock;
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
	private static final Map<Integer, List<int[]>> sailsByTeam = new LinkedHashMap<>();

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
		List<int[]> cells = sailsByTeam.computeIfAbsent(teamIndex, k -> new ArrayList<>());
		int half = count / 2;
		int placed = 0;
		String firstError = null;
		for (int i = -half; i < count - half; i++) {
			// z=-3 是甲板靠尾那一排；y=1 在甲板之上，不会埋进甲板方块里
			if (SableRafts.setPlotBlock(world, raftId, i, 1, -3,
					NbwContent.sail.getDefaultState().with(SailBlock.FACING, facing))) {
				cells.add(new int[]{i, 1, -3});
				placed++;
			} else if (firstError == null) {
				firstError = "plot 写入失败（局部偏移 " + i + ",1,-3）";
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
		List<int[]> cells = sailsByTeam.get(RaftSpawner.teamOf(raftId));
		return cells == null ? 0 : cells.size();
	}

	/** 局后清理：忘掉记录。帆方块本身随筏一起销毁，不需要逐个拆。 */
	public static void forget(int teamIndex) {
		sailsByTeam.remove(teamIndex);
	}

	/** 拆掉所有帆：写回空气，Sable 会从 lift provider 表里移除。 */
	public static int deRig(ServerWorld world, @Nullable UUID raftId) {
		if (raftId == null) {
			return 0;
		}
		List<int[]> cells = sailsByTeam.get(RaftSpawner.teamOf(raftId));
		int removed = 0;
		if (cells != null) {
			for (int[] c : cells) {
				if (SableRafts.clearPlotBlock(world, raftId, c[0], c[1], c[2])) {
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
}
