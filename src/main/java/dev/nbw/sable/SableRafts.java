package dev.nbw.sable;

import dev.nbw.NBW;
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
			int radius, BlockState deck, @Nullable String name, String[] outError) {
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
		int radiusChunks = (radius + 8) / 16;
		ChunkPos center = plot.getCenterChunk();
		for (int x = -radiusChunks; x <= radiusChunks; x++) {
			for (int z = -radiusChunks; z <= radiusChunks; z++) {
				plot.newEmptyChunk(new ChunkPos(center.x + x, center.z + z));
			}
		}
		EmbeddedPlotLevelAccessor accessor = plot.getEmbeddedLevelAccessor();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
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
		NBW.LOGGER.info("[sable] 生成筏 '{}' id={} 半径={} 甲板 {} 格，落点=({} {} {})",
				name, id, radius, deckCount(radius),
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

	/** (2r+1)² —— 纯算式，给日志与单测用。 */
	public static int deckCount(int radius) {
		return (2 * radius + 1) * (2 * radius + 1);
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
