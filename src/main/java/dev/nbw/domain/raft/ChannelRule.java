package dev.nbw.domain.raft;

import java.util.UUID;

/**
 * 残骸修复引导（01 §4.3.2 已批准 A2-v2）：玩家对筏心残骸<b>持续右键 8 秒</b>恢复筏心，
 * 期间<b>受到伤害即打断</b>。
 *
 * <p><b>2026-11-14 工匠加成</b>：队伍里有工匠时，本次引导时长折算为 6 秒（120 tick），
 * 由适配层在<b>引导开始</b>时传入；中途工匠上下筏不改已开始的引导。
 *
 * <p>纯函数，零 Minecraft 依赖。适配层每 tick 只报三个事实（引导者是谁、还在不在有效交互、
 * 这一 tick 有没有受伤），判定与进度全在这里。时序规则最容易写错，所以必须不起服就能测干净。
 *
 * <p>打断条件取自 §4.3.2 默认值表：受伤、松手、死亡、掉出交互距离、断线；
 * 后四种都由适配层折算成 {@code interacting=false}。
 * 移动与状态机制归零<b>不</b>打断。
 */
public final class ChannelRule {

	/** 引导时长：8 秒 = 160 tick。 */
	public static final int CHANNEL_TICKS = 8 * 20;

	public enum Outcome {
		/** 进度推进中，还没完成。 */
		ADVANCED,
		/** 刚走完 160 tick，筏心恢复。 */
		COMPLETED,
		/** 本 tick 被打断，进度归零。 */
		INTERRUPTED,
		/** 这一 tick 没有任何人在引导。 */
		IDLE
	}

	/**
	 * 一次引导的现场。不可变；{@code leader == null} 表示当前无人引导。
	 *
	 * <p>同一处残骸同时只认一个引导者：允许两人交替的话，就能用"你被打了换我上"
	 * 绕过打断惩罚，这条机制当场失效。
	 *
	 * <p>{@code requiredTicks} 在<b>引导开始那一刻</b>定格（工匠队伍是 120，其他 160）：
	 * 中途工匠上/下筏不改变这次引导的时长，避免"最后一秒塞个工匠"变成免费加速。
	 */
	public record Channel(UUID leader, int progressTicks, int requiredTicks) {

		/** 旧口径：不显式给时长时按基础 160 tick。测试与历史调用都走这个。 */
		public Channel(UUID leader, int progressTicks) {
			this(leader, progressTicks, CHANNEL_TICKS);
		}

		public static Channel none() {
			return new Channel(null, 0, CHANNEL_TICKS);
		}

		public boolean active() {
			return leader != null;
		}

		/** 剩余秒数（向上取整），给 HUD 与播报用。 */
		public int remainingSeconds() {
			return Math.max(0, (requiredTicks - progressTicks + 19) / 20);
		}

		public double progressRatio() {
			return (double) progressTicks / requiredTicks;
		}
	}

	/**
	 * @param who        本 tick 正在右键残骸的玩家；null 表示无人
	 * @param interacting 他是否仍在<b>有效</b>交互：没松手、在交互距离内、活着、在线
	 * @param damaged    他这一 tick 是否受到伤害
	 */
	public record Inputs(UUID who, boolean interacting, boolean damaged) {
	}

	/** 判定结果：新现场 + 这一 tick 发生了什么。 */
	public record Result(Channel channel, Outcome outcome) {

		public boolean restored() {
			return outcome == Outcome.COMPLETED;
		}

		public boolean interrupted() {
			return outcome == Outcome.INTERRUPTED;
		}
	}

	private ChannelRule() {
	}

	/**
	 * 推进一 tick。
	 *
	 * <p>判定顺序是刻意的：<b>先判"还在不在交互"，再判"有没有受伤"</b>。
	 * 反过来写会让被打死的那一 tick 既算受伤又算续上了进度。
	 */
	public static Result tick(Channel current, Inputs in) {
		return tick(current, in, CHANNEL_TICKS);
	}

	/**
	 * 推进一 tick（可指定本次引导的时长）。
	 *
	 * @param startTicks 只在<b>开始一段新引导</b>时使用（工匠队伍 120，其他 160）；
	 *                   引导进行中始终沿用 {@link Channel#requiredTicks()}，中途不来回来回改
	 */
	public static Result tick(Channel current, Inputs in, int startTicks) {
		boolean someone = in.who() != null && in.interacting();

		if (!someone) {
			// 松手 / 掉出距离 / 死亡 / 断线：现场作废，进度不保留
			return new Result(Channel.none(), current.active() ? Outcome.INTERRUPTED : Outcome.IDLE);
		}
		if (in.damaged()) {
			return new Result(Channel.none(), Outcome.INTERRUPTED);
		}
		if (!current.active() || !current.leader().equals(in.who())) {
			// 新人接手（含从空现场开始）：从第 1 tick 计，别人剩下的进度不算
			return new Result(new Channel(in.who(), 1, Math.max(1, startTicks)), Outcome.ADVANCED);
		}
		int next = current.progressTicks() + 1;
		if (next >= current.requiredTicks()) {
			return new Result(Channel.none(), Outcome.COMPLETED);
		}
		return new Result(new Channel(current.leader(), next, current.requiredTicks()), Outcome.ADVANCED);
	}

	/** 恰好走满 160 tick 才完成；第 159 tick 还差一格。 */
	public static boolean isComplete(int progressTicks) {
		return progressTicks >= CHANNEL_TICKS;
	}

	/** 按本次引导的实际时长判断是否完成（工匠队伍是 120）。 */
	public static boolean isComplete(int progressTicks, int requiredTicks) {
		return progressTicks >= requiredTicks;
	}
}
