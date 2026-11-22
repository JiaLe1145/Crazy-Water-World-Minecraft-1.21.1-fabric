package dev.nbw.domain.raft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * 筏面设施格子的分配规则（初始筏削弱到 4×4 之后必须有这一层）。
 *
 * <p><b>为什么不能再硬编码 {@code (i, 1, -3)}。</b>旧写法把甲板当成 7×7 的奇数方阵
 * （帆铺在 {@code z=-3} 那排、桨铺在 {@code x=-3} 那列）。现在初始筏是 4×4 这种偶数边长，
 * 铺法必须以甲板中心为原点往外数：{@code side=4} 时甲格局部坐标是 {@code x,z ∈ [-2, 1]}，
 * 老的 {@code ±3} 那一圈<b>落在甲板外面</b>——帆与桨贴在空气上，看着挂上了，
 * 踩上去是一格虚空，桨台甚至拿不到方块实体（没有 BE 的桨台一句动力都不会有）。
 *
 * <p><b>帆取第 0、2、4… 排，桨取第 1、3、5… 排</b>（排从尾排往内数）。两类设施因此
 * 永远占不同的格子，不会因"先挂帆还是先挂桨"互相顶掉——旧布局在 {@code (-3,-3)} 那格其实撞了。
 * 筏心固定在 {@code (0, 1, 0)}，取格子时跳过它，所以它的占位也计入设施位（见
 * {@code BuildRules.facilitySlots}）。
 *
 * <p>纯函数：给定边长和序号就给出格子，"4×4 塞不塞得下 7 帆 4 桨"能直接单测，不用起服务端。
 *
 * <p><b>2026-11-23 起支持长方形甲板（4×5）</b>：X 轴用 {@code width}、Z 轴用 {@code length}。
 * 单参数的旧方法一律按正方形（{@code side × side}）委托给新的双参数版本，
 * 这样既有的单测、探针、桨位计算不用改。
 */
public final class RaftLayout {


	/** 设施摆在甲板面上方一格：y=0 是甲板方块本身，埋进去会顶掉甲板。 */
	public static final int FACILITY_Y = 1;

	/** 筏心固定格（01 §4.3.2），任何设施都不许占它。 */
	public static final int CORE_X = 0;
	public static final int CORE_Z = 0;

	/** 帆用的排偏移（0 = 第 0、2、4… 排）。 */
	private static final int SAIL_ROW_OFFSET = 0;

	/** 桨用的排偏移（1 = 第 1、3、5… 排）。 */
	private static final int OAR_ROW_OFFSET = 1;

	private RaftLayout() {
	}

	// ---------------------------------------------------------------- 几何（正方形 = 双参数的特例）

	/** 甲板中心为原点，X 方向（宽度）起始格（偶数往负方向多出一格）。 */
	public static int deckStartX(int width) {
		return -(width >> 1);
	}

	/** X 方向最后一格（含）。 */
	public static int deckEndX(int width) {
		return deckStartX(width) + width - 1;
	}

	/** Z 方向（长度）起始格。 */
	public static int deckStartZ(int length) {
		return -(length >> 1);
	}

	/** Z 方向最后一格（含）。 */
	public static int deckEndZ(int length) {
		return deckStartZ(length) + length - 1;
	}

	/** 正方形甲板的起始格（旧 API，等于 {@link #deckStartX(int)}）。 */
	public static int deckStart(int side) {
		return deckStartX(side);
	}

	/** 正方形甲板的最后一格（旧 API）。 */
	public static int deckEnd(int side) {
		return deckEndX(side);
	}

	/**
	 * footprint 的半边宽：{@code max(-start, end)}，也就是"最远那一侧到中心的距离"。
	 * 扒边判定要的是这个，不是 {@code side/2}——偶数边长时两侧并不对称（4 → 一侧 2、一侧 1）。
	 */
	public static int halfWidth(int side) {
		if (side <= 0) {
			return 0;
		}
		return Math.max(-deckStartX(side), deckEndX(side));
	}

	/** 甲板格数（面积）。 */
	public static int deckCells(int side) {
		return side <= 0 ? 0 : side * side;
	}

