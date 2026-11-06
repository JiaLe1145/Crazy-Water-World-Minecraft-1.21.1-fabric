package dev.nbw.content;

import dev.nbw.NBW;
import dev.nbw.raft.OarService;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 桨台（M2-5 动力第二源，01 §4.4 已批准的"桨台驱动"，02 §6"木桨"行的方块化落点）。
 *
 * <p><b>方块自己不算力、不持有节奏。</b>推力口径在 {@link OarService}，
 * 数值口径在 {@code domain/raft/OarModel}。这里只回答一个问题：
 * "点我的人属于哪队、用的是第几号桨位"。
 *
 * <p>朝向放在方块属性上（与帆一致），因为"桨朝哪"是玩家能用眼睛核对的信息，必须进
 * 方块状态与渲染。但<b>推力方向以 BE 里那份为准</b>：方块状态负责显示，
 * BE 负责记账（还带着 plot 偏移，拆桨与重启回填都靠它）。
 *
 * <p><b>一次右键 = 一次 keepalive</b>（与 {@link RaftCoreBlock} 同一条纪律）：
 * 真正的"连续划桨"来自客户端长按周期包（{@code dev.nbw.client.HoldInputClient}），
 * 两条输入路径与 dev 入口 {@code /cww oar hold} 最终都汇到 {@link OarService#keepalive}
 * 背后的同一条登记，所以不存在"只有测试能划"的分叉。
 */
public class OarBlock extends Block implements BlockEntityProvider {

	public static final DirectionProperty FACING = DirectionProperty.of("facing", Direction.Type.HORIZONTAL);

	public OarBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new OarBlockEntity(pos, state);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
			BlockHitResult hit) {
		if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) {
			return ActionResult.PASS;
		}
		// 归属与桨位都从"点到的那个 BE"读，不按坐标查表：sub-level 里 BE 的 getPos()
		// 是 plot 局部量，与交互回调给的坐标不保证同一空间（同 RaftCoreBlock 的理由）。
		if (!(world.getBlockEntity(pos) instanceof OarBlockEntity be) || !be.isRigged()) {
			return ActionResult.PASS;   // 手工摆的桨台没有归属：划不动，也不许白得动力
		}
		OarService.keepalive(sp.getUuid(), be.teamIndex(), be.slot());
		return ActionResult.SUCCESS;
	}

	/** 放/拆桨台各留一条证据，探针数"到底挂上几台"时不用靠肉眼。 */
	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos,
			BlockState oldState, boolean moved) {
		super.onStateReplaced(state, world, pos, oldState, moved);
		if (!world.isClient() && state.getBlock() != oldState.getBlock()) {
			NBW.LOGGER.info("[oar] 方块写入 pos={} facing={}（桨位与归属由 rig 负责）", pos, state.get(FACING));
		}
	}
}
