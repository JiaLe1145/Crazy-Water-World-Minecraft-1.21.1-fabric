package dev.nbw.content;

import dev.nbw.NBW;
import dev.nbw.raft.RaftSpawner;
import dev.nbw.raft.Sailing;
import dev.nbw.sable.SableRafts;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 帆（M2 航行入口）。
 *
 * <p><b>方块自己实现 {@link BlockSubLevelLiftProvider}</b>，不需要方块实体：
 * {@code ServerLevelPlot.java:668-669} 在方块进 plot 时按 {@code instanceof} 自动登记，
 * 并把 {@code sable$getNormal} 的结果存进 LiftProviderContext。
 * 这是 {@code content/} 里第二处刻意的 Sable import 例外，理由与 BE 那处一样——
 * 注册机制靠类型判断，加适配器就永远不会被登记。
 *
 * <p>气动公式也不用写：接口的 default 实现 {@code sable$contributeLiftAndDrag} 是一套完整翼型
 * （平行阻力 + 无向阻力 + 升力，都乘 {@code 空气压力 × 时间步}），我们只给法向和三个系数。
 *
 * <p><b>必须先接受的事实</b>：Sable 默认维度物理的 {@code basePressure = 1.0}，
 * 而压力是<b>只随 Y 变化的标量</b>（{@code DimensionPhysics.createDefault} 用
 * {@code exp(-0.004·(y-seaLevel))} 拟合贝塞尔），<b>没有风向矢量</b>。
 * 静风时帆相对空气的速度就是筏自己的速度，升力项推不出前进方向——
 * 所以帆的真实作用是<b>阻力面 / 龙骨</b>，不是发动机。
 * 这条由 {@code tools/sail_probe.py} 直接测：从静止放帆不该自己动；给冲量后帆越多减速越快。
 * 两条只要反了，说明我对这套气动的理解是错的，要回头重读而不是接着配数值。
 */
public class SailBlock extends Block implements BlockSubLevelLiftProvider {

	public static final DirectionProperty FACING = DirectionProperty.of("facing", Direction.Type.HORIZONTAL);

	/** 升力系数。接口默认 0.475。改了必须同步重算 {@link #DIRECTIONLESS_DRAG}。 */
	public static final float LIFT_SCALAR = 0.475f;
	public static final float PARALLEL_DRAG = 0.75f;

	/**
	 * 无向阻力系数，下限由接口注释写死：
	 * {@code >= (-k1 + sqrt(k1² + k2²)) / 2}，低于它会<b>指数式增速</b>。
	 * 按 k1=0.75、k2=0.475 算出来就是 0.06888202261。
	 *
	 * <p>这是配平红线：数值表里谁想调帆面积或升力，都必须连这个一起重算，
	 * 否则会出现"装了帆越跑越快直到飞出去"——长期服最难看的一类问题。
	 */
	public static final float DIRECTIONLESS_DRAG = 0.06888202261f;

	public SailBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/**
	 * 帆的法向 = 它面对的方向。
	 *
	 * <p>Sable 取的是 {@code Vec3.atLowerCornerOf(dir.getNormal())}，即方向矢量的整数分量，
	 * 所以<b>法向只能轴对齐，没有"斜置帆"这种自由度</b>。
	 * 想要攻角效果只能靠多块帆拼——{@code groupLiftProviders} 会把邻接的帆 flood-fill 成一组
	 * 并算出升力中心与阻力中心，那才是这套接口给我们的"帆越大越有力"的正确用法。
	 */
	@Override
	@NotNull
	public Direction sable$getNormal(BlockState state) {
		return state.get(FACING);
	}

	@Override
	public float sable$getLiftScalar() {
		return LIFT_SCALAR;
	}

	@Override
	public float sable$getParallelDragScalar() {
		return PARALLEL_DRAG;
	}

	@Override
	public float sable$getDirectionlessDragScalar() {
		return DIRECTIONLESS_DRAG;
	}

