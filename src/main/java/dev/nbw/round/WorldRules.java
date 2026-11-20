package dev.nbw.round;

import dev.nbw.NBW;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;

/**
 * 世界规则固定器：把主世界锁成"永远正午、不刷怪、不放晴变化"。
 *
 * <p><b>为什么必须有这一层</b>：这是 PvP 竞技图，不是生存服。
 * 2026-11-21 实机测试发现服务端跑着原生规则 ——
 * {@code doDaylightCycle=true}（打到一半天黑）、{@code doMobSpawning=true}（海里刷溺尸/陆上刷怪）、
 * {@code doWeatherCycle=true}（随机下雨）。三样都会直接破坏对局公平性：
 * 夜晚视野清零、怪物抢伤害、雨天远程判定变化。项目此前完全没有这块代码。
 *
 * <p><b>为什么不用 server.properties</b>：那三条是 gamerule，不在 properties 里；
 * 而且换图/重开服务端后必须重新确保，所以放在代码里每次开服 + 每次进 PLAY 都刷一遍。
 *
 * <p><b>风暴事件不受影响</b>：{@code EventService} 的风暴是游戏内状态位，不是原版天气，
 * 这里只关"原版天气自己变化"，风暴该来还是会来。
 */
public final class WorldRules {

	/** 固定时间：正午（6000 = noon）。 */
	public static final long FIXED_TIME = 6000L;

	private WorldRules() {
	}

	/** 开服时调一次；每次进入 PLAY 再调一次（防管理员中途改规则）。 */
	public static void apply(MinecraftServer server) {
		ServerWorld world = server.getOverworld();
		GameRules rules = world.getGameRules();

		setBool(rules, GameRules.DO_DAYLIGHT_CYCLE, false, server);
		world.setTimeOfDay(FIXED_TIME);

		// 自然刷怪 + 三条例外刷怪（巡逻队/流浪商人/幻翼）全部关掉
		setBool(rules, GameRules.DO_MOB_SPAWNING, false, server);
		setBool(rules, GameRules.DO_PATROL_SPAWNING, false, server);
		setBool(rules, GameRules.DO_TRADER_SPAWNING, false, server);
		setBool(rules, GameRules.DO_INSOMNIA, false, server);

		// 原版天气不再自己变，并且立刻放晴
		setBool(rules, GameRules.DO_WEATHER_CYCLE, false, server);
		world.setRainGradient(0.0f);
		world.setThunderGradient(0.0f);

		NBW.LOGGER.info("[world] 规则已固定：正午/不刷怪/不放晴变化（daylight={}, mobSpawning={}, weatherCycle={}）",
				rules.getBoolean(GameRules.DO_DAYLIGHT_CYCLE),
				rules.getBoolean(GameRules.DO_MOB_SPAWNING),
				rules.getBoolean(GameRules.DO_WEATHER_CYCLE));
	}

	private static void setBool(GameRules rules, GameRules.Key<GameRules.BooleanRule> key, boolean value,
			MinecraftServer server) {
		GameRules.BooleanRule rule = rules.get(key);
		if (rule.get() != value) {
			rule.set(value, server);
		}
	}
}
