package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.loot.LootService;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * 事件池（M5-1）：按 {@code format.events} 的池与间隔，在 PLAY 里定时抽一个事件触发。
 *
 * <p>调度分两段：到点先选中事件并<b>提前 8 秒预告</b>（01 §9 硬约束：5–10s，公告 + 音效），
 * 预告结束才真正生效。预告事件在选中那一刻就定死，玩家看到的名字与最终触发的一定是同一个。
 *
 * <p>已接实际效果：supply_rain（补箱）、storm（禁航 + 远程失准 + 压暗）、
 * black_sea_surge（圈外伤害 ×1.5）、survivor_drift（海面漂出 3–4 名幸存者）。
 * kraken / bounty 仍只广播，等实体与悬赏结算落地（M5 尾巴，不是漏做）。
 */
public final class EventService {

	/** 预告提前量：01 §9 要求 5–10s，取中值 8s。 */
	public static final int WARN_TICKS = 8 * 20;
	/** 幸存者聚集点的人数区间（02 §10：3–4 名）。 */
	private static final int SURVIVOR_MIN = 3;
	private static final int SURVIVOR_MAX = 4;

	private static List<String> pool = List.of();
	private static int minSec = 150;
	private static int maxSec = 240;
	private static long nextFireTick;
	/** 已选中、正在预告的事件（空串 = 还没选）。 */
	private static String pendingEvent = "";
	private static long pendingFireTick;
	private static Random rnd = new Random();
	private static String lastEvent = "";
	private static int fired;
	/** 当前生效的事件与到期 tick（同刻最多 1 个，02 §10）。 */
	private static String activeEvent = "";
	private static long activeUntilTick;

	private EventService() {
	}

	/** PLAY 开始：读池与间隔，排第一发。 */
	public static void begin(MinecraftServer srv) {
		ConfigModels.Format f = RoundManager.formatOrNull();
		pool = f != null && f.events != null && f.events.pool != null ? f.events.pool : List.of();
		minSec = 150;
		maxSec = 240;
		if (f != null && f.events != null && f.events.intervalSec != null && f.events.intervalSec.size() >= 2) {
			minSec = Math.max(1, f.events.intervalSec.get(0));
			maxSec = Math.max(minSec, f.events.intervalSec.get(1));
		}
		rnd = new Random(srv.getOverworld().getRandom().nextLong());
		fired = 0;
		lastEvent = "";
		activeEvent = "";
		activeUntilTick = 0;
		pendingEvent = "";
		pendingFireTick = 0;
		scheduleNext(srv);
		NBW.LOGGER.info("[event] 事件池 {} 个，间隔 {}-{}s，第一发 tick={}",
				pool.size(), minSec, maxSec, nextFireTick);
	}

	/** PLAY 每 tick：清过期事件；到点先预告、8 秒后触发（同刻最多 1 个，02 §10）。 */
	public static void onEndTick(MinecraftServer srv) {
		long now = srv.getTicks();
		if (!activeEvent.isEmpty() && now >= activeUntilTick) {
			announce(srv, "§7[事件] " + displayName(activeEvent) + " 结束");
			activeEvent = "";
			activeUntilTick = 0;
		}
		if (stormActive()) {
			// 风暴的视野压暗是持续效果：即使事件池为空（管理员强制触发）也必须继续 tick。
			tickStormEffects(srv);
		}
		if (pool.isEmpty()) {
			return;
		}
		if (!activeEvent.isEmpty()) {
			return;   // 已有事件在生效，不叠加；预告也等它结束
		}
		if (pendingEvent.isEmpty()) {
			if (now < nextFireTick) {
				return;
			}
			// 到点只“选中并预告”，真正生效在 WARN_TICKS 之后。
			pendingEvent = pool.get(rnd.nextInt(pool.size()));
			pendingFireTick = now + WARN_TICKS;
			warn(srv, pendingEvent);
			NBW.LOGGER.info("[event] 预告 {}，{} tick 后触发", pendingEvent, WARN_TICKS);
			return;
		}
		if (now < pendingFireTick) {
			return;
		}
		String event = pendingEvent;
		pendingEvent = "";
		pendingFireTick = 0;
		fire(srv, event);
		scheduleNext(srv);
		NBW.LOGGER.info("[event] 下一发 tick={}", nextFireTick);
	}