	/** 长方形甲板的格数。 */
	public static int deckCells(int width, int length) {
		return width <= 0 || length <= 0 ? 0 : width * length;
	}

	/** 甲板可站面的**实测**局部 Y：站上去以后，脚底 = 筏位姿 Y + 0.295（2026-11-22 两次实测一致）。
	 *
	 * <p>它不是从方块几何推出来的 1.0。<b>这是本项目踩过的坑</b>：甲板方块写在 plot 局部
	 * {@code y=0} 那一层，于是很容易推出"可站面 = 局部 1.0"；但 Sable 的位姿原点并不在甲板底面，
	 * {@link dev.nbw.sable.SableRafts#localToWorld} 的局部 1.0 比真实可站面高约 0.7 格。
	 */
	public static final double DECK_SURFACE_Y = 0.30;

	/** 落位在实测可站面之上再留的余量：宁可多 0.1 格自由落体，也不要"算低压进甲板被顶一下"。 */
	public static final double ENTRY_CLEARANCE = 0.10;

	/**
	 * 开局落位用的甲板局部格（正方形版，等于 {@code entryLocal(side, side)}）。
	 */
	public static double[] entryLocal(int side) {
		return entryLocal(side, side);
	}

	/**
	 * 开局落位用的甲板局部格（{@code {x, y, z}}，脚底坐标）。
	 *
	 * <p><b>为什么要单独有一个"落位格"而不是直接用中心：</b>
	 * 中心 {@code (0,0)} 那一格的正上方 {@code (0,1,0)} 是筏心方块，物资箱也在
	 * {@code (0,1,deckStartZ+1)}。把人放在中心，他的身体就嵌进筏心方块里，原版会沿最小
	 * 穿透轴把人顶出去（顶到筏心上面 = 站在方块顶上，或顶到侧面 = 摔进水里）。
	 *
	 * <p>取**桨族**（奇数排）里、不在中线上的第一格：
	 * <ul>
	 *   <li>桨族不会被自动放置（遥控器取代了多人划桨），不会出现"帆被放进玩家身体里"那种事故
	 *       （实测：7 帆挂满时人直接被顶下海）；</li>
	 *   <li>避开 x=0 中线，是因为物资箱就压在 {@code (0, FACILITY_Y, deckStartZ+1)} 那一格上；</li>
	 *   <li>用 {@code oarCell} 而不是手写坐标：奇数边长与偶数边长的排奇偶性不一样
	 *       （7×7 的 z=-1 是帆排），手写 z 一定会在某张图上撞进帆位。</li>
	 * </ul>
	 *
	 * <p>纯函数：能直接单测"落位格在甲板范围内且不是筏心/物资箱那一格"。
	 */
	public static double[] entryLocal(int width, int length) {
		for (int i = 0; i < oarCapacity(width, length); i++) {
			int[] c = oarCell(width, length, i);
			if (c != null && c[0] != CORE_X) {
				return new double[]{c[0] + 0.5, DECK_SURFACE_Y + ENTRY_CLEARANCE, c[2] + 0.5};
			}
		}
		// 退化甲板（1×1，整张就剩筏心那一格）：只能站中心
		return new double[]{0.5, DECK_SURFACE_Y + ENTRY_CLEARANCE, 0.5};
	}

	/** 局部 XZ 是否落在甲板格内（正方形版）。 */
	public static boolean onDeck(int side, double lx, double lz) {
		return onDeck(side, side, lx, lz);
	}

	/** 局部 XZ 是否落在甲板格内（长方形版）。 */
	public static boolean onDeck(int width, int length, double lx, double lz) {
		if (width <= 0 || length <= 0) {
			return false;
		}
		int bx = (int) Math.floor(lx);
		int bz = (int) Math.floor(lz);
		return bx >= deckStartX(width) && bx <= deckEndX(width)
				&& bz >= deckStartZ(length) && bz <= deckEndZ(length);
	}

	// ---------------------------------------------------------------- 设施格子

	/** 第 {@code index} 面帆的格子（正方形版）。 */
	public static int[] sailCell(int side, int index) {
		return sailCell(side, side, index);
	}

