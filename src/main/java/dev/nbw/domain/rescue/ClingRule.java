package dev.nbw.domain.rescue;

/**
 * 落水、扒边与自救的规则（01 §8，`[已批准]` 的是"可自救 + 队友可救"这个原则，
 * 具体数值仍是 `[新增·待批 A7]`，改之前请先看 {@code docs/dev/M2-RESULTS.md} 的对应小节）。
 *
 * <p><b>这里只有状态机与算术，没有方块、没有实体、没有 Sable。</b>
 * "我是不是在自己队筏的外壳旁边"这类问题由适配层算成一组数字送进来，
 * 所以每一条时序都能在这里单独测——包括最容易写错的那两件：
 * 打断之后必须从 0 重新开始，以及<b>不许在离线/换维度之后还留着状态</b>（那是 #41 那一类幽灵）。
 *
 * <p><b>为什么攀爬是"我们抬人"而不是"原版爬梯子"。</b>Sable 确实有一个
 * {@code climbing_sub_levels.LivingEntityMixin} 想让 sub-level 里的梯子可爬，
 * 但它要求 {@code SablePlatform.isBlockstateLadder(...)}，而 Fabric 侧的实现直接
 * {@code return false; //handled already for fabric}（{@code SablePlatformImpl.java:20-22}）。
 * "fabric 已经处理好了"这句话我们不能当结论用，所以这里不依赖它；
 * 真要省这一层，得先在实机上证明筏上的梯子能爬，测出来能用再换过去。
 *
 * <p><b>刻意没实现的两条</b>：
 * 01 §8 的"水中被近战打伤害衰减 50%"——这版 Fabric 的 {@code ServerLivingEntityEvents} 只有
 * {@code ALLOW_DAMAGE}（能不能打）与 {@code AFTER_DAMAGE}（打完告知），<b>没有能改伤害数值的钩子</b>。
 * 要接只能"拦掉再补半刀"，那是会重复触发事件的脆招，不值得为一条待批规则做。
 * 还有 01 §8 的"体力不足时攀爬失败"。玩家侧没有体力系统
 * （02 的消耗挂在幸存者与口粮上，M3 才落地），现在硬编一个体力门槛就是脑补机制。
 */
public final class ClingRule {

	/** 扒住之后爬回甲板需要的时间：01 §8 写的 2 秒。 */
	public static final int CLIMB_TICKS = 40;

	/** 伸手能够到船壳的距离（格）。超出就算"游不回去"，得靠队友。 */
	public static final double HULL_REACH = 2.5;

	/**
	 * 甲板面在筏位姿原点上方几格：甲板方块占局部 {@code y ∈ [0,1)}，能站人的面就是 +1。
	 *
	 * <p><b>为什么改成"绝对目标高度"。</b>第一版是"每 tick 抬 {@code 1.3/40} 格"，
	 * 实测在水里 2.6 秒只净涨 0.016 格——下沉几乎把抬升全部抵消，"2 秒回筏"就变成
	 * 由水的参数决定而不是由 01 §8 决定。现在插值到甲板面：泡得多深只影响起点，不影响终点，
	 * 时序仍然由 {@link #CLIMB_TICKS} 一家说话。
	 */
	public static final double DECK_SURFACE_OFFSET = 1.0;

	/** 甲板面的世界 Y（筏位姿原点 Y + 一个方块高）。 */
	public static double deckSurfaceY(double poseY) {
		return poseY + DECK_SURFACE_OFFSET;
	}

	/**
	 * 第 {@code tick} 拍应该在的世界 Y：从 {@code baseY} 线性插值到 {@code deckY}。
	 *
	 * <p>线性是刻意的：探针要逐拍比"高度单调不减、终点正好是甲板面"，
	 * 换成缓动曲线这条断言就只能写成"大概齐"。
	 */
	public static double climbY(double baseY, double deckY, int tick) {
		if (tick <= 0) {
			return baseY;
		}
		if (tick >= CLIMB_TICKS) {
			return deckY;
		}
		return baseY + (deckY - baseY) * tick / CLIMB_TICKS;
	}

