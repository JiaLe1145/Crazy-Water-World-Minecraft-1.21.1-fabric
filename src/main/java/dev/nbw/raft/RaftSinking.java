package dev.nbw.raft;

import dev.nbw.NBW;
import dev.nbw.domain.raft.SinkRule;
import dev.nbw.domain.raft.SinkRule.SinkTrigger;
import dev.nbw.domain.raft.SinkState;
import dev.nbw.domain.round.MatchTeam;
import dev.nbw.domain.round.PlayerSession;
import dev.nbw.domain.round.RoundMachine;
import dev.nbw.round.RoundManager;
import dev.nbw.sable.SableRafts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import org.jetbrains.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/**
 * 沉筏运行时（01 §4.3.1 已批准 A3-v4）。
 *
 * <p>分工是刻意的：
 * <ul>
 *   <li>{@link SinkRule}（domain，零 MC）决定"该不该沉、还剩几 tick"——可单测、可审计；</li>
 *   <li>{@link SableRafts}（sable，唯一碰 Sable 的地方）负责"怎么沉"——施加向下的力；</li>
 *   <li>本类只做接线：每 tick 读存活情况、推进状态、把 SUNK 的筏压下水。</li>
 * </ul>
 *
 * <p><b>幸存者尚未实现</b>（M5 的幸存者职业），所以此刻 {@code anySurvivorAlive} 恒为 false，
 * 触发条件等价于"本队玩家全灭"。等幸存者落地，这里换成真实读数即可，判定式不用改。
 */
public final class RaftSinking {

	/**
	 * 沉筏的有效重量倍数（见 {@code SableRafts.sink}）。1.0 = 不施力。
	 *
	 * <p>2.0 是实测出来的安全值（25×17×9 的水池、质量 40.5 的筏）：
	 * 2.0–3.0 倍下筏子约 1 秒沉到 Y≈94.5 并稳定贴着池底摆动；
	 * <b>6.0 倍则压穿池底石头一路掉到 Y=70</b>（那不是沉底，是穿模）。
	 */
	public static double sinkWeightMultiplier = 2.0;

	/** 判"已沉到底"的观察窗口（tick）与窗口内位移阈值。 */
	public static final int REST_WINDOW_TICKS = 20;
	public static final double REST_WINDOW_MOVE = 0.25;

	/**
	 * 倍数上限。实测出来的，不是拍的：
	 * 2.0–3.0 倍下筏子稳定停在池底（相邻采样只在 94.41↔94.54 之间摆），
	 * 6.0 倍则把筏子<b>压穿池底石头一路掉到 Y=70</b>。
	 * 所以穿模的闸门设在"倍数"这一侧，而不是靠"压一会儿就停手"——
	 * 停手会让浮力把已经沉底的筏子重新顶回水面（实测 94.5 → 104.1）。
	 */
	public static final double MAX_WEIGHT_MULTIPLIER = 4.0;

	private static final Map<Integer, Double> windowStartY = new LinkedHashMap<>();
	private static final Map<Integer, Integer> windowTicks = new LinkedHashMap<>();
	private static final Set<Integer> resting = new LinkedHashSet<>();

	private static final Map<Integer, SinkState> states = new LinkedHashMap<>();
	/** 队号 → sub-level UUID。开局生成筏时写入；当前也可用 /cww raft bind 手工绑。 */
	private static final Map<Integer, UUID> rafts = new LinkedHashMap<>();

	private RaftSinking() {
	}

	/** 筏心恢复成功 → 解除本队沉筏倒计时（A2-v2）。已沉的捞不回来，SUNK 不可逆。 */
	public static void cancelByCoreRestore(int teamIndex) {
		SinkState current = stateOf(teamIndex);
		SinkState cleared = SinkRule.clear(current);
		if (cleared != current) {
			states.put(teamIndex, cleared);
			NBW.LOGGER.info("[raft] 队 {} 筏心恢复，沉筏倒计时已解除（原剩余 {}s，阶段 {}）",
					teamIndex, current.remainingSeconds(), current.phase());
		}
	}

	public static void bind(int teamIndex, UUID raftId) {
		rafts.put(teamIndex, raftId);
		NBW.LOGGER.info("[raft] 队 {} 绑定 sub-level {}", teamIndex, raftId);
	}

	/**
	 * 只丢开这一队的记录。
	 *
	 * <p>用在"补生成时发现旧映射指向的筏已经不在场"：新筏一出生不该继承上一只的
	 * 沉筏倒计时与静止观测窗口，否则现场是"刚发筏就卡在 30 秒倒计时里"，很难归因。
	 * 局后清理走 {@link #reset()}，不经这里。
	 */
	public static void forget(int teamIndex) {
		rafts.remove(teamIndex);
		states.remove(teamIndex);
		windowStartY.remove(teamIndex);
		windowTicks.remove(teamIndex);
		resting.remove(teamIndex);
	}