	/** 风暴期间每秒刷新一次 DARKNESS（02 §10 的“视野 −30%”在当前效果体系里的近似）。 */
	private static void tickStormEffects(MinecraftServer srv) {
		if (srv.getTicks() % 20 != 0) {
			return;
		}
		for (UUID id : RoundManager.participantIds()) {
			ServerPlayerEntity p = srv.getPlayerManager().getPlayer(id);
			if (p != null && !p.isSpectator()) {
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 40, 0, false, false, false));
			}
		}
	}

	private static void scheduleNext(MinecraftServer srv) {
		int sec = minSec >= maxSec ? minSec : minSec + rnd.nextInt(maxSec - minSec + 1);
		nextFireTick = srv.getTicks() + (long) sec * 20;
	}

	/**
	 * 预告：公告 + 音效（01 §9 的硬约束）。名字在选中时已定死，预告与触发不会对不上。
	 *
	 * <p>声音是每个参赛者各放一次，不用 {@code playSound(Player)} 的客户端重载——
	 * 这条路径在专用服务端上也要能响，且探针不需要客户端就能在日志里看到公告。
	 */
	private static void warn(MinecraftServer srv, String event) {
		announce(srv, "§e[预告] " + (WARN_TICKS / 20) + "s 后：" + displayName(event) + effectHint(event));
		for (UUID id : RoundManager.participantIds()) {
			ServerPlayerEntity p = srv.getPlayerManager().getPlayer(id);
			if (p != null) {
				p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(),
						SoundCategory.PLAYERS, 1.0f, 1.5f);
			}
		}
	}

	/** 事件的中文名（预告、结束公告与 {@code /cww event} 共用）。 */
	public static String displayName(String event) {
		if (event == null) {
			return "？";
		}
		return switch (event) {
			case "supply_rain" -> "物资雨";
			case "storm" -> "风暴";
			case "kraken" -> "海怪袭击";
			case "black_sea_surge" -> "黑海倒流";
			case "survivor_drift" -> "幸存者聚集点";
			case "bounty" -> "悬赏令";
			default -> event;
		};
	}

	/** 预告里的一句效果提示；只做提示，不改判定。 */
	private static String effectHint(String event) {
		return switch (event) {
			case "supply_rain" -> "（圈内刷补给箱）";
			case "storm" -> "（45s 航行失效 / 远程失准 / 视野压暗）";
			case "kraken" -> "（海怪出没）";
			case "black_sea_surge" -> "（边界外扩 10% / 圈外伤害 ×1.5）";
			case "survivor_drift" -> "（海面漂出 3–4 名幸存者）";
			case "bounty" -> "（标记一名玩家）";
			default -> "";
		};
	}

	/** dev/命令入口：强制触发指定事件（不看池、不看间隔、不走预告），用于验证/管理员手动开事件。 */
	public static String forceFire(MinecraftServer srv, String event) {
		if (event == null || event.isBlank()) {
			return "事件名为空";
		}
		// 强制触发不排队：清掉可能已在预告中的事件，避免下一 tick 又自动触发一次。
		pendingEvent = "";
		pendingFireTick = 0;
		fire(srv, event);
		return "已触发事件 " + event;
	}

	private static void fire(MinecraftServer srv, String event) {
		lastEvent = event;
		fired++;
		long now = srv.getTicks();
		switch (event) {
			case "supply_rain" -> {
				String out = LootService.spawn(srv, 4, 0, 60);
				announce(srv, "§6[事件] 物资雨！" + out);
			}
			case "storm" -> {
				activeEvent = "storm";
				activeUntilTick = now + 45 * 20;
				announce(srv, "§6[事件] 风暴来袭：45s 内帆/遥控失效、远程精度 −50%");
			}
			case "black_sea_surge" -> {
				activeEvent = "black_sea_surge";
				activeUntilTick = now + 20 * 20;
				announce(srv, "§6[事件] 黑海倒流：20s 内边界外扩 10%、圈外伤害 ×1.5");
			}
			case "kraken" -> announce(srv, "§6[事件] 海怪出没（待 M5-2）");
			case "survivor_drift" -> {
				// 02 §10：幸存者聚集点 3–4 名；无人小筏还没做（需要额外的 sub-level），先只落人数。
				int want = SURVIVOR_MIN + rnd.nextInt(SURVIVOR_MAX - SURVIVOR_MIN + 1);
				int n = SurvivorService.spawnFloating(srv, want);
				announce(srv, "§6[事件] 幸存者聚集点：海面漂出 " + n + " 个（钩竿可招募）");
			}
			case "bounty" -> announce(srv, "§6[事件] 悬赏目标已标记（待 M5-3）");
			default -> announce(srv, "§6[事件] " + event);
		}
		NBW.LOGGER.info("[event] 触发 {}（累计 {}）", event, fired);
	}

	/** 风暴是否在生效（45s）：航行受阻，由 RemoteService 读取。 */
	public static boolean stormActive() {
		return "storm".equals(activeEvent);
	}

	/** 圈外伤害倍率：黑海涌动期间 ×1.5（02 §10），由 ZoneService 读取。 */
	public static double zoneDamageMultiplier() {
		return "black_sea_surge".equals(activeEvent) ? 1.5 : 1.0;
	}

	/** 黑海倒流期间边界外扩 10%（01 §9 / 02 §10：“边界短暂外扩，可回圈外抢物资”）。 */
	public static double zoneRadiusMultiplier() {
		return "black_sea_surge".equals(activeEvent) ? 1.10 : 1.0;
	}

	/** 当前生效事件（空串 = 无）。 */
	public static String activeEvent() {
		return activeEvent;
	}

	private static void announce(MinecraftServer srv, String msg) {
		NBW.LOGGER.info("[event] {}", msg);
		for (UUID id : RoundManager.participantIds()) {
			ServerPlayerEntity p = srv.getPlayerManager().getPlayer(id);
			if (p != null) {
				p.sendMessage(Text.literal(msg), false);
			}
		}
	}

	/** 一行读数：给 {@code /cww event}。预告中同时给出事件名与剩余秒数。 */
	public static String status(MinecraftServer srv) {
		if (pool.isEmpty()) {
			return "[event] 本局没有事件池";
		}
		long now = srv.getTicks();
		if (!pendingEvent.isEmpty()) {
			long remain = Math.max(0, (pendingFireTick - now + 19) / 20);
			return String.format(Locale.ROOT,
					"[event] 池=%d 已触发=%d 上次=%s 预告=%s 还剩=%ds 下一发=%ds 间隔=%d-%ds",
					pool.size(), fired, lastEvent.isEmpty() ? "无" : lastEvent,
					pendingEvent, remain, remain, minSec, maxSec);
		}
		long remain = Math.max(0, (nextFireTick - now) / 20);
		return String.format(Locale.ROOT, "[event] 池=%d 已触发=%d 上次=%s 下一发=%ds 间隔=%d-%ds",
				pool.size(), fired, lastEvent.isEmpty() ? "无" : lastEvent, remain, minSec, maxSec);
	}

	/** 局后清理。 */
	public static void reset() {
		pool = List.of();
		nextFireTick = 0;
		pendingEvent = "";
		pendingFireTick = 0;
		lastEvent = "";
		activeEvent = "";
		activeUntilTick = 0;
		fired = 0;
	}
}