	/** 攀爬阶段。{@code LANDED} 是一次性的"刚刚上筏"，用于播报与探针取证，下一 tick 就回 {@code OFF}。 */
	public enum Stage { OFF, GRAB, CLIMB, LANDED }

	/** 某个玩家的扒边状态。不可变，靠 {@link #next} 推进。 */
	public record State(Stage stage, int tick) {

		public static State off() {
			return new State(Stage.OFF, 0);
		}

		public boolean climbing() {
			return stage == Stage.GRAB || stage == Stage.CLIMB;
		}
	}

	private ClingRule() {
	}

	/**
	 * 到筏体外壳的水平间隙：0 = 正贴着船壳，&gt;0 = 还差这么多格，&lt;0 不可能（在 footprint 内时就是 0）。
	 *
	 * <p>用<b>局部坐标</b>算，所以筏子开走、转向都不影响结论——前提在适配层：
	 * 它必须用 {@code SableRafts.worldToLocal}（位姿逆变换）把玩家换算进来。
	 */
	public static double hullGap(double lx, double lz, double halfWidth) {
		double dx = Math.max(0.0, Math.abs(lx) - halfWidth);
		double dz = Math.max(0.0, Math.abs(lz) - halfWidth);
		return Math.hypot(dx, dz);
	}

	/** 三道前置门的原因；null 表示有资格扒自家筏。 */
	public static String denyReason(boolean phaseAllows, boolean ownRaft, boolean raftPresent) {
		if (!phaseAllows) {
			return "当前阶段不开放攀爬（只在 PLAY，见 04 §2 不变式 4）";
		}
		if (!raftPresent) {
			return "这一队没有筏（还没生成或已经沉了）";
		}
		if (!ownRaft) {
			return "那不是你家筏：只能扒自己队的木筏";
		}
		return null;
	}

	/**
	 * 推进一个 tick。
	 *
	 * @param gap       到船壳的水平间隙（格），由 {@link #hullGap} 算
	 * @param damaged   这一 tick 内是否受到伤害（01 §8：攀爬中被打断会掉回水里）
	 * @return 新状态；{@code LANDED} 只在完成那一 tick 出现一次
	 */
	public static State next(State prev, boolean phaseAllows, boolean ownRaft, boolean raftPresent,
			boolean holding, boolean inWater, double gap, boolean damaged) {
		if (damaged && prev.climbing()) {
			return State.off();   // 被打落：从 0 开始，不保留进度
		}
		String denied = denyReason(phaseAllows, ownRaft, raftPresent);
		// 在水里只是"起爬"的门槛，不是"继续爬"的门槛：人在爬的过程中必然要离开水面，
		// 一路都要求在水里就会在爬到一半时把人弹回水里、重新开始 —— 实测就栽在这里
		// （rescue_probe 看到"扒住 3 次才上筏 1 次"，终点 Y 卡在水面线下方）。
		if (denied != null || !holding || gap > HULL_REACH) {
			return State.off();
		}
		if (!prev.climbing() && !inWater) {
			return State.off();   // 人没下水也够不着船壳时不该白爬：站在筏上按右键不算自救
		}
		if (prev.stage() == Stage.LANDED) {
			return State.off();   // 上筏是一次性事件，之后由"人在筏上"那条路接管
		}
		if (prev.stage() == Stage.OFF) {
			return new State(Stage.GRAB, 0);
		}
		int tick = prev.tick() + 1;
		if (tick >= CLIMB_TICKS) {
			return new State(Stage.LANDED, tick);
		}
		return new State(Stage.CLIMB, tick);
	}

	/** GRAB 那一拍绝不出力：01 §8 的顺序是"先扒住边缘，再花 2 秒爬回筏"，没抓住就起飞很怪。 */
	public static boolean liftsThisTick(Stage stage) {
		return stage == Stage.CLIMB;
	}

}
