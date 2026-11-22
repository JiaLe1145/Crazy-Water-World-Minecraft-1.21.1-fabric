package dev.nbw.sable;

import dev.nbw.NBW;
import dev.nbw.domain.raft.RaftLayout;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Unit;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Sable 隔离层（01-技术架构：全工程只有这个包允许 import {@code dev.ryanhcode.sable.*}）。
 *
 * <p><b>公开面一律用 UUID，一个 Sable 类型都不漏出去。</b>
 * 这条不是洁癖：只要有一个方法把 {@code ServerSubLevel} 返回出去，上层就会开始存它、传它、调它的方法，
 * "隔离层"当场变成一句空话。UUID 可序列化、可落盘、可跨重启，正是回合状态机需要的东西。
 *
 * <p>本类只做三件事：找到筏、量筏、对筏施力。<b>不做任何玩法判定</b>——
 * 该不该沉、还剩几 tick，全在 {@code domain/raft/SinkRule} 那个纯函数里。
 */
public final class SableRafts {

	/**
	 * 对局期间 held 的加载票类型。
	 *
	 * <p>必须有：{@code sub_level_tracking_range} 默认 320 格（SableConfig.java:33-35），
	 * 缩圈后期筏子离人远就不再收快照、也可能被卸载。开局发票、结算收票。
	 * {@code SubLevelLoadingTicketType.create} 是公开的（:22），所以能注册我们自己的类型，
	 * 不必蹭它的 COMMAND_FORCED。
	 */
	public static final SubLevelLoadingTicketType<Unit> ROUND_ACTIVE =
			SubLevelLoadingTicketType.create(Identifier.of(NBW.MOD_ID, "round_active"), Unit.CODEC);

	private SableRafts() {
	}

	// ---------------------------------------------------------------- 生成与销毁

	/**
	 * 程序化生成一初始筏（M2-2）。做法照 Sable 自己的 {@code /sable spawn platform}
	 * （{@code SableSpawnCommands.java:484-521}）：分配 sub-level → 往 plot 铺甲板 →
	 * {@code updateLastPose()} → 摆到目标世界坐标。
	 *
	 * <p>为什么不用 {@code assembleBlocks}：那条路要求世界上已经存在这些方块，
	 * 开局发筏要的是"凭空造一艘形状可控、可复现的筏"，而且装配在水上还会踩连通域漏水的坑
	 * （见 {@code M0-TEST-PLAN.md} T0）。
	 *
	 * <p>plot 网格只有 16384 个槽位，{@code allocateNewSubLevel} 满了会抛
	 * {@code IllegalStateException}。这里把它翻译成可读失败而不是让对局崩：
	 * 长期服跑久了这是会真发生的。
	 *
	 * @return 新筏的 UUID；失败时返回 null 并把原因写进 {@code outError}（长度为 1 的数组当出参用，
	 *         是为了让调用方既能拿到成功值又能拿到具体原因，而不必再查日志）
	 */
	public static @Nullable UUID createRaft(ServerWorld world, double cx, double cy, double cz,
			int width, int length, BlockState deck, @Nullable String name, String[] outError) {
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		if (c == null) {
			outError[0] = "该维度不是 sub-level 承载维度";
			return null;
		}
		SubLevel subLevel;
		try {
			Pose3d pose = new Pose3d();
			pose.position().set(cx, cy, cz);
			subLevel = c.allocateNewSubLevel(pose);
		} catch (IllegalStateException e) {
			outError[0] = "plot 网格已满（上限 16384 槽位）：" + e.getMessage();
			NBW.LOGGER.error("[sable] 生成筏失败：{}", outError[0]);
			return null;
		}

		LevelPlot plot = subLevel.getPlot();
		// 甲板几何的唯一来源是 RaftLayout：帆位与桨位也从那里取。
		// 两边各写一遍公式，迟早出现"设施挂在甲板外面"这种 bug——4×4 偶数边长就是这么暴露的。
		int fromX = RaftLayout.deckStartX(width);
		int toX = RaftLayout.deckEndX(width);
		int fromZ = RaftLayout.deckStartZ(length);
		int toZ = RaftLayout.deckEndZ(length);
		int radiusChunks = (Math.max(width, length) - 1) / 16;
		ChunkPos center = plot.getCenterChunk();
		for (int x = -radiusChunks; x <= radiusChunks; x++) {
			for (int z = -radiusChunks; z <= radiusChunks; z++) {
				plot.newEmptyChunk(new ChunkPos(center.x + x, center.z + z));
			}
		}
		EmbeddedPlotLevelAccessor accessor = plot.getEmbeddedLevelAccessor();
		for (int x = fromX; x <= toX; x++) {
			for (int z = fromZ; z <= toZ; z++) {
				accessor.setBlockState(new BlockPos(x, 0, z), deck, 2);
			}
		}
		subLevel.updateLastPose();
		if (name != null) {
			subLevel.setName(name);
		}
		c.physicsSystem().getPipeline().teleport((ServerSubLevel) subLevel,
				new Vector3d(cx, cy, cz), subLevel.logicalPose().orientation());

		UUID id = subLevel.getUniqueId();
		NBW.LOGGER.info("[sable] 生成筏 '{}' id={} 尺寸={}x{} 甲板 {} 格，落点=({} {} {})",
				name, id, width, length, deckCount(width, length),
				String.format(java.util.Locale.ROOT, "%.1f", cx),
				String.format(java.util.Locale.ROOT, "%.1f", cy),
				String.format(java.util.Locale.ROOT, "%.1f", cz));
		return id;
	}

