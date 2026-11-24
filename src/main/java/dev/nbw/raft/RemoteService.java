package dev.nbw.raft;

import dev.nbw.NBW;
import dev.nbw.content.NbwContent;
import dev.nbw.domain.raft.HelmModel;
import dev.nbw.domain.raft.HelmModel.Intent;
import dev.nbw.domain.raft.HelmsmanRule;
import dev.nbw.domain.raft.SpeedGovernor;
import dev.nbw.domain.survivor.SurvivorJob;
import dev.nbw.round.EventService;
import dev.nbw.round.RoundManager;
import dev.nbw.round.SurvivorService;
import dev.nbw.sable.SableRafts;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 遥控器推进的运行时（问题③，替代 {@code OarService} 的"多人划桨"）。
 *
 * <p>与桨的分工变化要说清：<b>桨是每人一次输入、要持续按着桨台；遥控器是一个玩家手持遥控器、
 * WASD 输入直接作用在本队的筏上。</b>所以这里没有"桨位表"，只有"每队当前按着哪些键"，
 * 以及"每队当前有没有人正在遥控"。动力合成仍然统一交给 {@link RaftMotionService}
 * （线性前进/后退），转向是新增的角冲量（绕 Y 轴）。
 *
 * <p><b>"每筏同时只能一人遥控"怎么保证。</b>每队每人都发一个遥控器，但服务端记一个"当前驾驶员"：
 * 谁先发来非静止的移动意图谁就是驾驶员；别人在开的时候，第二个人的输入一律忽略。
 * 驾驶员松手（全键松开）或断输入（窗口过期）就让位。客户端只在"主手拿遥控器 + 移动模式"时发包，
 * 服务端再核一次主手，双保险。
 */
public final class RemoteService {

	/**
	 * A/D 转向时每 tick 施加的绕 Y 角冲量。2026-11-08 用户把转向速度定为 0.5。
	 */
	/**
	 * 转向角冲量。2026-11-21 实测：0.5 时 A/D 只有约 10°/s（一圈 36 秒），
	 * 手感上等于"转不动"（用户实机反馈"转向过小甚至没有"）。改到 1.5 后约 30°/s。
	 *
	 * <p><b>2026-11-22 降到 0.9（≈18°/s）：</b>用户反馈"速度过快、移动不平稳、木筏会翻倒"。
	 * 满速下每 tick 一记 1.5 的偏航冲量，实测能把筏子横滚顶到 12.9° ——
	 * 转向本身不该是"甩尾"。0.9 仍然一圈 20 秒，配合新的姿态稳定，
	 * 转向时倾斜峰值压在 8° 以内。
	 */
	public static final double TURN_IMPULSE = 0.9;

	/** 移动意图的有效窗口（tick）。客户端每 2 tick 发一次包，这里给 6 tick 兜掉线/换物品。 */
	public static final int INTENT_WINDOW_TICKS = 6;

	/** 队号 → 最近一次移动意图 + 到期 tick。 */
	private record HeldIntent(Intent intent, int untilTick) {
	}

	private static final Map<Integer, HeldIntent> intents = new LinkedHashMap<>();
	/** 队号 → 当前正在遥控的玩家。同时只能一个人。 */
	private static final Map<Integer, UUID> pilots = new LinkedHashMap<>();
	/** 玩家 → 上次"被拒"提示的 tick，节流用，避免每 tick 刷屏。 */
	private static final Map<UUID, Integer> lastRejectNotice = new LinkedHashMap<>();
	private static int tick;

	private RemoteService() {
	}

	/**
	 * 收到一条移动包。只认"主手拿遥控器 + 在对局里 + 有队"，其余一律丢弃不改状态。
	 * 包里的四个布尔翻译成意图纯函数（{@link HelmModel}），服务端再决定往哪推。
	 */
	public static void onMove(ServerPlayerEntity player, boolean forward, boolean backward,
			boolean left, boolean right) {
		if (player.isSpectator()) {
			return;   // 淘汰观战者不能遥控：防伪造 nbw:move 包让死人开船
		}
		if (!RoundManager.phase().allowsPvp()) {
			return;   // 非 PLAY：不推进（PVP 本身已由 RoundManager 闸掉，这里只防误操作）
		}
		if (player.getMainHandStack().getItem() != NbwContent.remote) {
			return;   // 没拿遥控器：不是本筏的驾驶员
		}
		var session = RoundManager.machine().session(player.getUuid());
		int team = session == null ? -1 : session.teamIndex();
		if (team < 0) {
			return;
		}
		Intent intent = HelmModel.fromKeys(forward, backward, left, right);
		UUID current = pilots.get(team);
		if (current != null && !current.equals(player.getUuid())) {
			// 已经有人在遥控：第二个人进不了遥控模式，输入忽略 + 节流提示
			Integer last = lastRejectNotice.get(player.getUuid());
			if (last == null || tick - last >= 20) {
				lastRejectNotice.put(player.getUuid(), tick);
				NBW.LOGGER.info("[remote] 队 {} 正被 {} 遥控，{} 的输入被忽略",
						team, current, player.getNameForScoreboard());
			}
			return;
		}
		if (intent.idle()) {
			// 松手（全键松开）：让位 + 清意图
			if (current != null && current.equals(player.getUuid())) {
				pilots.remove(team);
			}
			intents.remove(team);
			return;
		}
		if (current == null) {
			pilots.put(team, player.getUuid());   // 第一个发来非静止意图的人成为驾驶员
		}
		intents.put(team, new HeldIntent(intent, tick + INTENT_WINDOW_TICKS));
	}

