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

	/** 甲板中心为原点，边长 side 的筏面在一根轴上的起始格（偶数边长往负方向多出一格）。 */
	public static int deckStart(int side) {
		return -(side >> 1);
	}

	/** 起始格的对面那一格（含）。 */
	public static int deckEnd(int side) {
		return deckStart(side) + side - 1;
	}

	/**
	 * footprint 的半边宽：{@code max(-start, end)}，也就是"最远那一侧到中心的距离"。
	 * 扒边判定要的是这个，不是 {@code side/2}——偶数边长时两侧并不对称（4 → 一侧 2、一侧 1）。
	 */
	public static int halfWidth(int side) {
		if (side <= 0) {
			return 0;
		}
		return Math.max(-deckStart(side), deckEnd(side));
	}

	/** 甲板格数（面积）。 */
	public static int deckCells(int side) {
		return side <= 0 ? 0 : side * side;
	}

	/**
	 * 甲板可站面的**实测**局部 Y：站上去以后，脚底 = 筏位姿 Y + 0.295（2026-11-22 两次实测一致）。
	 *
	 * <p>它不是从方块几何推出来的 1.0。<b>这是本项目踩过的坑</b>：甲板方块写在 plot 局部
	 * {@code y=0} 那一层，于是很容易推出"可站面 = 局部 1.0"；但 Sable 的位姿原点并不在甲板底面，
	 * {@link dev.nbw.sable.SableRafts#localToWorld} 的局部 1.0 比真实可站面高约 0.7 格。
	 * 用 1.0 当落点，人会从 0.7 格高处落到甲板上（不致命，但"开局掉一下"正是用户抱怨的观感）；
	 * 用实测值 + 一点余量，人几乎是**站上去**而不是掉上去。
	 *
	 * <p>实测方法（写进 M6 计划书）：rtp 到 {@code 位姿+1.05} 与 {@code 位姿+0.45} 两个高度，
	 * 落定后读数相同（{@code 150.325}，位姿 {@code 150.03}），且连续 8 次采样不漂移。
	 */
	public static final double DECK_SURFACE_Y = 0.30;

	/** 落位在实测可站面之上再留的余量：宁可多 0.1 格自由落体，也不要"算低压进甲板被顶一下"。 */
	public static final double ENTRY_CLEARANCE = 0.10;

	/**
	 * 开局落位用的甲板局部格（{@code {x, y, z}}，脚底坐标）。
	 *
	 * <p><b>为什么要单独有一个"落位格"而不是直接用中心：</b>
	 * 中心 {@code (0,0)} 那一格的正上方 {@code (0,1,0)} 是筏心方块，物资箱也在
	 * {@code (0,1,deckStart+1)}。把人放在中心，他的身体就嵌进筏心方块里，原版会沿最小
	 * 穿透轴把人顶出去（顶到筏心上面 = 站在方块顶上，或顶到侧面 = 摔进水里）。
	 * 取中心右边一格（局部 {@code x=+1.5}）就没有任何设施占位。
	 *
	 * <p>退化筏（边长 &lt; 4，中心格就是唯一可站格）退回起始格——这种筏本来也塞不下设施，
	 * 有落脚点比"精确避开筏心"更重要。
	 *
	 * <p>纯函数：能直接单测"落位格在甲板范围内且不是筏心那一格"。
	 */
	public static double[] entryLocal(int side) {
		// 取**桨族**（奇数排）里、不在中线上的第一格：
		//   · 桨族不会被自动放置（遥控器取代了多人划桨，oarCell 现在只有单测在用），
		//     所以不会出现"帆被放进玩家身体里"那种事故（实测：7 帆挂满时人直接被顶下海）；
		//   · 避开 x=0 中线，是因为物资箱就压在 (0, FACILITY_Y, deckStart+1) 那一格上；
		//   · 用 oarCell 而不是手写坐标：奇数边长与偶数边长的排奇偶性不一样（7×7 的 z=-1 是帆排），
		//     手写 z 一定会在某张图上撞进帆位 —— 单测就是这么抓住第一版的。
		for (int i = 0; i < oarCapacity(side); i++) {
			int[] c = oarCell(side, i);
			if (c != null && c[0] != CORE_X) {
				return new double[]{c[0] + 0.5, DECK_SURFACE_Y + ENTRY_CLEARANCE, c[2] + 0.5};
			}
		}
		// 退化甲板（1×1，整张就剩筏心那一格）：只能站中心
		return new double[]{0.5, DECK_SURFACE_Y + ENTRY_CLEARANCE, 0.5};
	}

	/** 局部 XZ 是否落在甲板格内（含边界判定用格子中心，不含"正好踩在边上"）。 */
	public static boolean onDeck(int side, double lx, double lz) {
		if (side <= 0) {
			return false;
		}
		int bx = (int) Math.floor(lx);
		int bz = (int) Math.floor(lz);
		return bx >= deckStart(side) && bx <= deckEnd(side)
				&& bz >= deckStart(side) && bz <= deckEnd(side);
	}

	/**
	 * 第 {@code index} 面帆的格子。
	 *
	 * @return {@code {x, y, z}}；甲板铺不下时返回 null（调用方必须报失败，不许静默少挂）
	 */
	public static int[] sailCell(int side, int index) {
		return cell(side, index, SAIL_ROW_OFFSET);
	}

	/** 第 {@code index} 台桨台的格子（与帆错排，见类注释）。 */
	public static int[] oarCell(int side, int index) {
		return cell(side, index, OAR_ROW_OFFSET);
	}

	/** 帆这一族在这张甲板上最多摆几面（已扣掉被筏心占的那一格）。 */
	public static int sailCapacity(int side) {
		return capacity(side, SAIL_ROW_OFFSET);
	}

	/** 桨这一族最多摆几台。 */
	public static int oarCapacity(int side) {
		return capacity(side, OAR_ROW_OFFSET);
	}

	private static int[] cell(int side, int index, int rowOffset) {
		if (side <= 0 || index < 0) {
			return null;
		}
		int start = deckStart(side);
		int end = deckEnd(side);
		int seen = 0;
		int row = 0;
		for (int z = start; z <= end; z++, row++) {
			if (!familyRow(row, rowOffset)) {
				continue;
			}
			for (int x : balancedOrder(side)) {
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
	 * 南风 2.0、5 秒窗口）用"从 start 扫到 end"的旧顺序时，侧移/顺风位移的比例随帆数单调恶化：
	 * 7×7 筏 1 帆 0.012、4 帆 0.547、7 帆 1.865；4×4 筏 4 帆到了 2.679——
	 * 也就是南风下筏子大半在往西走。原因是部分填充时帆的面积中心整排偏到一舷，
	 * 形成偏航力矩，筏子顶风横爬。按中心交替取格之后，<b>任意前缀的面积中心离甲板中心
	 * 不超过半格</b>（这条已被单测钉住），偏置不会累积。
	 */
	private static int[] balancedOrder(int side) {
		int start = deckStart(side);
		int end = deckEnd(side);
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


	private static int capacity(int side, int rowOffset) {
		if (side <= 0) {
			return 0;
		}
		int start = deckStart(side);
		int end = deckEnd(side);
		int count = 0;
		int row = 0;
		for (int z = start; z <= end; z++, row++) {
			if (!familyRow(row, rowOffset)) {
				continue;
			}
			for (int x = start; x <= end; x++) {
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