	/**
	 * 往筏的 plot 里写一格方块（筏心就是这么放进筏体中心的）。
	 *
	 * <p>坐标是 <b>plot 局部坐标</b>，不是世界坐标——筏子会动，
	 * 用世界坐标寻址 sub-level 里的方块是本项目已经明确否掉的做法
	 * （{@code /data modify block <原坐标>} 同理失效）。
	 * 带方块实体的方块由原版 {@code setBlockState} 负责创建实体，这里不自己 new。
	 */
	public static boolean setPlotBlock(ServerWorld world, UUID raftId, int lx, int ly, int lz, BlockState state) {
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return false;
		}
		return sl.getPlot().getEmbeddedLevelAccessor().setBlockState(new BlockPos(lx, ly, lz), state, 3);
	}

	/** 取 plot 局部坐标上的方块实体；不存在返回 null。 */
	public static @Nullable BlockEntity plotBlockEntity(ServerWorld world, UUID raftId, int lx, int ly, int lz) {
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return null;
		}
		return sl.getPlot().getEmbeddedLevelAccessor().getBlockEntity(new BlockPos(lx, ly, lz));
	}

	/** 把 plot 局部偏移上的一格换成空气（拆帆用）。 */
	public static boolean clearPlotBlock(ServerWorld world, UUID raftId, int lx, int ly, int lz) {
		return setPlotBlock(world, raftId, lx, ly, lz, Blocks.AIR.getDefaultState());
	}

	// ---------------------------------------------------------------- 建造规则用的解析与统计

	/**
	 * 交互坐标落在哪个筏上；不是筏（公共海域、海床）返回 null。
	 *
	 * <p><b>这里的 pos 不是玩家眼前的世界坐标。</b>Sable 的射线重写
	 * （{@code mixin/clip_overwrite/BlockGetterMixin.java:104-131}）先用筏的 {@code logicalPose}
	 * 把射线反投影进筏自己的<b>存储坐标系</b>，再在存储空间里求交并直接把该空间的命中结果交回去，
	 * 所以玩家点到的方块一律以存储坐标递交——筏子开出去多远都一样。
	 * 判定口径跟 Sable 自己对"我点的是不是 plot"的判定同源（{@code mixin/block_placement/
	 * BlockPlaceContextMixin.java:81} 用的 {@code getContaining} 内部就是比 plot 的区块范围），
	 * 这里只是换了个 yarn 编得过的写法，不是另发明一套。
	 */
	public static @Nullable UUID raftAt(ServerWorld world, BlockPos pos) {
		// 语义与 Sable 自己的 getContaining 一致（container.getPlot(chunkX, chunkZ) 判的就是
		// plot 的区块范围，LevelPlot.java:198-205），但绕开它调用：Sable 的 HELPER 走 companion
		// 桥接层，那里是 intermediary 类名（class_1937），yarn 工程在编译期拿不到那个类。
		for (ServerSubLevel sl : list(world)) {
			if (sl.getPlot().contains(pos.getX(), pos.getZ())) {
				return sl.getUniqueId();
			}
		}
		return null;
	}

	/**
	 * 筏 plot 的 accessor 原点在存储坐标系里的位置（Sable 定义为 plot 中心块）。
	 *
	 * <p>有了它才能把交互坐标换算成我们全局使用的 plot 局部坐标：
	 * {@code local = global - center}（{@code EmbeddedPlotLevelAccessor:105} 内部就是
	 * {@code local.offset(center)}，两侧必须用同一个原点，不能自己另推一个）。
	 */
	public static @Nullable BlockPos accessorOrigin(ServerWorld world, UUID raftId) {
		ServerSubLevel sl = resolve(world, raftId);
		return sl == null ? null : sl.getPlot().getCenterBlock();
	}

	/** 读一格 plot 局部坐标上的方块；筏不在时返回 null（与"读到空气"区分开）。 */
	public static @Nullable BlockState plotBlock(ServerWorld world, UUID raftId, int lx, int ly, int lz) {
		EmbeddedPlotLevelAccessor a = accessor(world, raftId);
		return a == null ? null : a.getBlockState(new BlockPos(lx, ly, lz));
	}

	/** 一格非空 plot 方块：局部坐标 + 方块状态。给重启后重建帆/设施登记用。 */
	public record PlotBlock(int lx, int ly, int lz, BlockState state) {
	}

	/**
	 * 扫描 plot 局部盒里的非空方块。只在恢复/诊断时调用（不是每 tick），
	 * 半径 12 × y[-1,8] 对 17×17 的筏约 6.9k 次读取。
	 */
	public static List<PlotBlock> plotBlocks(ServerWorld world, UUID raftId, int radius, int minY, int maxY) {
		EmbeddedPlotLevelAccessor a = accessor(world, raftId);
		if (a == null) {
			return List.of();
		}
		List<PlotBlock> out = new ArrayList<>();
		for (int y = minY; y <= maxY; y++) {
			for (int x = -radius; x <= radius; x++) {
				for (int z = -radius; z <= radius; z++) {
					BlockState state = a.getBlockState(new BlockPos(x, y, z));
					if (state != null && !state.isAir()) {
						out.add(new PlotBlock(x, y, z, state));
					}
				}
			}
		}
		return out;
	}

	/** 局部坐标 → 父世界存储坐标（设施服务的 Map<BlockPos,…> 键用的是存储坐标）。 */
	public static @Nullable BlockPos localToStorage(ServerWorld world, UUID raftId, int lx, int ly, int lz) {
		BlockPos center = accessorOrigin(world, raftId);
		return center == null ? null : center.add(lx, ly, lz);
	}

	/**
	 * 方块存储坐标 → 世界坐标（方块格中心）。
	 *
	 * <p>弩炮/投石机在 plot 里登记的是存储坐标，而射程要拿玩家世界坐标比；
	 * 直接拿存储坐标（约 2×10^7）当世界坐标用，距离永远是天量，设施永远不开火。
	 */
	public static double[] storageToWorld(ServerWorld world, UUID raftId, BlockPos pos) {
		double[] local = storageToLocal(world, raftId, pos);
		if (Double.isNaN(local[0])) {
			return new double[]{Double.NaN, Double.NaN, Double.NaN};
		}
		return localToWorld(world, raftId, local[0] + 0.5, local[1] + 0.5, local[2] + 0.5);
	}

	/**
	 * 局部坐标六邻格里"实心方块"的位掩码（建造邻接判定用：新方块必须贴着已有筏体）。
	 *
	 * <p>可替换的东西不算实心：水、雪、草这类 {@code isReplaceable} 的方块撑不住新方块，
	 * 把它们算进邻接会让"往水里搁地板"通过检查。
	 *
	 * @return 0 = 六个方向都是空的或水；筏不存在时返回 {@code -1}（调用方必须区别对待"查不到"）
	 */
	public static int neighborMask(ServerWorld world, UUID raftId, int lx, int ly, int lz) {
		EmbeddedPlotLevelAccessor a = accessor(world, raftId);
		if (a == null) {
			return -1;
		}
		int mask = 0;
		BlockPos base = new BlockPos(lx, ly, lz);
		for (Direction d : Direction.values()) {
			if (isSolid(a.getBlockState(base.offset(d)))) {
				mask |= 1 << d.ordinal();
			}
		}
		return mask;
	}

	/** 局部建造盒扫描结果：实心方块数、占用的水平列数、设施数。 */
	public record Scan(int blocks, int columns, int facilities, int loadedChunks) {
	}

	/**
	 * 扫描 {@code [-radius, radius]²  ×  [minY, maxY]} 的局部盒子，统计筏体方块。
	 *
	 * <p><b>只在事件里调，不在 tick 里调</b>：放置/破坏是玩家动作，频率上限就是人手速；
	 * 半径 9、y 窗口 [-1,7] 是 19×19×9 = 3249 次读取，和一次 map 查找同数量级地便宜。
	 * 这条约束写在 {@code BuildService} 的注释里，改动前请先看那里
	 * （04 §12 性能预算禁的是"每 tick 全图包围盒扫描"，不是"每次点击扫一遍建造区"）。
	 *
	 * @param facility 判定"这格算不算设施"（帆/桨台/筏心由上层给，本层不认识内容方块）
	 */
	public static @Nullable Scan scan(ServerWorld world, UUID raftId, int radius, int minY, int maxY,
			Predicate<BlockState> facility) {
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return null;
		}
		EmbeddedPlotLevelAccessor a = sl.getPlot().getEmbeddedLevelAccessor();
		int side = 2 * radius + 1;
		boolean[] column = new boolean[side * side];
		int blocks = 0;
		int facilities = 0;
		for (int y = minY; y <= maxY; y++) {
			for (int x = -radius; x <= radius; x++) {
				for (int z = -radius; z <= radius; z++) {
					BlockState state = a.getBlockState(new BlockPos(x, y, z));
					if (!isSolid(state)) {
						continue;
					}
					blocks++;
					column[(x + radius) * side + (z + radius)] = true;
					if (facility.test(state)) {
						facilities++;
					}
				}
			}
		}
		int columns = 0;
		for (boolean c : column) {
			if (c) {
				columns++;
			}
		}
		return new Scan(blocks, columns, facilities, sl.getPlot().getLoadedChunks().size());
	}

	/**
	 * 世界坐标 → 该筏的 plot 局部坐标（与 {@link #setPlotBlock}、建造规则同一个口径）。
	 *
	 * <p><b>这是"人在不在筏上"唯一正确的问法。</b>判定必须走 {@code logicalPose} 的逆变换：
	 * 筏子会开走，用世界坐标或 {@code getContaining}（它只比存储区块）去找"这个玩家所在的筏"
	 * 一定假阴性。Sable 自己的射线与伸手距离也是这条链
	 * （{@code BlockGetterMixin:116-117}、{@code interaction_distance/PlayerMixin:41}）。
	 *
	 * <p><b>两套"局部"坐标必须分清，这正是我在这里翻过一次车的地点：</b>
	 * 方块交互事件递交的是<b>存储坐标</b>（量级 2×10^7，换算局部要减 {@code getCenterBlock()}），
	 * 而玩家/实体的世界坐标经位姿逆变换出来<b>已经是甲板中心为原点的局部坐标</b>，不能再减。
	 * 两者数值上属于同一帧（甲板中心 = 0），但来源不同，混用会得到天文数字的距离。
	 * 可验证的锚点：把筏的位姿原点本身喂进来，必须得到 {@code (0, 0, 0)}
	 * ——因为甲板是写在局部 y=0 这一层、然后整体 teleport 到那个坐标的（探针钉住这条）。
	 *
	 * @return {@code {lx, ly, lz}}；筏不存在时返回 NaN 三元组（不返回 null，省得每个调用点都要判空）
	 */
	public static double[] worldToLocal(ServerWorld world, UUID raftId, double x, double y, double z) {
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return new double[]{Double.NaN, Double.NaN, Double.NaN};
		}
		// 自己做位姿逆变换：Sable 的 transformPositionInverse 声明在 companion 桥上，
		// 参数表里掺了 intermediary 类名（class_243），yarn 工程编译期看不见（和 raftAt 那次同一堵墙）。
		// 算法就是标准位姿逆：storage = q⁻¹ · (world − origin)，与 Sable 内部那条完全同义。
		Vector3d storage = new Vector3d(x, y, z);
		Vector3d origin = new Vector3d();
		sl.logicalPose().position().get(origin);
		storage.sub(origin);
		org.joml.Quaterniond q = new org.joml.Quaterniond(sl.logicalPose().orientation());
		q.invert();               // 单位四元数的逆就是共轭；这版 joml 的 Vector3d 没有 rotateConjugate
		storage.rotate(q);
		// 不再减 plot 中心块：logicalPose 映射的本来就是"以甲板中心为原点"的那套坐标，
		// 与 setPlotBlock / neighborMask 用的是同一帧（实测：人站在筏中心时这里必须得 (0,0,0)）。
		// 之前多减了一次 center，得到的是 ±2×10^7 那种量级的数，
		// 后果不是报错而是"明明贴着船壳却判够不着"，扒边整条路静默失效。
		return new double[]{storage.x, storage.y, storage.z};
	}

	/**
	 * <b>方块交互坐标</b>（Sable 递交的存储坐标）→ 同一套甲板局部坐标。
	 *
	 * <p>与 {@link #worldToLocal} 的分工必须写清楚，这两条经常被当成一条：
	 * 玩家/实体的世界坐标要过位姿逆变换（上面那条）；而交互事件与客户端准星交出来的
	 * {@code BlockPos} <b>已经是存储坐标了</b>，只需要减掉 accessor 的原点，<b>不做旋转</b>——
	 * sub-level 的方块物理上就存在父世界的那片存储区域里
	 * （{@code ServerSubLevelContainer.java:152} 把父 {@code ServerLevel} 直接交给了 sub-level，
	 * 实测父世界 {@code getBlockEntity(存储坐标)} 能读出 OarBlockEntity）。
	 * 换算出来的两组数值上落在<b>同一帧</b>（甲板中心=0），所以可以直接相减求距离
	 * （{@code domain/net/HoldPolicy.localDistance}）。
	 *
	 * <p>减的那个原点只能是 {@code plot.getCenterBlock()}：accessor 内部就是
	 * {@code blockPos.offset(this.center)}（{@code EmbeddedPlotLevelAccessor.java:100} 取 BE、
	 * {@code :105} 取方块，类注释 §47 也写明 "(0,0,0) at the plot center"），
	 * 换任何一个原点都会得到"读出来全是空气"这种静默失效。
	 *
	 * @return 方块<b>角</b>的局部坐标 {@code {lx, ly, lz}}；筏不在场时返回 NaN 三元组
	 */
	public static double[] storageToLocal(ServerWorld world, UUID raftId, BlockPos pos) {
		BlockPos center = accessorOrigin(world, raftId);
		if (center == null) {
			return new double[]{Double.NaN, Double.NaN, Double.NaN};
		}
		return new double[]{pos.getX() - center.getX(), pos.getY() - center.getY(), pos.getZ() - center.getZ()};
	}

	/**
	 * 甲板局部坐标 → 世界（显示）坐标：{@link #worldToLocal} 的正变换
	 * {@code world = origin + q · local}。
	 *
	 * <p><b>为什么需要它。</b>攀爬与拉回的插值只能在那套<b>不随筏子移动</b>的局部帧里算
	 * （起点、甲板面、footprint 边界都是局部量），但 {@code requestTeleport} 只认世界坐标。
	 * 逐拍"局部插值点→世界"而不是"记下起始世界坐标再加偏移"，才会在筏子开走之后
	 * 仍然把人放到筏子旁边——否则一加速，人就被留在起爬点那一格的半空里掉回海里。
	 */
	public static double[] localToWorld(ServerWorld world, UUID raftId, double lx, double ly, double lz) {
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return new double[]{Double.NaN, Double.NaN, Double.NaN};
		}
		Vector3d local = new Vector3d(lx, ly, lz);
		// 正变换就是共轭反过来：world = origin + q·local。与 worldToLocal 同一套 joml 写法。
		local.rotate(new org.joml.Quaterniond(sl.logicalPose().orientation()));
		Vector3d origin = new Vector3d();
		sl.logicalPose().position().get(origin);
		return new double[]{origin.x + local.x, origin.y + local.y, origin.z + local.z};
	}

	/** 实心 = 非空气且不可替换（水、雪、植被都不算）。 */
	private static boolean isSolid(BlockState state) {
		return state != null && !state.isAir() && !state.isReplaceable();
	}

	private static @Nullable EmbeddedPlotLevelAccessor accessor(ServerWorld world, UUID raftId) {
		ServerSubLevel sl = resolve(world, raftId);
		return sl == null ? null : sl.getPlot().getEmbeddedLevelAccessor();
	}

	/**
	 * 筏的线速度（世界坐标）。
	 * 返回 {@code double[3]} 而不是 joml 的 Vector3d：那是物理库的类型，不该漏给上层。
	 *
	 * <p>航行配平全靠这个数——帆有没有用、桨推得动推不动，都只能靠速率曲线判，
	 * 不能靠"看着像在动"。
	 */
	public static double[] linearVelocity(ServerWorld world, UUID raftId) {
		double nan = Double.NaN;
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return new double[]{nan, nan, nan};
		}
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		if (c == null) {
			return new double[]{nan, nan, nan};
		}
		RigidBodyHandle handle = c.physicsSystem().getPhysicsHandle(sl);
		if (handle == null || !handle.isValid()) {
			return new double[]{nan, nan, nan};
		}
		Vector3d dest = new Vector3d();
		handle.getLinearVelocity(dest);
		return new double[]{dest.x, dest.y, dest.z};
	}

	/**
	 * 筏子的<b>实时世界坐标</b>（{@code logicalPose}，与 {@link #posY} 同一来源）。
	 *
	 * <p>为什么需要它：航速必须按<b>真实位移</b>算。{@link #linearVelocity} 读到的是每 tick
	 * 施完冲量后的<b>瞬时峰值</b>，Sable 的水阻在同一个 tick 内就把绝大部分速度吃掉 ——
	 * 实测同一状态下位移只有读数的约 1/22（{@code docs/dev/M2-RESULTS.md} §3.10）。
	 * 所以推力配平与探针断言都要用逐 tick 位移差，不能拿那个峰值当"每秒走几格"。
	 *
	 * @return {@code {x, y, z}}；筏不在场或取不到位姿时三个分量都是 {@code NaN}
	 */
	public static double[] position(ServerWorld world, UUID raftId) {
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return new double[]{Double.NaN, Double.NaN, Double.NaN};
		}
		Vector3d p = sl.logicalPose().position();
		return new double[]{p.x, p.y, p.z};
	}

	/** 边长 side 的方形甲板格数（可以是偶数；4 → 16 格，就是现在的初始筏）。 */
	public static int deckCount(int width, int length) {
		return RaftLayout.deckCells(width, length);
	}

	public static int deckCount(int side) {
		return RaftLayout.deckCells(side);
	}

	/** 销毁筏。局后清理用；{@code reason} 由 Sable 记录在存储层。 */
	public static boolean remove(ServerWorld world, UUID raftId) {
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		ServerSubLevel sl = resolve(world, raftId);
		if (c == null || sl == null) {
			return false;
		}
		c.removeSubLevel(sl, SubLevelRemovalReason.REMOVED);
		return true;
	}

	/** 列出盘上/内存里的筏（名字 + id），给残留复检与运维用。 */
	public static List<String> describeAll(ServerWorld world) {
		List<String> out = new ArrayList<>();
		for (ServerSubLevel sl : list(world)) {
			out.add((sl.getName() == null ? "<unnamed>" : sl.getName()) + "=" + sl.getUniqueId());
		}
		return out;
	}

	// ---------------------------------------------------------------- 查找

	/**
	 * 删除名字以 {@code prefix} 开头的全部 sub-level。
	 *
	 * <p>为什么需要：开局发筏的唯名是 {@code nbw_raft_<队>_<局>}，上一局/重启残留的旧名
	 * 与当前局不同，{@code findByName} 只认精确名，清不到它们。旧筏与出生点上的新筏重叠时，
	 * 新筏会被顶住/倾斜（2026-11-14 test_sea 队1 实测）。这里按前缀做一次全局清扫。
	 *
	 * @return 被删除条目的 {@code name=id} 清单，给日志与运维核对
	 */
	public static List<String> removeByPrefix(ServerWorld world, String prefix) {
		List<String> removed = new ArrayList<>();
		for (ServerSubLevel sl : list(world)) {
			String name = sl.getName();
			if (name != null && name.startsWith(prefix) && remove(world, sl.getUniqueId())) {
				removed.add(name + "=" + sl.getUniqueId());
			}
		}
		return removed;
	}

	/** 按名字找筏（名字来自 {@code /sable name set} 或我们的开局生成逻辑）。重名时取第一个并告警。 */
	public static @Nullable UUID findByName(ServerWorld world, String name) {
		ServerSubLevel found = null;
		int hits = 0;
		for (ServerSubLevel sl : list(world)) {
			if (name.equals(sl.getName())) {
				hits++;
				if (found == null) {
					found = sl;
				}
			}
		}
		if (hits > 1) {
			NBW.LOGGER.warn("[sable] 名字 '{}' 命中 {} 个 sub-level，取第一个；筏-队绑定可能有重复", name, hits);
		}
		return found == null ? null : found.getUniqueId();
	}

	/** 离给定点最近的筏，语义对齐 Sable 自己的 {@code @n}（SubLevelTargetSelector.java:55-70）。 */
	public static @Nullable UUID nearestTo(ServerWorld world, double x, double y, double z) {
		ServerSubLevel best = null;
		double closest = Double.MAX_VALUE;
		for (ServerSubLevel sl : list(world)) {
			var p = sl.logicalPose().position();
			double d = p.distanceSquared(x, y, z);
			if (d < closest) {
				closest = d;
				best = sl;
			}
		}
		return best == null ? null : best.getUniqueId();
	}

	/** 当前世界所有 sub-level 的 UUID，给运维列清单用。 */
	public static List<UUID> ids(ServerWorld world) {
		List<UUID> out = new ArrayList<>();
		for (ServerSubLevel sl : list(world)) {
			out.add(sl.getUniqueId());
		}
		return out;
	}

	// ---------------------------------------------------------------- 测量

	/** 筏的当前质量（由方块算出，Sable 没有质量写入口）。用于日志、平衡核对与沉筏力计算。 */
	public static double mass(ServerWorld world, UUID raftId) {
		ServerSubLevel sl = resolve(world, raftId);
		return sl == null ? Double.NaN : sl.getMassTracker().getMass();
	}

	/** 筏中心的世界 Y。用来客观判断"真的沉下去了没有"，不靠肉眼。 */
	public static double posY(ServerWorld world, UUID raftId) {
		ServerSubLevel sl = resolve(world, raftId);
		return sl == null ? Double.NaN : sl.logicalPose().position().y();
	}

	/** 筏还在不在。沉没 / 被 shatter / 被卸载之后返回 false。 */
	public static boolean present(ServerWorld world, UUID raftId) {
		return resolve(world, raftId) != null;
	}

	/**
	 * 筏体姿态的倾斜角（度）：局部"上"方向与世界竖直的夹角。
	 *
	 * <p><b>为什么需要它：</b>用户 2026-11-22 反馈"速度过快，木筏会翻倒入水"。
	 * "翻倒"是姿态问题，光看 Y/速度看不出来（筏子翻成 90° 时中心 Y 甚至可能没怎么变）。
	 * 这条读数是唯一能客观判定"翻了没有"的量：0° = 完全水平，&gt;60° 基本就是翻了。
	 *
	 * <p>算法：把局部 up（0,1,0）用位姿四元数旋到世界系，取它与 (0,1,0) 的夹角。
	 * 不含偏航（yaw）—— 筏子转圈是正常的，只有俯仰/横滚才算倾斜。
	 *
	 * @return 倾斜角（度）；筏不在场时返回 {@code NaN}
	 */
	public static double tiltDeg(ServerWorld world, UUID raftId) {
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return Double.NaN;
		}
		Vector3d up = new Vector3d(0.0, 1.0, 0.0);
		up.rotate(new org.joml.Quaterniond(sl.logicalPose().orientation()));
		double cos = Math.max(-1.0, Math.min(1.0, up.y));
		return Math.toDegrees(Math.acos(cos));
	}

	/**
	 * 姿态稳定（防翻）。每 tick 对每艘在场筏调一次：<b>夹住俯仰/横滚角速度</b>，
	 * 并在倾斜超过阈值时给一个**回正角冲量**。
	 *
	 * <p><b>为什么需要这一层（用户 2026-11-22："速度过快…出现木筏翻倒入水"）。</b>
	 * 我们给筏子的推力是<b>质心上的水平冲量</b>，而 Sable 的水阻作用在浸没的方块上（船底），
	 * 两者不在同一条作用线上 —— 一加速就必然产生一个让船头点下去的力矩。
	 * 速度越快、力矩越大；一旦倾斜到某个角度，浮心与重心错开，就会自己继续翻过去（失稳）。
	 * 实测（7 帆 + 风暴 + 左转）：倾斜峰值 12.9°，稳态 8~9°。
	 * 这不是靠"少推一点"能彻底解决的（风照样在推），所以补一层姿态控制。
	 *
	 * <p>做法刻意保守，只管两件"物理上一定会失控"的事：
	 * <ol>
	 *   <li><b>阻尼</b>：俯仰/横滚角速度超过 {@code maxTiltRate} 就反向补一记，把转速拉回上限内。</li>
	 *   <li><b>回正</b>：倾斜超过 {@code maxTiltDeg} 后按超出量给回正角冲量（有单 tick 上限），
	 *       相当于给筏子一个"扶正"的小力矩。偏航（yaw）完全不碰 —— 转向是玩法。</li>
	 * </ol>
	 *
	 * @param maxTiltDeg   允许的自然倾斜（低于它不干预）；实测静止倾斜 2~3°，建议 8
	 * @param rightGain    每超出 1° 施加的回正角冲量
	 * @param maxRight     单 tick 回正角冲量上限（防止大角度时一记猛掰）
	 * @param maxTiltRate  俯仰/横滚角速度上限（rad/s）
	 * @return true = 本 tick 施加了修正
	 */
	public static boolean stabilize(ServerWorld world, UUID raftId, double maxTiltDeg,
			double rightGain, double maxRight, double maxTiltRate) {
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return false;
		}
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		if (c == null) {
			return false;
		}
		RigidBodyHandle handle = c.physicsSystem().getPhysicsHandle(sl);
		if (handle == null || !handle.isValid()) {
			return false;
		}
		org.joml.Quaterniond q = new org.joml.Quaterniond(sl.logicalPose().orientation());
		Vector3d up = new Vector3d(0.0, 1.0, 0.0).rotate(q);
		Vector3d worldUp = new Vector3d(0.0, 1.0, 0.0);
		double tiltDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, up.y))));
		Vector3d omega = new Vector3d();
		handle.getAngularVelocity(omega);
		// 俯仰/横滚分量 = 除竖直（偏航）以外的部分；倾斜很小时这个近似足够
		Vector3d tiltRate = new Vector3d(omega.x, 0.0, omega.z);
		Vector3d correction = new Vector3d();
		// ① 角速度阻尼：把俯仰/横滚转速拉回上限
		double rate = tiltRate.length();
		if (rate > maxTiltRate && rate > 1e-9) {
			correction.fma(-(rate - maxTiltRate) / rate, tiltRate);
		}
		// ② 回正：倾斜超过阈值时沿"up × worldUp"轴向反方向扶正
		Vector3d axis = new Vector3d(up).cross(worldUp);
		if (tiltDeg > maxTiltDeg && axis.lengthSquared() > 1e-9) {
			axis.normalize();
			double strength = Math.min(maxRight, (tiltDeg - maxTiltDeg) * rightGain);
			correction.fma(strength, axis);
		}
		if (correction.lengthSquared() < 1e-12) {
			return false;
		}
		handle.applyAngularImpulse(correction);
		return true;
	}

	/** 筏的竖直速度（世界 Y 分量）。给"筏停稳了没有"用。
	 *
	 * <p>开局入图闸门要等筏停稳再放人：发筏那一刻筏是"凭空出现在目标坐标"的，
	 * 目标坐标只要比水面高一点，它就会先自由落体、再被浮力顶回来。
	 * 人这时候传上去，看到的就是"脚下木筏慢慢浮上来"。
	 *
	 * @return 竖直速度；筏不在场、或物理体还没就绪时返回 {@code NaN}
	 *         （调用方要按"不知道"处理，不能当成 0 = 已停稳）
	 */
	public static double velocityY(ServerWorld world, UUID raftId) {
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return Double.NaN;
		}
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		if (c == null) {
			return Double.NaN;
		}
		RigidBodyHandle handle = c.physicsSystem().getPhysicsHandle(sl);
		if (handle == null || !handle.isValid()) {
			return Double.NaN;
		}
		Vector3d v = new Vector3d();
		handle.getLinearVelocity(v);
		return v.y;
	}

	// ---------------------------------------------------------------- 施力

	/**
	 * 沉筏：把筏的<b>有效重量</b>放大到 N 倍，持续一 tick。
	 *
	 * <p>玩法说的就是"将木筏重量加大"（01 §4.3.1），而 Sable 里质量完全由方块算出，
	 * {@code MassTracker} 只有 getter、全仓没有 setMass / gravityScale / massMultiplier，
	 * 所以用等效力实现：重力已经由 Sable 的 GRAVITY 力组施加，我们只补上 <b>(N−1) 倍的那部分</b>，
	 * 于是浮力仍是 1 倍而向下是 N 倍 → 筏被压下水。不破坏筏体方块，也不丢箱子里的东西。
	 *
	 * <p><b>为什么不用"每 tick 一个固定加速度"这个裸常数</b>：实测
	 * {@code applyLinearImpulse} 的注释写的是 {@code [N]}，它是<b>力</b>不是冲量
	 * （{@code RigidBodyHandle.java:115-121}）。
	 * {@code mass × 0.05} 只有重力的不到 1%，筏子纹丝不动；
	 * 而 {@code mass × 30} 直接把筏<b>穿过池底石头压下去 50 格</b>。
	 * 用重力倍数表达就不有这个尺度问题，而且换维度自动正确
	 * （重力取自 {@code DimensionPhysicsData.getGravity}，:57-62）。
	 *
	 * @param weightMultiplier 有效重量倍数；1.0 等于不施力。建议 2–4
	 * @return 是否真的施到了力；false 说明筏已经不在了
	 */
	public static boolean sink(ServerWorld world, UUID raftId, double weightMultiplier) {
		if (weightMultiplier <= 1.0) {
			return false;
		}
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return false;
		}
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		if (c == null) {
			return false;
		}
		RigidBodyHandle handle = c.physicsSystem().getPhysicsHandle(sl);
		if (handle == null || !handle.isValid()) {
			return false;
		}
		Vector3d gravity = DimensionPhysicsData.getGravity(world, sl.logicalPose().position(), new Vector3d());
		// 向下"补充"的重力 = 质量 × 重力 × (倍数 - 1)；gravity 本身已是负 Y
		gravity.mul(sl.getMassTracker().getMass() * (weightMultiplier - 1.0));
		handle.applyLinearImpulse(gravity);
		return true;
	}

	/**
	 * 施加一个世界坐标方向的力（风用）。
	 *
	 * <p>与 {@link #sink} 的区别很关键：sink 是"变重"，所以按质量成比例；
	 * 风是<b>真实的外力</b>，不该乘质量——同一阵风吹大筏和小筏，加速度本就应该不同。
	 * 乘了质量会让风对大筏不公平地更强。
	 *
	 * @return 是否真的施到了力
	 */
	public static boolean push(ServerWorld world, UUID raftId, double forceX, double forceY, double forceZ) {
		if (forceX == 0.0 && forceY == 0.0 && forceZ == 0.0) {
			return false;
		}
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return false;
		}
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		if (c == null) {
			return false;
		}
		RigidBodyHandle handle = c.physicsSystem().getPhysicsHandle(sl);
		if (handle == null || !handle.isValid()) {
			return false;
		}
		handle.applyLinearImpulse(new Vector3d(forceX, forceY, forceZ));
		return true;
	}

	/**
	 * 施加一个绕 Y 轴的角冲量（遥控器 A/D 转向用）。
	 *
	 * <p>与线性推力的区别：转向不改变质心速度，只改变朝向。数值是"角冲量"（力×1 tick 的量纲），
	 * 大小靠 {@code RemoteService} 按"每秒转几度"配平，这里只做无脑转发。
	 *
	 * @return 是否真的施到了角冲量；false 说明筏已经不在了
	 */
	public static boolean applyYawImpulse(ServerWorld world, UUID raftId, double yawImpulse) {
		if (yawImpulse == 0.0) {
			return false;
		}
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return false;
		}
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		if (c == null) {
			return false;
		}
		RigidBodyHandle handle = c.physicsSystem().getPhysicsHandle(sl);
		if (handle == null || !handle.isValid()) {
			return false;
		}
		// 绕世界 Y 轴（上）旋转；正负号约定由 RemoteService 负责，这里保持 joml 的原样。
		handle.applyAngularImpulse(new Vector3d(0.0, yawImpulse, 0.0));
		return true;
	}

	/**
	 * 筏当前的<b>水平朝向单位矢量</b>（世界坐标）：把局部前进方向 (0,0,1) 用位姿旋转后取 XZ。
	 *
	 * <p>这是"船帆永远跟随前进方向"与"W 前进该往哪推"的唯一口径——不能拿 {@code FACING} 之类的
	 * 方块状态顶替，筏子转了那个状态也不会跟着转。返回值已经是单位矢量，方向不明确时退回正南。
	 *
	 * @return {@code {dirX, dirZ}}，水平单位矢量
	 */
	public static double[] forward(ServerWorld world, UUID raftId) {
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return new double[]{0.0, 1.0};
		}
		Vector3d fwd = new Vector3d(0.0, 0.0, 1.0);
		fwd.rotate(new org.joml.Quaterniond(sl.logicalPose().orientation()));
		double len = Math.hypot(fwd.x, fwd.z);
		if (Double.isNaN(len) || len < 1e-9) {
			return new double[]{0.0, 1.0};
		}
		return new double[]{fwd.x / len, fwd.z / len};
	}

	/** 筏的角速度（世界坐标，绕各轴）。返回 {@code double[3]}；筏不在场时三个分量都是 {@code NaN}。 */
	public static double[] angularVelocity(ServerWorld world, UUID raftId) {
		double nan = Double.NaN;
		ServerSubLevel sl = resolve(world, raftId);
		if (sl == null) {
			return new double[]{nan, nan, nan};
		}
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		if (c == null) {
			return new double[]{nan, nan, nan};
		}
		RigidBodyHandle handle = c.physicsSystem().getPhysicsHandle(sl);
		if (handle == null || !handle.isValid()) {
			return new double[]{nan, nan, nan};
		}
		Vector3d dest = new Vector3d();
		handle.getAngularVelocity(dest);
		return new double[]{dest.x, dest.y, dest.z};
	}

	// ---------------------------------------------------------------- 加载票

	/** 开局发票：对局期间禁止该筏被卸载。 */
	public static boolean holdLoaded(ServerWorld world, UUID raftId) {
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		ServerSubLevel sl = resolve(world, raftId);
		return c != null && sl != null && c.addForceLoadTicket(sl, ROUND_ACTIVE, Unit.INSTANCE);
	}

	/** 结算收票。漏收会让死筏永久占着加载槽（plot 网格只有 16384 个槽位）。 */
	public static boolean releaseLoaded(ServerWorld world, UUID raftId) {
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		ServerSubLevel sl = resolve(world, raftId);
		return c != null && sl != null && c.removeForceLoadTicket(sl, ROUND_ACTIVE, Unit.INSTANCE);
	}

	// ---------------------------------------------------------------- 内部

	/** 容器可能为 null（该维度不是 sub-level 承载维度），所以列表允许为空。 */
	private static List<ServerSubLevel> list(ServerWorld world) {
		ServerSubLevelContainer c = SubLevelContainer.getContainer(world);
		return c == null ? List.of() : new ArrayList<>(c.getAllSubLevels());
	}

	private static @Nullable ServerSubLevel resolve(ServerWorld world, UUID raftId) {
		for (ServerSubLevel sl : list(world)) {
			if (sl.getUniqueId().equals(raftId)) {
				return sl;
			}
		}
		return null;
	}
}
