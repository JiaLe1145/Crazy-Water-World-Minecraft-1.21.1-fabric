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

	/** 甲板格数（面积）。 */
	public static int deckCells(int side) {
		return side <= 0 ? 0 : side * side;
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
