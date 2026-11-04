package dev.nbw.domain.raft;

import java.util.UUID;

/**
 * 残骸修复引导（01 §4.3.2 已批准 A2-v2）：玩家对筏心残骸<b>持续右键 8 秒</b>恢复筏心，
 * 期间<b>受到伤害即打断</b>。
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
	 */
	public record Channel(UUID leader, int progressTicks) {

		public static Channel none() {
			return new Channel(null, 0);
		}

		public boolean active() {
			return leader != null;
		}

		/** 剩余秒数（向上取整），给 HUD 与播报用。 */
		public int remainingSeconds() {
			return (CHANNEL_TICKS - progressTicks + 19) / 20;
		}

		public double progressRatio() {
			return (double) progressTicks / CHANNEL_TICKS;
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
			return new Result(new Channel(in.who(), 1), Outcome.ADVANCED);
		}
		int next = current.progressTicks() + 1;
		if (next >= CHANNEL_TICKS) {
			return new Result(Channel.none(), Outcome.COMPLETED);
		}
		return new Result(new Channel(current.leader(), next), Outcome.ADVANCED);
	}

	/** 恰好走满 160 tick 才完成；第 159 tick 还差一格。 */
	public static boolean isComplete(int progressTicks) {
		return progressTicks >= CHANNEL_TICKS;
	}
}
