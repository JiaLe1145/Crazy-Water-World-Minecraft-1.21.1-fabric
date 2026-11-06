package dev.nbw.domain.construct;

/**
 * 建造规则（01 §4.2「建造规则」的可运行版本，M2 里程碑条目"建造区、面积/方块数硬上限、只有本队可改"）。
 *
 * <p>这里<b>只有判定，没有方块、没有队伍、没有 Sable</b>：所有输入都是已经解析好的数字与布尔，
 * 所以每条规则都能单独测、也能测出"换了输入结论就变"的区分度（适配层负责把世界解析成这些输入）。
 *
 * <p><b>三条硬约束。</b>
 * <ol>
 *   <li><b>归属</b>：只有本队能改本队筏，公共海域一律不许动。长期服没有这条，grief 与误伤纠纷会直接
 *       拖垮运营（01 §4.2 原文的理由）。</li>
 *   <li><b>建造区</b>：水平半径 {@link #buildRadius(int)}（由 {@code limits.maxRaftArea} 换算）+
 *       垂直窗口 [{@link #MIN_LOCAL_Y}, {@link #MAX_LOCAL_Y}]，坐标是<b>相对甲板面的 plot 局部坐标</b>。
 *       "防无限铺地板把服务器 TPS 拖死"靠的就是这条——筏体方块数直接决定 Sable 的物理负载。</li>
 *   <li><b>数量上限</b>：总方块数 ≤ {@code maxBlocksPerRaft}，设施数 ≤ {@code maxFacilities}。</li>
 * </ol>
 *
 * <p><b>一条软约束：新方块必须邻接已有方块。</b>plot 是有限存储区，允许隔空造一格会得到
 * 一个没有支撑、也不进筏体形状的浮空方块——看起来像 bug，所以拒掉。
 *
 * <p><b>破坏只查归属与保护，不查建造区。</b>建造区外可能残留历史方块（改过配置、旧版本留下的），
 * 那时必须还能拆掉它；用建造区挡住拆除，等于让玩家对一个拆不掉的方块干瞪眼。
 *
 * <p><b>刻意没实现的一条</b>：01 §4.2 的"设施位随面积增长（每 9 格甲板 1 个设施位）"。
 * 按那个公式，7×7=49 格的初始筏只给 5 个位，而现在一条 {@code cww oar rig} 就能摆 4 台桨、
 * {@code cww sail rig} 再摆帆，会立刻撞线并让既有探针全红。设施位与面积的绑定是<b>玩法决策</b>
 * （要先重配初始筏尺寸与上限），不是工程决策，留在待批清单里。
 */
public final class BuildRules {

	/** 建造区下界：甲板面往下 1 格（允许在甲板下补一格龙骨）。 */
	public static final int MIN_LOCAL_Y = -1;

	/**
	 * 建造区上界：甲板面往上 7 格。
	 * 依据是两层舱室 + 屋顶够用（0 甲板、1~3 一层、4 楼板、5~7 二层）。
	 * {@code maxRaftArea=96} → 半径 4 时这个窗口的体积是 9×9×9 = 729，
	 * 远小于 {@code maxBlocksPerRaft=3200}，所以真正卡住建造规模的是面积与方块数，
	 * 这个窗口只负责别让人把筏子堆成山。
	 */
	public static final int MAX_LOCAL_Y = 7;

	private BuildRules() {
	}

	/**
	 * 面积上限 → 建造区水平半径：取<b>不超过该面积的最大奇数边长方形</b>。
	 *
	 * <p>字段名写的是"最大面积"，判定就不能允许铺出比它更大的 footprint。旧写法
	 * {@code floor(sqrt(96)) = 9} 会放开 19×19 = 361 列，等于把上限放大 3.7 倍，字段名当场没意义。
	 * 现在 {@code maxRaftArea=96} → 边长 9 → 半径 4（81 列 ≤ 96）；
	 * {@code 49} → 半径 3，正好等于 {@code RaftSpawner.DEFAULT_RADIUS} 的初始筏，
	 * 语义变成"这张图只许在原甲板范围内重建，不许扩建"——配置给多大就只能用多大，
	 * 小面积自然收敛到半径 0，不需要再写 {@code area<=4} 的特例。
	 * 边长取奇数是因为甲板在正中间，偶数边长会朝一个方向偏出一格。
	 */
	public static int buildRadius(int maxRaftArea) {
		if (maxRaftArea <= 0) {
			return 0;
		}
		int side = (int) Math.floor(Math.sqrt(maxRaftArea));
		if (side % 2 == 0) {
			side--;
		}
		return Math.max(0, (side - 1) / 2);
	}

