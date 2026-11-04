package dev.nbw.content;

import dev.nbw.NBW;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

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

	/** 放置时留一条证据，方便在日志里数"到底登记了几块帆"。 */
	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos,
			BlockState oldState, boolean moved) {
		super.onStateReplaced(state, world, pos, oldState, moved);
		if (!world.isClient() && state.getBlock() != oldState.getBlock()) {
			NBW.LOGGER.info("[sail] 放置 pos={} facing={}", pos, state.get(FACING));
		}
	}
}
