package dev.nbw.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 黑海圈层与环带的纯逻辑模型（domain 层，不依赖 Minecraft，可单测）。
 *
 * 设计要点：
 * - 圈进度是"阶段表 + 时间"的纯函数，任何时刻可重算 —— 崩溃恢复与埋点回放都依赖这一点；
 * - 环带（rings）与圈进度相互独立：圈决定"能不能站"，环带决定"物资品质"（原方案要求内圈更高）；
 * - 校验失败必须给出字段级信息，绝不自动纠正（03 §6.2）。
 */
public record ZoneModel(List<Stage> stages, List<Ring> rings, double baseRadius) {

	/**
	 * @param radiusScale 相对 arena.sea.radius 的比例
	 * @param atSecond    该阶段开始收缩的时刻（秒）
	 * @param overSeconds 从开始到到位所需的收缩时长
	 * @param damage      到位后的圈外每秒伤害
	 * @param warnSeconds 提前预告秒数
	 */
	public record Stage(int index, double radiusScale, int atSecond, int overSeconds, double damage, int warnSeconds) {
	}

	/** 环带：[fromRadius, toRadius) 内的物资品质倍数与层级。 */
	public record Ring(double fromRadius, double toRadius, double lootMultiplier, int tier) {
	}

	public static ZoneModel of(List<Stage> stages, List<Ring> rings, double baseRadius) {
		return new ZoneModel(List.copyOf(stages), List.copyOf(rings), baseRadius);
	}

	/** 某时刻的圈半径（线性插值，最后一段持续收缩）。 */
	public double radiusAt(int second) {
		if (stages.isEmpty()) {
			return baseRadius;
		}
		double scale = 1.0;
		for (int i = 0; i < stages.size(); i++) {
			Stage s = stages.get(i);
			if (second < s.atSecond()) {
				break;
			}
			double from = i == 0 ? 1.0 : stages.get(i - 1).radiusScale();
			double progress = s.overSeconds() <= 0 ? 1.0
					: Math.min(1.0, Math.max(0.0, (second - s.atSecond()) / (double) s.overSeconds()));
			scale = from + (s.radiusScale() - from) * progress;
		}
		Stage last = stages.get(stages.size() - 1);
		if (second >= last.atSecond() + last.overSeconds()) {
			// 终局不停圈：按每多 1 秒再收 0.5% 持续压缩，杜绝"各自躲角落拖到超时"
			double extra = (second - last.atSecond() - last.overSeconds()) * 0.005;
			scale = Math.max(0.02, last.radiusScale() - extra);
		}
		return baseRadius * scale;
	}

	/** 某时刻的圈外每秒伤害。 */
	public double damageAt(int second) {
		double damage = 0.0;
		for (Stage s : stages) {
			if (second >= s.atSecond()) {
				damage = s.damage();
			}
		}
		return damage;
	}

	/** 下一次收缩的预告窗口；没有则返回 -1。 */
	public int warnCountdownAt(int second) {
		for (Stage s : stages) {
			int until = s.atSecond() - second;
			if (until > 0 && until <= s.warnSeconds()) {
				return until;
			}
		}
		return -1;
	}

	/** 点到圈心的距离落在哪个环带（不在任何环带返回 null）。 */
	public Ring ringOf(double distance) {
		for (Ring r : rings) {
			if (distance >= r.fromRadius() && distance < r.toRadius()) {
				return r;
			}
		}
		return null;
	}

	/** 玩家是否已在圈外（含边界外才算外）。 */
	public boolean isOutside(double distance, int second) {
		return distance > radiusAt(second);
	}

	/**
	 * 03 §6.1 的校验清单中与圈层相关的部分。返回字段级错误列表，空表示通过。
	 */
	/** 完整校验 = 圈阶段 + 环带。 */
	public List<String> validate() {
		List<String> errors = new ArrayList<>(validateStages());
		errors.addAll(validateRings());
		return errors;
	}

	/** 只校验环带（arena 单独加载时用，此时还没有 format）。 */
	public List<String> validateRings() {
		List<String> errors = new ArrayList<>();
		if (baseRadius <= 0) {
			errors.add("sea.radius 必须 > 0，当前 " + baseRadius);
		}
		double cursor = 0;
		for (int i = 0; i < rings.size(); i++) {
			Ring r = rings.get(i);
			if (r.toRadius() <= r.fromRadius()) {
				errors.add("rings[" + i + "].toRadius 必须大于 fromRadius");
			}
			if (Math.abs(r.fromRadius() - cursor) > 1e-6) {
				errors.add("rings[" + i + "] 存在空洞或重叠：期望 fromRadius=" + cursor + "，实际 " + r.fromRadius());
			}
			cursor = r.toRadius();
		}
		if (!rings.isEmpty() && Math.abs(cursor - baseRadius) > 1e-6) {
			errors.add("rings 最后一段必须覆盖 sea.radius(" + baseRadius + ")，实际到 " + cursor);
		}
		return errors;
	}

	/** 只校验圈阶段（format 单独加载时用）。 */
	public List<String> validateStages() {
		List<String> errors = new ArrayList<>();
		if (stages.isEmpty()) {
			errors.add("border.stages 不能为空");
		}
		for (int i = 0; i < stages.size(); i++) {
			Stage s = stages.get(i);
			if (s.radiusScale() <= 0 || s.radiusScale() > 1) {
				errors.add("border.stages[" + i + "].radiusScale 必须在 (0,1]，当前 " + s.radiusScale());
			}
			if (i > 0) {
				Stage prev = stages.get(i - 1);
				if (s.atSecond() <= prev.atSecond()) {
					errors.add("border.stages[" + i + "].at 必须严格递增，" + prev.atSecond() + " -> " + s.atSecond());
				}
				if (s.radiusScale() > prev.radiusScale()) {
					errors.add("border.stages[" + i + "].radiusScale 不得比上一段大（黑海不能自己涨回去）");
				}
				if (s.damage() < prev.damage()) {
					errors.add("border.stages[" + i + "].damage 必须单调不减");
				}
			}
		}
		Stage last = stages.get(stages.size() - 1);
		if (!stages.isEmpty() && last.radiusScale() <= 0) {
			errors.add("border.stages 最后一段 radiusScale 必须 > 0");
		}

		return errors;
	}
}
