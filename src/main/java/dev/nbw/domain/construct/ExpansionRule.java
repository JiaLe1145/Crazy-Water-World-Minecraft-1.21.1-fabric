package dev.nbw.domain.construct;

/**
 * 扩建甲板的材料成本（02 §5：每 1 格甲板 = 4 木 + 1 布）。
 *
 * <p><b>本轮口径（2026-11-14，用户批准"两项都做"）：</b>
 * <ul>
 *   <li>"木" = 木板（{@code #minecraft:planks}），"布" = 线（{@code minecraft:string}）——
 *       选线是因为它已经在海面补给箱的掉落表里，不会让扩建卡在"没有布料来源"。</li>
 *   <li>成本从<b>放置者背包</b>扣，不引入队伍仓库/箱子记账。</li>
 *   <li>放下的是木板时，<b>放下的那块算 4 木里的 1 块</b>，额外扣 3 块；
 *       放下非木板时额外扣 4 块。这样"4 木"是材料总量，不是"放下之外再要 4 块"。</li>
 *   <li>只对<b>甲板层（局部 y=0）且非设施方块</b>收费；设施（帆/桨台/筏心）与上层建筑不收费。</li>
 * </ul>
 *
 * <p>纯函数：背包扣减与方块识别在适配层 {@code construct/BuildService}。
 */
public final class ExpansionRule {

	/** 每格甲板的木板总量（含放下的那块）。 */
	public static final int PLANKS_PER_CELL = 4;

	/** 每格甲板的线数量（"布"）。 */
	public static final int STRING_PER_CELL = 1;

	private ExpansionRule() {
	}

	/**
	 * 放置前还要从背包额外扣几块木板。
	 *
	 * @param placingPlank 放下的方块本身是不是木板（是 → 它已经贡献了 1 块）
	 */
	public static int extraPlanks(boolean placingPlank) {
		return placingPlank ? PLANKS_PER_CELL - 1 : PLANKS_PER_CELL;
	}

	/** 放置前要从背包扣几根线。 */
	public static int stringCost() {
		return STRING_PER_CELL;
	}
}
