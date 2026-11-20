package dev.nbw.content;

import dev.nbw.round.CatapultService;
import dev.nbw.round.RoundManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

import java.util.HashSet;
import java.util.Set;

/**
 * 投石机方块（09 §2.2 设施武器）：放上筏后由 {@link CatapultService} 每 5 秒对
 * 40 格内最近的敌方筏心打 24 HP。
 *
 * <p><b>真 2×2 多方块</b>：与 {@link BallistaBlock} 同一套机制 —— 主方块 + 3 个从方块，
 * 从方块不可见、无碰撞、不可被替换，因此那 3 格真正占住；拆任意一格连带拆整组。
 *
 * <p>主方块位于 2×2 的 <b>+X/+Z 角</b>，从方块在 -X/-Z 方向，与模型坐标一致。
 */
public final class CatapultBlock extends Block {

	public enum Part implements StringIdentifiable {
		MAIN("main"),
		EXT("ext");

		private final String name;

		Part(String name) {
			this.name = name;
		}

		@Override
		public String asString() {
			return name;
		}
	}

	public static final EnumProperty<Part> PART = EnumProperty.of("part", Part.class);

	/** 2×2 碰撞箱（只对主方块生效）；Y 取 0..32 覆盖模型的 31.2 高度。 */
	private static final VoxelShape SHAPE = Block.createCuboidShape(-16.0, 0.0, -16.0, 16.0, 32.0, 16.0);
	private static final VoxelShape EMPTY = VoxelShapes.empty();

	/** 2×2 中除主方块外的 3 格（相对主方块的偏移）。 */
	private static final int[][] EXT_OFFSETS = {{-1, 0}, {0, -1}, {-1, -1}};

	/** 整体拆除中：避免"主拆从、从拆主"互相递归。 */
	private static final Set<BlockPos> REMOVING = new HashSet<>();

	public CatapultBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(PART, Part.MAIN));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(PART);
	}

	/** 主方块要求 2×2 剩余的 3 格都能被替换。 */
	@Override
	protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
		if (state.get(PART) == Part.EXT) {
			return true;
		}
		for (int[] o : EXT_OFFSETS) {
			if (!world.getBlockState(pos.add(o[0], 0, o[1])).isReplaceable()) {
				return false;
			}
		}
		return super.canPlaceAt(state, world, pos);
	}

	/** 一次性摆好 2×2（主方块 + 3 从方块）。{@code /cww catapult place} 也走这里。 */
	public static void placeAt(World world, BlockPos pos) {
		world.setBlockState(pos, NbwContent.catapult.getDefaultState().with(PART, Part.MAIN), 3);
		placeExtensions(world, pos, true);
	}

	private static void placeExtensions(World world, BlockPos main, boolean overwrite) {
		for (int[] o : EXT_OFFSETS) {
			BlockPos p = main.add(o[0], 0, o[1]);
			if (overwrite || world.getBlockState(p).isReplaceable()) {
				world.setBlockState(p, NbwContent.catapult.getDefaultState().with(PART, Part.EXT), 3);
			}
		}
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return state.get(PART) == Part.EXT ? EMPTY : SHAPE;
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return state.get(PART) == Part.EXT ? EMPTY : SHAPE;
	}

	@Override
	public BlockRenderType getRenderType(BlockState state) {
		return state.get(PART) == Part.EXT ? BlockRenderType.INVISIBLE : BlockRenderType.MODEL;
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world.isClient() || state.get(PART) != Part.MAIN) {
			return;
		}
		placeExtensions(world, pos, false);
		if (!(placer instanceof ServerPlayerEntity sp)) {
			return;
		}
		var session = RoundManager.machine().session(sp.getUuid());
		if (session != null && session.teamIndex() >= 0) {
			CatapultService.register(pos, session.teamIndex());
		}
	}

	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!world.isClient() && !state.isOf(newState.getBlock()) && REMOVING.add(pos)) {
			try {
				BlockPos main = state.get(PART) == Part.MAIN ? pos : findMain(world, pos);
				if (main != null) {
					removeIfThis(world, main);
					for (int[] o : EXT_OFFSETS) {
						removeIfThis(world, main.add(o[0], 0, o[1]));
					}
					CatapultService.unregister(main);
				}
			} finally {
				REMOVING.remove(pos);
			}
		}
		super.onStateReplaced(state, world, pos, newState, moved);
		CatapultService.unregister(pos);
	}

	private static void removeIfThis(World world, BlockPos p) {
		if (REMOVING.contains(p)) {
			return;
		}
		if (world.getBlockState(p).isOf(NbwContent.catapult)) {
			world.removeBlock(p, false);
		}
	}

	/** 从任一从方块反查主方块：从方块在主方块的 -X/-Z 侧，所以主方块往 +X/+Z 找。 */
	private static BlockPos findMain(World world, BlockPos pos) {
		BlockPos[] candidates = {pos, pos.add(1, 0, 0), pos.add(0, 0, 1), pos.add(1, 0, 1)};
		for (BlockPos c : candidates) {
			BlockState s = world.getBlockState(c);
			if (s.isOf(NbwContent.catapult) && s.get(PART) == Part.MAIN) {
				return c;
			}
		}
		return null;
	}
}
