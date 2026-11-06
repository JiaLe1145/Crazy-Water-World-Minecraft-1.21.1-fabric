package dev.nbw.arena;

import dev.nbw.NBW;
import dev.nbw.cfg.ConfigModels;
import dev.nbw.domain.arena.ArenaGeometry;
import dev.nbw.domain.arena.ChunkPacing;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 对局期间的海域区块预热：逐区块下强制加载票，并按服务端实际快慢背压。
 *
 * <p><b>为什么要预热。</b>不预热时，READY 的传送会让服务端在那一刻同步生成/加载锚点区块，
 * 实测一次 {@code Can't keep up! 2001ms or 40 ticks behind} —— 正式服一开局整个房间都卡住。
 *
 * <p><b>为什么不是"每 tick 固定几个"。</b>按行下票（一次 8 个新区块）实测拖住 2157ms；
 * 改成逐区块 2 个/tick 时"0 次落后"那条只在<b>区块早已生成过</b>的前提下成立；
 * 首批真正要加载 81 个区块时，1 个/tick 仍然攒出一次 6137ms / 122 tick 的落后。
 * 总量不变，摊速率没用 —— 所以改成背压：服务端自己跑得慢就先让路（见 {@link ChunkPacing}）。
 *
 * <p>只撤我们自己下的票：{@code setChunkForced(x, z, true)} 的返回值就是"状态是否被改变"，
 * 所以已经由地图作者/别人强制加载的区块不会进我们的回收清单，也不会被我们撤掉。
 */
public final class ArenaChunkLoader {

	/** 上次被 tick 的墙钟时刻（纳秒）。背压信号就取两次 END_SERVER_TICK 之间的真实间隔。 */
	private static long lastCallNanos = 0;
	/** 因为服务端已经跑慢了而让路的 tick 数；预热结束时打出来，便于判断背压是否真的在起作用。 */
	private static long throttledTicks = 0;
	/**
	 * 预热期间 observed 的最大 tick 间隔（毫秒）。
	 *
	 * <p>为什么自己记：{@code Can't keep up! Running 4705ms or 71 ticks behind} 里的毫秒数是
	 * <b>看门狗触发时累积的欠账总额</b>，不是一次阻塞那么久。玩家真正感知的是单帧时长，
	 * 所以判据必须用 tick 间隔的最大值，而不是那行日志的数字。
	 */
	private static long maxTickGapMs = 0;
	/** 预热完成行只打一次，避免每 tick 刷日志。 */
	private static boolean warmReported = false;

	/** 每 tick 最多预热几个区块。调大的代价是开局冻结，调小的代价是等待时间。 */
	private static final int PER_TICK = 1;

	private static final List<int[]> pending = new ArrayList<>();
	private static final List<int[]> held = new ArrayList<>();
	private static String arenaId = null;

	private ArenaChunkLoader() {
	}

	/** 开始（或重来）预热某张图。幂等：已在手上的区块不重复下票，也不误进回收清单。 */
	public static void begin(ServerWorld world, String id, ConfigModels.Arena arena) {
		if (arena == null || arena.sea == null || world == null) {
			return;
		}
		ArenaGeometry.ChunkBox box = ArenaGeometry.boxOfCircle(arena.sea.centerX, arena.sea.centerZ, arena.sea.radius);
		List<int[]> coords = ArenaGeometry.chunkCoords(box);
		pending.clear();
		for (int[] c : coords) {
			if (!contains(c[0], c[1])) {
				pending.add(c);
			}
		}
		arenaId = id;
		lastCallNanos = 0;
		throttledTicks = 0;
		maxTickGapMs = 0;
		warmReported = false;
		NBW.LOGGER.info("[arena] 区块预热开始 arena={} 区块盒 {}×{}={} 个（已在手 {} 个，每 tick 最多 {} 个，"
						+ "≤{} 秒；服务端变慢会主动让路，所以这是下界）",
				id, box.maxChunkX() - box.minChunkX() + 1, box.maxChunkZ() - box.minChunkZ() + 1, box.count(),
				held.size(), PER_TICK, Math.max(1, box.count()) * PER_TICK / 20.0);
	}

	/** 每 tick 推进一点。由 RoundManager 的 END_SERVER_TICK 调，不在对局中时 pending/held 都空。 */
	public static void tick(ServerWorld world) {
		long now = System.nanoTime();
		long elapsedMs = lastCallNanos == 0 ? 0 : (now - lastCallNanos) / 1_000_000;
		lastCallNanos = now;
		if (world == null || arenaId == null) {
			return;
		}
		if (elapsedMs > maxTickGapMs) {
			maxTickGapMs = elapsedMs;
		}
		if (pending.isEmpty()) {
			return;   // 帧时间已经记上了；没活就只是继续观测
		}
		// 背压：上一段间隔已经超出一个 tick 的预算，说明加载积压在拖慢服务端，
		// 这一 tick 就完全不添新活。固定"每 tick 1~2 个"实测挡不住首批 81 个区块攒出的 6.1 秒尖峰。
		if (elapsedMs > maxTickGapMs) {
			maxTickGapMs = elapsedMs;
		}
		if (!ChunkPacing.shouldLoadThisTick(elapsedMs, ChunkPacing.SLOW_TICK_MS)) {
			throttledTicks++;
			return;
		}
		int budget = PER_TICK;
		Iterator<int[]> it = pending.iterator();
		while (it.hasNext() && budget-- > 0) {
			int[] c = it.next();
			// 返回值 = 状态是否改变；false 说明本来就被别人强制加载了，不归我们撤
			if (world.setChunkForced(c[0], c[1], true)) {
				held.add(c);
			}
			it.remove();
		}
		if (pending.isEmpty() && !warmReported) {
			warmReported = true;
			NBW.LOGGER.info("[arena] 海域预热完成 arena={}：已持票 {} 个，让路 {} 个 tick，最大 tick 间隔 {}ms",
					arenaId, held.size(), throttledTicks, maxTickGapMs);
		}
	}

	/**
	 * 局后回收：只撤自己下的那批。
	 *
	 * @return 残留说明（空 = 干净）。漏撤的票会**跨重启存活**，所以这条必须进局后断言。
	 */
	public static List<String> release(ServerWorld world) {
		List<String> issues = new ArrayList<>();
		int count = held.size();
		int alreadyGone = 0;
		for (int[] c : held) {
			if (world == null) {
				continue;
			}
			// 返回 false = 这块本来就没在被强制：票被别处撤了，或者我们记错了账。
			// 不报就是恒不报警的残留检查。
			if (!world.setChunkForced(c[0], c[1], false)) {
				alreadyGone++;
			}
		}
		if (alreadyGone > 0) {
			issues.add(alreadyGone + "/" + count + " 个自持海域票在回收时已经不在强制加载表上了");
			NBW.LOGGER.warn("[arena] 回收海域票时对不上账：{}", issues.get(0));
		}
		held.clear();
		pending.clear();
		if (arenaId != null) {
			NBW.LOGGER.info("[arena] 海域预热回收 arena={} 撤掉 {} 个自持票；因背压让路 {} 个 tick，"
							+ "整局观测到的最大 tick 间隔 {}ms（玩家感知的帧时间；"
							+ "Can't keep up 里的毫秒数是累计欠账，不是单次阻塞）",
					arenaId, count, throttledTicks, maxTickGapMs);
			arenaId = null;
		}
		return issues;
	}

	private static boolean contains(int x, int z) {
		for (int[] c : held) {
			if (c[0] == x && c[1] == z) {
				return true;
			}
		}
		return false;
	}

	public static int remaining() {
		return pending.size();
	}

	public static int heldCount() {
		return held.size();
	}

	public static String arenaId() {
		return arenaId;
	}
}