	/**
	 * 每 tick 登记。线性推进交给 {@link RaftMotionService}（与帆合成、单点施力），
	 * 转向是角冲量、直接施。必须在 {@code RaftMotionService.onEndTick} 之前调用。
	 *
	 * @return 本 tick 动了几艘筏（给探针与日志用）
	 */
	public static int onEndTick(MinecraftServer server) {
		tick++;
		intents.entrySet().removeIf(e -> {
			if (e.getValue().untilTick() < tick) {
				pilots.remove(e.getKey());   // 断输入自动脱手，也放掉驾驶席
				return true;
			}
			return false;
		});
		if (EventService.stormActive()) {
			return 0;   // 风暴事件：航行受阻（02 §10），意图仍按窗口过期
		}
		ServerWorld world = server.getOverworld();
		int moved = 0;
		for (Map.Entry<Integer, HeldIntent> e : intents.entrySet()) {
			int team = e.getKey();
			UUID raft = RaftSpawner.raftOf(team);
			if (raft == null) {
				continue;
			}
			Intent intent = e.getValue().intent();
			// 舵手（01 §7 A6 / 02 §6）：本队有舵手时，遥控航速与转向冲量各 +15%。
			// 只乘目标值，不碰 SpeedGovernor 的船体上限——满帆顶到上限时加成自然失效。
			boolean hasHelmsman = SurvivorService.hasJob(team, SurvivorJob.HELMSMAN);
			if (intent.linear() != 0) {
				double[] fwd = SableRafts.forward(world, raft);
				// 2026-11-24 修：航向直接取"甲板上那面帆的**方块朝向**"经位姿旋到世界。
				// 上一版用体前方再按 heading 旋转，方向依赖 forward 的符号约定，实机上表现为
				// "转帆不改变方向 / 走的是世界方向"。现在只有一条语义：帆朝哪，船头朝哪。
				double[] sailFwd = Sailing.forwardOf(world, raft, team);
				if (sailFwd != null) {
					fwd = sailFwd;
				}
				double speed = SpeedGovernor.clampSpeed(
						HelmsmanRule.speed(SpeedGovernor.REMOTE_SPEED, hasHelmsman));
				RaftMotionService.request(team, fwd[0] * intent.linear(), fwd[1] * intent.linear(), speed);
				moved++;
			}
			if (intent.turn() != 0) {
				SableRafts.applyYawImpulse(world, raft,
						intent.turn() * HelmsmanRule.turnImpulse(TURN_IMPULSE, hasHelmsman));
				moved++;
			}
		}
		return moved;
	}

	/**
	 * 开局给每支队的<b>每个</b>成员都发一个遥控器（2026-11-08 改：原先是只发第一个）。
	 * "每筏同时只能一人遥控"由 {@link #onMove} 的驾驶席逻辑保证，不靠"只发一个"。
	 */
	public static void distributeRemotes(MinecraftServer server) {
		for (var team : RoundManager.machine().teams()) {
			for (UUID member : team.membersOrdered()) {
				ServerPlayerEntity p = server.getPlayerManager().getPlayer(member);
				if (p == null) {
					continue;
				}
				if (p.getInventory().insertStack(new ItemStack(NbwContent.remote))) {
					NBW.LOGGER.info("[remote] 队 {} 把遥控器发给 {}", team.index(), p.getNameForScoreboard());
				}
			}
		}
	}

	/**
	 * dev / 自动化测试入口：绕过"主手拿遥控器"那道校验，直接给某队登记一段意图。
	 * 与真实客户端的区别只有"输入从哪来"，{@link #onEndTick} 的推进路径完全同一条。
	 *
	 * @return 一行结论；失败时给出原因并且不改状态
	 */
	public static String hold(int team, boolean forward, boolean backward, boolean left, boolean right, int ticks) {
		if (!RoundManager.phase().allowsPvp()) {
			return "不在 PLAY 阶段（当前 " + RoundManager.phase() + "），遥控无效";
		}
		if (RaftSpawner.raftOf(team) == null) {
			return "队 " + team + " 这一局没有筏（先 /cww start 或 /cww raft gen）";
		}
		intents.put(team, new HeldIntent(HelmModel.fromKeys(forward, backward, left, right), tick + ticks));
		return "已登记遥控意图 前进=" + forward + " 后退=" + backward + " 左转=" + left
				+ " 右转=" + right + " 共 " + ticks + " tick";
	}

	/** 一行读数：每队当前意图 + 帆的真实朝向 + 实际用于推进的世界航向。 */
	public static String readout(MinecraftServer server, int teamIndex) {
		HeldIntent held = intents.get(teamIndex);
		Intent intent = held == null ? Intent.NONE : held.intent();
		UUID raft = RaftSpawner.raftOf(teamIndex);
		net.minecraft.server.world.ServerWorld world = server.getOverworld();
		net.minecraft.util.math.Direction facing = raft == null ? null
				: Sailing.sailFacing(world, raft, teamIndex);
		double[] sailFwd = raft == null ? null : Sailing.forwardOf(world, raft, teamIndex);
		double[] fwd = sailFwd != null ? sailFwd
				: (raft == null ? new double[]{0.0, 1.0} : SableRafts.forward(world, raft));
		return String.format(Locale.ROOT,
				"[remote] 队%d 意图 前进=%s 后退=%s 左转=%s 右转=%s | 帆=%s 航向=(%.3f,%.3f) | %s",
				teamIndex, intent.linear() > 0, intent.linear() < 0, intent.turn() > 0, intent.turn() < 0,
				facing == null ? "无" : facing.asString(), fwd[0], fwd[1],
				RaftMotionService.travelReadout(teamIndex));
	}

	public static void reset() {
		intents.clear();
		pilots.clear();
		lastRejectNotice.clear();
		tick = 0;
		NBW.LOGGER.debug("[remote] 遥控器意图与驾驶席已清空");
	}
}