	/** 第 {@code index} 面帆的格子。 */
	public static int[] sailCell(int width, int length, int index) {
		return cell(width, length, index, SAIL_ROW_OFFSET);
	}

	/** 第 {@code index} 台桨台的格子（与帆错排）。 */
	public static int[] oarCell(int side, int index) {
		return oarCell(side, side, index);
	}

	/** 第 {@code index} 台桨台的格子（长方形版）。 */
	public static int[] oarCell(int width, int length, int index) {
		return cell(width, length, index, OAR_ROW_OFFSET);
	}

	/** 帆这一族在这张甲板上最多摆几面（已扣掉被筏心占的那一格）。 */
	public static int sailCapacity(int side) {
		return sailCapacity(side, side);
	}

	public static int sailCapacity(int width, int length) {
		return capacity(width, length, SAIL_ROW_OFFSET);
	}

	/** 桨这一族最多摆几台。 */
	public static int oarCapacity(int side) {
		return oarCapacity(side, side);
	}

	public static int oarCapacity(int width, int length) {
		return capacity(width, length, OAR_ROW_OFFSET);
	}

	/** 正方形甲板上第 {@code index} 个设施格（排从尾排往内数，跳过筏心）。 */
	public static int[] facilityCell(int side, int index) {
		return cell(side, side, index, 0);
	}

	private static int[] cell(int width, int length, int index, int rowOffset) {
		if (width <= 0 || length <= 0 || index < 0) {
			return null;
		}
		int sx = deckStartX(width);
		int ex = deckEndX(width);
		int sz = deckStartZ(length);
		int ez = deckEndZ(length);
		int seen = 0;
		int row = 0;
		for (int z = sz; z <= ez; z++, row++) {
			if (!familyRow(row, rowOffset)) {
				continue;
			}
			for (int x : balancedOrder(sx, ex)) {
				if (x == CORE_X && z == CORE_Z) {
					continue;   // 筏心占的那一格不属于任何设施族
				}
				if (seen == index) {
					return new int[]{x, FACILITY_Y, z};
				}
				seen++;
			}
		}
		return null;
	}

	/**
	 * 一排内 x 的取格顺序：<b>从甲板中心往两边交替</b>，而不是从边到边扫。
	 *
	 * <p>这条顺序不是美观问题。实测（{@code _sail_symmetry.py}，静止基线 0.000 起步、
	 * 南风 2.0、5 秒窗口）用"从 start 扫到 end"的旧顺序时，侧移/顺风位移的比例随帆数单调恶化。
	 * 原因是部分填充时帆的面积中心整排偏到一舷，形成偏航力矩，筏子顶风横爬。
	 * 按中心交替取格之后，<b>任意前缀的面积中心离甲板中心不超过半格</b>（这条已被单测钉住）。
	 */
	private static int[] balancedOrder(int start, int end) {
		double center = (start + end) / 2.0;
		List<Integer> xs = new ArrayList<>();
		for (int x = start; x <= end; x++) {
			xs.add(x);
		}
		// 离甲板中心越近越先取；同距离时先取右侧，这样奇数边长的第一格正好是中心格本身
		xs.sort(Comparator.comparingDouble((Integer x) -> Math.abs(x - center))
				.thenComparingInt(x -> x > center ? 0 : 1));
		int[] out = new int[xs.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = xs.get(i);
		}
		return out;
	}


	private static int capacity(int width, int length, int rowOffset) {
		if (width <= 0 || length <= 0) {
			return 0;
		}
		int sx = deckStartX(width);
		int ex = deckEndX(width);
		int sz = deckStartZ(length);
		int ez = deckEndZ(length);
		int count = 0;
		int row = 0;
		for (int z = sz; z <= ez; z++, row++) {
			if (!familyRow(row, rowOffset)) {
				continue;
			}
			for (int x = sx; x <= ex; x++) {
				if (x == CORE_X && z == CORE_Z) {
					continue;
				}
				count++;
			}
		}
		return count;
	}

	/** 第 row 排（从尾排往内数）是不是属于本设施族。 */
	private static boolean familyRow(int row, int rowOffset) {
		return row >= rowOffset && ((row - rowOffset) & 1) == 0;
	}
}