	/**
	 * 按 sub-level 名字绑定（名字来自 {@code /sable name set} 或开局生成逻辑）。
	 *
	 * <p>命令层只调这里、不调 {@code SableRafts}：Sable 类型必须留在 sable/ 里，
	 * 否则"隔离层"会变成一句空话。
	 */
	public static String bindByName(ServerWorld world, int teamIndex, String raftName) {
		UUID id = SableRafts.findByName(world, raftName);
		if (id == null) {
			return "找不到名为 " + raftName + " 的 sub-level；先用 /sable info @e 看有哪些";
		}
		bind(teamIndex, id);
		return "队 " + teamIndex + " 已绑到 '" + raftName + "'（" + describe(world, id) + "）";
	}

	/** 绑定离操作者最近的一艘筏，语义对齐 Sable 的 @n。 */
	public static String bindNearest(ServerWorld world, int teamIndex, double x, double y, double z) {
		UUID id = SableRafts.nearestTo(world, x, y, z);
		if (id == null) {
			return "这个世界里一个 sub-level 都没有；先用 /sable assemble 造一艘筏";
		}
		bind(teamIndex, id);
		return "队 " + teamIndex + " 已绑到最近的 sub-level " + id + "（" + describe(world, id) + "）";
	}

	private static String describe(ServerWorld world, UUID id) {
		return "质量 " + SableRafts.mass(world, id)
				+ "，Y=" + String.format(java.util.Locale.ROOT, "%.2f", SableRafts.posY(world, id));
	}

	/** 运维/测试用：跳过 30s 直接判沉。 */
	public static String forceSink(ServerWorld world, int teamIndex) {
		if (!rafts.containsKey(teamIndex) && SableRafts.findByName(world, "nbw_raft_" + teamIndex) == null) {
			return "队 " + teamIndex + " 没有可沉的筏；先 /cww raft bind " + teamIndex;
		}
		states.put(teamIndex, new SinkState(SinkState.SinkPhase.SUNK, 0, stateOf(teamIndex).resetsUsed()));
		NBW.LOGGER.warn("[raft] 队 {} 被运维命令直接判沉（跳过 30s 倒计时）", teamIndex);
		return "队 " + teamIndex + " 已直接判沉（下一 tick 开始加压）";
	}

	public static SinkState stateOf(int teamIndex) {
		return states.getOrDefault(teamIndex, SinkState.fresh());
	}

	/** 局后清理：倒计时与绑定都不该跨局残留。残留过一次就会在下局里变成幽灵淘汰。 */
	public static void reset() {
		states.clear();
		rafts.clear();
		windowStartY.clear();
		windowTicks.clear();
		resting.clear();
		warned.clear();
	}

	/**
	 * 重修筏心（每局限 1 次）。
	 *
	 * @return true 表示倒计时确实被拉回了 30s；false 表示没生效（不在倒计时中，或额度已用完）
	 */
	public static boolean repair(int teamIndex) {
		SinkState current = stateOf(teamIndex);
		if (!current.countingDown()) {
			return false;
		}
		SinkState after = SinkRule.reset(current);
		boolean changed = after != current;
		if (changed) {
			states.put(teamIndex, after);
			NBW.LOGGER.info("[raft] 队 {} 重修筏心，沉筏倒计时重置为 {}s（本局已用额度 {}/{}）",
					teamIndex, after.remainingSeconds(), after.resetsUsed(), SinkRule.MAX_RESETS);
		}
		return changed;
	}

	/**
	 * 每 tick 调一次。只在 PLAY 阶段生效——ENROLL/READY 时玩家还没上筏，
	 * 拿"没人活着"去触发沉筏会开局就判灭队。
	 */
	public static void onEndTick(MinecraftServer server) {
		if (!RoundManager.phase().matchActive()) {
			return;
		}
		RoundMachine machine = RoundManager.machine();
		ServerWorld world = server.getOverworld();

		for (MatchTeam team : machine.teams()) {
			int index = team.index();
			if (team.members().isEmpty()) {
				continue;
			}
			boolean anyPlayerAlive = false;
			for (UUID id : team.members()) {
				PlayerSession s = machine.sessions().get(id);
				if (s != null && s.isAlive()) {
					anyPlayerAlive = true;
					break;
				}
			}
			// 幸存者职业尚未实现（M5），此刻 anySurvivorAlive 恒为 false，
			// 所以 teamWiped 等价于"本队玩家全灭"。第二条触发路径来自筏心（A2）。
			SinkTrigger trigger = new SinkTrigger(!anyPlayerAlive,
					RaftCoreService.isWreckage(index));
			SinkState next = SinkRule.tick(stateOf(index), trigger, false);
			if (next != stateOf(index)) {
				states.put(index, next);
				if (next.sunk()) {
					NBW.LOGGER.info("[raft] 队 {} 沉筏（原因：全队无幸存者且无玩家存活，30s 倒计时走完）", index);
				}
			}
			if (next.sunk()) {
				UUID raftId = resolve(world, index);
				if (raftId != null) {
					applySink(world, index, raftId);
				}
			}
		}
	}

