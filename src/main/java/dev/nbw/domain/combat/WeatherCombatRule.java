package dev.nbw.domain.combat;

/**
 * 风暴对远程武器的影响（02 §10 / 09 §2.2）。
 *
 * <p>设计口径：
 * <ul>
 *   <li>普通远程武器：风暴期间<b>精度 −50%</b>，即每一发有 50% 概率射偏；</li>
 *   <li>火铳：风暴期间<b>完全失效</b>（09 §2.2 的明确反制）；</li>
 *   <li>命中/射偏的随机数由适配层取，纯函数只负责判定，便于单测。</li>
 * </ul>
 *
 * <p>这是全场同等事件：所有队伍、所有远程武器共用同一规则，不制造单队 RNG 收益。
 */
public final class WeatherCombatRule {

	/** 风暴期间远程武器的射偏概率。 */
	public static final double STORM_MISS_CHANCE = 0.5;

	private WeatherCombatRule() {
	}

	/** 火铳在风暴中是否完全失效；其他远程武器不受这条影响。 */
	public static boolean musketDisabledByStorm(boolean stormActive, String weaponId) {
		return stormActive && "musket".equals(weaponId);
	}

	/**
	 * 风暴中这一发是否射偏。
	 *
	 * @param stormActive 风暴是否生效
	 * @param roll01      {@code [0,1)} 的随机数；由调用方从服务端 World 随机源取
	 */
	public static boolean rangedMissesInStorm(boolean stormActive, double roll01) {
		return stormActive && roll01 < STORM_MISS_CHANCE;
	}
}