	/** 局部坐标是否落在建造区内（水平半径 + 垂直窗口）。 */
	public static boolean inBuildZone(int lx, int ly, int lz, int radius) {
		return Math.abs(lx) <= radius && Math.abs(lz) <= radius && ly >= MIN_LOCAL_Y && ly <= MAX_LOCAL_Y;
	}

	/**
	 * 放置判定。
	 *
	 * @param playerTeam     放置者所在队（-1 = 没分队）
	 * @param raftTeam       被碰的筏属于哪队（-1 = 这不是筏，是公共海域）
	 * @param neighborMask   六邻格里"已有实心方块"的位掩码（0 = 悬空）
	 * @param facility       要放的是不是设施方块（帆/桨台/筏心）
	 * @return null 表示允许；否则是给玩家看的拒绝原因
	 */
	public static String checkPlace(boolean phaseAllowsBuild, int playerTeam, int raftTeam,
			int lx, int ly, int lz, int maxRaftArea, int neighborMask,
			int blockCount, int maxBlocks, boolean facility, int facilityCount, int maxFacilities) {
		String shared = sharedGate(phaseAllowsBuild, playerTeam, raftTeam);
		if (shared != null) {
			return shared;
		}

		int radius = buildRadius(maxRaftArea);
		if (!inBuildZone(lx, ly, lz, radius)) {
			return "超出建造区：局部坐标 (" + lx + ", " + ly + ", " + lz + ")，"
					+ "水平半径上限 " + radius + "（limits.maxRaftArea=" + maxRaftArea + "），"
					+ "高度窗口 [" + MIN_LOCAL_Y + ", " + MAX_LOCAL_Y + "]";
		}
		if (neighborMask == 0) {
			return "这里没有可依附的方块：新方块必须贴着已有筏体（上下东南西北六邻格之一）";
		}
		if (blockCount >= maxBlocks) {
			return "筏体方块数已达上限 " + blockCount + "/" + maxBlocks + "（limits.maxBlocksPerRaft）";
		}
		if (facility && facilityCount >= maxFacilities) {
			return "设施位已满 " + facilityCount + "/" + maxFacilities + "（limits.maxFacilities），先拆掉旧设施";
		}
		return null;
	}

	/**
	 * 破坏判定。刻意不查建造区，理由见类注释。
	 *
	 * @param protectedBlock 受保护方块（筏心）：只能走破拆伤害/长按重修那条路，不许直接拆
	 * @return null 表示允许；否则是拒绝原因
	 */
	public static String checkBreak(boolean phaseAllowsBuild, int playerTeam, int raftTeam,
			boolean protectedBlock) {
		String shared = sharedGate(phaseAllowsBuild, playerTeam, raftTeam);
		if (shared != null) {
			return shared;
		}
		if (protectedBlock) {
			return "筏心不能用拆方块的方式拆除（它走破拆伤害与沉筏倒计时，见 01 §4.3.2）";
		}
		return null;
	}

	/**
	 * 只查阶段 / 分队 / 归属三道前置门。
	 *
	 * <p>单独开放给适配层：这三门不需要扫描方块，越权请求应该在这里就被拒掉，
	 * 不该先付一次建造盒扫描再被 {@link #checkPlace} 拒绝——被拒的请求往往是别队来 grief 的，
	 * 让"最便宜的检查"先挡住"最该挡的人"，服务端才扛得住。
	 */
	public static String checkOwnership(boolean phaseAllowsBuild, int playerTeam, int raftTeam) {
		return sharedGate(phaseAllowsBuild, playerTeam, raftTeam);
	}

	/** 放置与破坏共用的三道前置门：阶段、分队、归属。 */
	private static String sharedGate(boolean phaseAllowsBuild, int playerTeam, int raftTeam) {
		if (!phaseAllowsBuild) {
			return "当前阶段不开放建造（建造只在 PLAY 阶段，见 04 §2 不变式 4）";
		}
		if (playerTeam < 0) {
			return "你没有分队，不能建造";
		}
		if (raftTeam < 0) {
			return "这里不是筏体：只能在自家筏的建造区内建造，公共海域禁止改动";
		}
		if (raftTeam != playerTeam) {
			return "那是队伍 " + raftTeam + " 的筏，禁止改动别队的木筏";
		}
		return null;
	}
}
