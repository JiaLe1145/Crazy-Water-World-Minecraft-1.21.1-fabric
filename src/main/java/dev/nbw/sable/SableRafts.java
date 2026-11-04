package dev.nbw.sable;

import dev.nbw.NBW;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.world.ServerWorld;
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
