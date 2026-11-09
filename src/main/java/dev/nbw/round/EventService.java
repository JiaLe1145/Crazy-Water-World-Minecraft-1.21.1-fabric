package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.loot.LootService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * 事件池（M5-1）：按 {@code format.events} 的池与间隔，在 PLAY 里定时抽一个事件触发。
 *
 * <p>本轮只落"调度 + 公告 + 少数有实际效果的事件"：<b>supply_rain</b> 会实打实补箱子；
 * storm / kraken / black_sea_surge / bounty / survivor_drift 先只广播（它们依赖风暴禁用航行、
 * 幸存者、悬赏等系统，随 M5-2/M5-3 分批接）。
 */
public final class EventService {

	private static List<String> pool = List.of();
	private static int minSec = 150;
	private static int maxSec = 240;
	private static long nextFireTick;
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
		scheduleNext(srv);
		NBW.LOGGER.info("[event] 事件池 {} 个，间隔 {}-{}s，第一发 tick={}",
				pool.size(), minSec, maxSec, nextFireTick);
	}

	/** PLAY 每 tick：清过期事件；到点抽一发（同刻最多 1 个，02 §10）。 */
	public static void onEndTick(MinecraftServer srv) {
		if (pool.isEmpty()) {
			return;
		}
		if (!activeEvent.isEmpty() && srv.getTicks() >= activeUntilTick) {
			announce(srv, "§7[事件] " + activeEvent + " 结束");
			activeEvent = "";
			activeUntilTick = 0;
		}
		if (!activeEvent.isEmpty()) {
			return;   // 已有事件在生效，不叠加
		}
		if (srv.getTicks() < nextFireTick) {
			return;
		}
		fire(srv);
		scheduleNext(srv);
		NBW.LOGGER.info("[event] 下一发 tick={}", nextFireTick);
	}

	private static void scheduleNext(MinecraftServer srv) {
		int sec = minSec >= maxSec ? minSec : minSec + rnd.nextInt(maxSec - minSec + 1);
		nextFireTick = srv.getTicks() + (long) sec * 20;
	}

	private static void fire(MinecraftServer srv) {
		fire(srv, pool.get(rnd.nextInt(pool.size())));
	}

	/** dev/命令入口：强制触发指定事件（不看池、不看间隔），用于验证/管理员手动开事件。 */
	public static String forceFire(MinecraftServer srv, String event) {
		if (event == null || event.isBlank()) {
			return "事件名为空";
		}
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
				announce(srv, "§6[事件] 风暴来袭：45s 内航行受阻");
			}
			case "black_sea_surge" -> {
				activeEvent = "black_sea_surge";
				activeUntilTick = now + 20 * 20;
				announce(srv, "§6[事件] 黑海涌动：20s 内圈外伤害 ×1.5");
			}
			case "kraken" -> announce(srv, "§6[事件] 海怪出没（待 M5-2）");
			case "survivor_drift" -> {
				int n = SurvivorService.spawnFloating(srv, 2);
				announce(srv, "§6[事件] 幸存者漂流经过：海面漂出 " + n + " 个（钩竿可招募）");
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

	/** 一行读数：给 {@code /cww event}。 */
	public static String status(MinecraftServer srv) {
		if (pool.isEmpty()) {
			return "[event] 本局没有事件池";
		}
		long remain = Math.max(0, (nextFireTick - srv.getTicks()) / 20);
		return String.format(Locale.ROOT, "[event] 池=%d 已触发=%d 上次=%s 下一发=%ds 间隔=%d-%ds",
				pool.size(), fired, lastEvent.isEmpty() ? "无" : lastEvent, remain, minSec, maxSec);
	}

	/** 局后清理。 */
	public static void reset() {
		pool = List.of();
		nextFireTick = 0;
		lastEvent = "";
		activeEvent = "";
		activeUntilTick = 0;
		fired = 0;
	}
}