	/**
	 * 右键转帆：在 4 个水平朝向里循环（**方块局部**朝向，不是世界东南西北）。
	 *
	 * <p>2026-11-23 起帆是<b>航向参考物</b>：它面对的方向就是遥控器 W 的前进方向，
	 * 所以"转帆"等于"改船头"。转完必须同步 {@link Sailing} 的登记表，
	 * 否则 {@code Sailing.heading} 还按旧朝向给方向。
	 */
	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
			BlockHitResult hit) {
		if (world.isClient()) {
			return ActionResult.SUCCESS;   // 客户端只负责挥手，真正的状态改在服务端
		}
		Direction next = state.get(FACING).rotateYClockwise();
		world.setBlockState(pos, state.with(FACING, next), Block.NOTIFY_ALL);
		if (world instanceof ServerWorld sw) {
			UUID raftId = SableRafts.raftAt(sw, pos);
			BlockPos origin = raftId == null ? null : SableRafts.accessorOrigin(sw, raftId);
			if (raftId != null && origin != null) {
				int team = RaftSpawner.teamOf(raftId);
				int lx = pos.getX() - origin.getX();
				int ly = pos.getY() - origin.getY();
				int lz = pos.getZ() - origin.getZ();
				Sailing.unregisterPlaced(team, lx, ly, lz);
				Sailing.registerPlaced(team, lx, ly, lz, next);
				NBW.LOGGER.info("[sail] 队 {} 转帆 {} -> {}（这是该筏现在的前进方向）",
						team, state.get(FACING).asString(), next.asString());
				player.sendMessage(net.minecraft.text.Text.literal(
						"§b[cww] 船帆已转向 " + next.asString() + "（= 前进方向）"), true);
			}
		}
		return ActionResult.CONSUME;
	}

	/**
	 * 玩家手动放帆：登记进 {@link Sailing} 的队表，风才算得到这一面。
	 *
	 * <p>2026-11-21 补。此前帆只能由 {@code /cww sail rig}（权限 2）挂上，
	 * 真实对局里玩家拿不到帆、也挂不上帆 —— 而帆是唯一推力来源，等于整局动不了。
	 *
	 * <p>局部坐标的算法与 {@code BuildService.resolve} 一致：{@code 世界坐标 - accessor 原点}。
	 * 帆不在任何筏上（例如搭在大厅平台上）就只放方块、不登记。
	 */
	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world.isClient() || !(world instanceof ServerWorld sw)) {
			return;
		}
		UUID raftId = SableRafts.raftAt(sw, pos);
		if (raftId == null) {
			return;
		}
		BlockPos origin = SableRafts.accessorOrigin(sw, raftId);
		if (origin == null) {
			return;
		}
		Sailing.registerPlaced(RaftSpawner.teamOf(raftId),
				pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ(),
				state.get(FACING));
	}

	/**
	 * 拆帆 / 换块：把这条记录摘掉，否则拆了帆风速还按旧帆数算。
	 *
	 * <p>日志原来写的是"放置"，实际是"被替换/移除"，一并改掉。
	 */
	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos,
			BlockState newState, boolean moved) {
		super.onStateReplaced(state, world, pos, newState, moved);
		if (world.isClient() || state.getBlock() == newState.getBlock() || !(world instanceof ServerWorld sw)) {
			return;
		}
		UUID raftId = SableRafts.raftAt(sw, pos);
		if (raftId == null) {
			return;
		}
		BlockPos origin = SableRafts.accessorOrigin(sw, raftId);
		if (origin == null) {
			return;
		}
		int team = RaftSpawner.teamOf(raftId);
		if (Sailing.unregisterPlaced(team,
				pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ())) {
			return;   // 已经打过注销日志，不必再打一条
		}
		NBW.LOGGER.debug("[sail] 移除 pos={} facing={}（这条不在手放登记表里）", pos, state.get(FACING));
	}
}