	/**
	 * 判沉之后每 tick 持续加压。
	 *
	 * <p><b>不因为"看起来触底了"就停手</b>：SUNK 是本局终态，筏子就该一直躺在海底当残骸。
	 * 实测停压 6 秒后浮力会把筏子从 Y=94.5 重新顶回水面 104.1——"沉到一半又浮回去"
	 * 比不沉更糟。穿模的风险改由 {@link #MAX_WEIGHT_MULTIPLIER} 在倍数那一侧拦。
	 *
	 * <p>触底只做一件事：记一次日志，供运维确认它真的到底了（每 tick 刷屏会把真错误埋掉）。
	 */
	private static void applySink(ServerWorld world, int teamIndex, UUID id) {
		if (!SableRafts.sink(world, id, sinkWeightMultiplier)) {
			warnOnce(teamIndex, "绑定的 sub-level " + id + " 已不存在（可能被 shatter/卸载），队 " + teamIndex);
			return;
		}
		double y = SableRafts.posY(world, id);
		Double anchor = windowStartY.get(teamIndex);
		if (anchor == null) {
			windowStartY.put(teamIndex, y);
			windowTicks.put(teamIndex, 0);
			return;
		}
		if (Math.abs(anchor - y) >= REST_WINDOW_MOVE) {
			windowStartY.put(teamIndex, y);   // 还在实质移动，重开窗口
			windowTicks.put(teamIndex, 0);
			return;
		}
		if (windowTicks.merge(teamIndex, 1, Integer::sum) >= REST_WINDOW_TICKS && !resting.contains(teamIndex)) {
			resting.add(teamIndex);
			NBW.LOGGER.info("[raft] 队 {} 已沉到池底（Y={}，{} tick 内位移 < {} 格），继续维持压力直到本局结束",
					teamIndex, String.format(java.util.Locale.ROOT, "%.2f", y), REST_WINDOW_TICKS, REST_WINDOW_MOVE);
		}
	}

		/** 显式绑定优先；没绑定就按开局生成逻辑的命名约定 {@code nbw_raft_<team>} 兜底。 */
	private static @Nullable UUID resolve(ServerWorld world, int teamIndex) {
		UUID bound = rafts.get(teamIndex);
		if (bound != null) {
			return bound;
		}
		String name = "nbw_raft_" + teamIndex;
		UUID byName = SableRafts.findByName(world, name);
		if (byName == null) {
			warnOnce(teamIndex, "找不到名为 " + name + " 的筏；/cww raft bind " + teamIndex + " 可手工绑定");
		}
		return byName;
	}

	/** 同一个队的缺筏告警只报一次：长期服里每 tick 刷 ERROR 会把真错误埋掉（M0-2 的 flywheel 噪音就是教训）。 */
	private static final Map<Integer, String> warned = new LinkedHashMap<>();

	private static void warnOnce(int teamIndex, String message) {
		if (message.equals(warned.get(teamIndex))) {
			return;
		}
		warned.put(teamIndex, message);
		NBW.LOGGER.warn("[raft] {}", message);
	}

	/** 给 /cww raft status 与审计用。 */
	public static String status() {
		if (states.isEmpty() && rafts.isEmpty()) {
			return "[raft] 本局还没有沉筏记录";
		}
		StringBuilder sb = new StringBuilder("[raft] ");
		for (Map.Entry<Integer, SinkState> e : states.entrySet()) {
			SinkState s = e.getValue();
			sb.append("队").append(e.getKey()).append('=').append(s.phase());
			if (s.countingDown()) {
				sb.append('(').append(s.remainingSeconds()).append("s,重置").append(s.resetsUsed()).append(')');
			}
			sb.append(' ');
		}
		for (Map.Entry<Integer, UUID> e : rafts.entrySet()) {
			sb.append("绑:队").append(e.getKey()).append("→").append(e.getValue()).append(' ');
		}
		return sb.toString().trim();
	}
}
