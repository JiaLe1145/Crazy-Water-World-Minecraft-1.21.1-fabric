package dev.nbw.domain.cosmetic;

import java.util.List;

/**
 * 外观解锁（01 §A11 已批准）：<b>纯外观，无数值</b>——只改显示，不改任何面板。
 *
 * <p>09 §5.6 的红线："禁止局外成长影响武器：外观解锁只能改贴图皮肤，不能改面板"。
 * 本轮先用"称号"落地（不依赖美术资源），后续换成皮肤贴图时，解锁条件与存储都不用改。
 *
 * <p>解锁只看两件事：**胜场**与**击杀**（跨局累计）。阈值刻意取整，方便运维在
 * {@code /cww cosmetic} 里一眼对上。
 */
public final class CosmeticRule {

	/** 一个称号：id（存档用，别改）、显示名、胜场门槛、击杀门槛。 */
	public record Title(String id, String display, int minWins, int minKills) {
	}

	/** 全部称号，按解锁门槛从低到高。第一条必须门槛全 0（新手默认）。 */
	public static final List<Title> TITLES = List.of(
			new Title("novice", "新手", 0, 0),
			new Title("sailor", "水手", 3, 20),
			new Title("captain", "船长", 10, 60),
			new Title("veteran", "老船长", 25, 150),
			new Title("legend", "传奇船长", 60, 400));

	/** 默认称号 id。 */
	public static final String DEFAULT_ID = "novice";

	private CosmeticRule() {
	}

	/** 按 id 找称号；找不到返回 null（调用方给可读报错）。 */
	public static Title byId(String id) {
		if (id == null) {
			return null;
		}
		for (Title t : TITLES) {
			if (t.id().equalsIgnoreCase(id.trim())) {
				return t;
			}
		}
		return null;
	}

	/** 该战绩**已经解锁**的称号（门槛全满足）。至少包含新手。 */
	public static List<Title> unlocked(int wins, int kills) {
		return TITLES.stream()
				.filter(t -> wins >= t.minWins() && kills >= t.minKills())
				.toList();
	}

	/** 是否已解锁指定 id；未知 id 返回 false。 */
	public static boolean isUnlocked(String id, int wins, int kills) {
		Title t = byId(id);
		return t != null && wins >= t.minWins() && kills >= t.minKills();
	}

	/**
	 * 选称号时的校验：能选就返回这个称号；没解锁返回 null。
	 * 未知 id 也返回 null（由调用方区分"不存在"与"没解锁"时再单独查 {@link #byId}）。
	 */
	public static Title pick(String id, int wins, int kills) {
		Title t = byId(id);
		if (t == null || wins < t.minWins() || kills < t.minKills()) {
			return null;
		}
		return t;
	}
}
