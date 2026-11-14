package dev.nbw.domain.survivor;

import java.util.Map;

/**
 * 幸存者职业（01 §7 / 待批 A6 的第一片）。
 *
 * <p>本切片只落地：
 * <ul>
 *   <li>职业数据、显示名、开局分配与招募补位；</li>
 *   <li><b>生产岗位加成</b>：水手→海水过滤器、厨师→厨台/堆肥、医护→医疗箱，
 *       统一按 02 §9 的“周期 −30%”（倍率 0.7）；</li>
 * </ul>
 *
 * <p><b>明确未做</b>：工匠的建造/修筏加速、舵手的航速/转向加成、卫兵的守筏战斗。
 * 这三条需要新的数值或 AI 行为，等用户单独批准，不在这里偷偷拍板。
 *
 * <p>开局分配顺序把三种生产职业放在最前：1 人队先有水手，2 人队水手+厨师，
 * 3 人队水手+厨师+医护。这样 4×4 初始筏在标准 3 人队下能立刻跑通水/食物/医疗三条生产链，
 * 而不是被工匠/舵手占掉名额。
 */
public enum SurvivorJob {

	SAILOR("水手"),
	COOK("厨师"),
	MEDIC("医护"),
	CRAFTSMAN("工匠"),
	HELMSMAN("舵手"),
	GUARD("卫兵");

	private final String displayName;

	SurvivorJob(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}

	/** 开局第 {@code index} 名幸存者的职业（循环取，生产职业优先）。 */
	public static SurvivorJob initialByIndex(int index) {
		SurvivorJob[] all = values();
		return all[Math.floorMod(index, all.length)];
	}

	/**
	 * 招募补位：选当前人数最少的职业；并列时按 enum 顺序（生产职业优先）。
	 * 空 map 返回水手。
	 */
	public static SurvivorJob leastRepresented(Map<SurvivorJob, Integer> counts) {
		SurvivorJob best = SAILOR;
		int bestCount = Integer.MAX_VALUE;
		for (SurvivorJob job : values()) {
			int n = counts == null ? 0 : counts.getOrDefault(job, 0);
			if (n < bestCount) {
				best = job;
				bestCount = n;
			}
		}
		return best;
	}
}
