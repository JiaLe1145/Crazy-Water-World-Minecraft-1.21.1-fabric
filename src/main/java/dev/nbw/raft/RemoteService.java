package dev.nbw.raft;

import dev.nbw.NBW;
import dev.nbw.content.NbwContent;
import dev.nbw.domain.raft.HelmModel;
import dev.nbw.domain.raft.HelmModel.Intent;
import dev.nbw.domain.raft.SpeedGovernor;
import dev.nbw.round.RoundManager;
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
 * <p><b>"每个筏只有一个遥控器"怎么保证。</b>不给玩家建档、不查 UUID：直接查发送移动包的人
 * <b>主手上是不是 {@code nbw:remote}</b>。遥控器是个物品，开局只发一件给一队中的一人，
 * 谁拿着它谁就能开。客户端也只在"主手拿遥控器 + 移动模式"时发包（见 {@code client/MoveInputClient}），
 * 服务端再核一次主手，双保险。断输入（松手/掉线/换物品）由窗口过期兜底，不会"卡在一直开"。
 */
public final class RemoteService {

	/**
	 * A/D 转向时每 tick 施加的绕 Y 角冲量。取 1.0 是起步值（约让 4×4 筏转 90°/s），
	 * 具体手感要真人验，探针只断"转了没转、方向对不对"，不把转向角速度钉死。
	 */
	public static final double TURN_IMPULSE = 1.0;

	/** 移动意图的有效窗口（tick）。客户端每 2 tick 发一次包，这里给 6 tick 兜掉线/换物品。 */
	public static final int INTENT_WINDOW_TICKS = 6;

	/** 队号 → 最近一次移动意图 + 到期 tick。 */
	private record HeldIntent(Intent intent, int untilTick) {
	}

	private static final Map<Integer, HeldIntent> intents = new LinkedHashMap<>();
	private static int tick;

	private RemoteService() {
	}

	/**
	 * 收到一条移动包。只认"主手拿遥控器 + 在对局里 + 有队"，其余一律丢弃不改状态。
	 * 包里的四个布尔翻译成意图纯函数（{@link HelmModel}），服务端再决定往哪推。
	 */
	public static void onMove(ServerPlayerEntity player, boolean forward, boolean backward,
			boolean left, boolean right) {
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
		intents.put(team, new HeldIntent(HelmModel.fromKeys(forward, backward, left, right),
				tick + INTENT_WINDOW_TICKS));
	}

	/**
	 * 每 tick 登记。线性推进交给 {@link RaftMotionService}（与帆合成、单点施力），
	 * 转向是角冲量、直接施。必须在 {@code RaftMotionService.onEndTick} 之前调用。
	 *
	 * @return 本 tick 动了几艘筏（给探针与日志用）
	 */
	public static int onEndTick(MinecraftServer server) {
		tick++;
		intents.entrySet().removeIf(e -> e.getValue().untilTick() < tick);   // 断输入自动脱手
		ServerWorld world = server.getOverworld();
		int moved = 0;
		for (Map.Entry<Integer, HeldIntent> e : intents.entrySet()) {
			int team = e.getKey();
			UUID raft = RaftSpawner.raftOf(team);
			if (raft == null) {
				continue;
			}
			Intent intent = e.getValue().intent();
			if (intent.linear() != 0) {
				double[] fwd = SableRafts.forward(world, raft);
				RaftMotionService.request(team, fwd[0] * intent.linear(), fwd[1] * intent.linear(),
						SpeedGovernor.REMOTE_SPEED);
				moved++;
			}
			if (intent.turn() != 0) {
				SableRafts.applyYawImpulse(world, raft, intent.turn() * TURN_IMPULSE);
				moved++;
			}
		}
		return moved;
	}

	/**
	 * 开局给每支有人的队发一个遥控器（给队内第一个成员）。"每筏一个遥控器"就落实在这：
	 * 发进背包（会落在快捷栏），谁拿着它谁就能开这队的筏。
	 */
	public static void distributeRemotes(MinecraftServer server) {
		for (var team : RoundManager.machine().teams()) {
			if (team.members().isEmpty()) {
				continue;
			}
			UUID first = team.membersOrdered().get(0);
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(first);
			if (p == null) {
				continue;
			}
			if (p.getInventory().insertStack(new ItemStack(NbwContent.remote))) {
				NBW.LOGGER.info("[remote] 队 {} 把遥控器发给 {}", team.index(), p.getNameForScoreboard());
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

	/** 一行读数：每队当前意图 + 朝向。探针靠它判断"遥控器到底改变了什么"。 */
	public static String readout(MinecraftServer server, int teamIndex) {
		HeldIntent held = intents.get(teamIndex);
		Intent intent = held == null ? Intent.NONE : held.intent();
		UUID raft = RaftSpawner.raftOf(teamIndex);
		double[] fwd = raft == null ? new double[]{0.0, 1.0}
				: SableRafts.forward(server.getOverworld(), raft);
		return String.format(Locale.ROOT,
				"[remote] 队%d 意图 前进=%s 后退=%s 左转=%s 右转=%s | 朝向=(%.3f,%.3f) | %s",
				teamIndex, intent.linear() > 0, intent.linear() < 0, intent.turn() > 0, intent.turn() < 0,
				fwd[0], fwd[1], RaftMotionService.travelReadout(teamIndex));
	}

	/** 局后清理：意图是局内状态，跨局不许残留成"上一局还在开船"。 */
	public static void reset() {
		intents.clear();
		tick = 0;
		NBW.LOGGER.debug("[remote] 遥控器意图已清空");
	}
}
