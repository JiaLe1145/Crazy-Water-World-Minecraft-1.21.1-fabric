package dev.nbw.content;

import dev.nbw.round.BallistaService;
import dev.nbw.round.RoundManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

import java.util.HashSet;
import java.util.Set;

/**
 * 弩炮方块（09 §2.2 设施武器）：放上筏后由 {@link BallistaService} 每 tick 驱动，
 * 自动打 32 格内最近的敌方玩家。
 *
 * <p><b>真 2×2 多方块</b>：模型按现实器械建模、视觉占 2×2 格，占位也做成 2×2 ——
 * 主方块（{@link Part#MAIN}）+ 3 个从方块（{@link Part#EXT}）。从方块不可见、无碰撞、
 * <b>不可被替换</b>，于是那 3 格真正被占住：别人不能再往缝里塞方块。
 * 拆任意一格会连带拆掉整组。
 *
 * <p>主方块位于 2×2 的 <b>+X/+Z 角</b>，从方块在 -X/-Z 方向 —— 与模型坐标一致
 * （elements 以方块原点为中心铺到 -16..16）。
 *
 * <p>归属取<b>放置者所在队</b>：{@code BuildService} 已保证"只有本队能改本队筏"，
 * 所以放置者队伍就是筏的队。
 */
public final class BallistaBlock extends Block {

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

	/**
	 * 2×2 碰撞箱（只对主方块生效）。模型 X/Z 以方块原点为中心铺开（-16..16），
	 * 碰撞与描边用同一范围；Y 取 0..32 覆盖模型的 21.4 高度。
	 */
	private static final VoxelShape SHAPE = Block.createCuboidShape(-16.0, 0.0, -16.0, 16.0, 32.0, 16.0);
	private static final VoxelShape EMPTY = VoxelShapes.empty();

	/** 2×2 中除主方块外的 3 格（相对主方块的偏移）。 */
	private static final int[][] EXT_OFFSETS = {{-1, 0}, {0, -1}, {-1, -1}};

	/** 整体拆除中：避免"主拆从、从拆主"互相递归。 */
	private static final Set<BlockPos> REMOVING = new HashSet<>();

	public BallistaBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(PART, Part.MAIN));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(PART);
	}

	/** 主方块要求 2×2 剩余的 3 格都能被替换（空气/水/草…）。 */
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

	/** 一次性摆好 2×2（主方块 + 3 从方块）。{@code /cww ballista place} 也走这里。 */
	public static void placeAt(World world, BlockPos pos) {
		world.setBlockState(pos, NbwContent.ballista.getDefaultState().with(PART, Part.MAIN), 3);
		placeExtensions(world, pos, true);
	}

	private static void placeExtensions(World world, BlockPos main, boolean overwrite) {
		for (int[] o : EXT_OFFSETS) {
			BlockPos p = main.add(o[0], 0, o[1]);
			if (overwrite || world.getBlockState(p).isReplaceable()) {
				world.setBlockState(p, NbwContent.ballista.getDefaultState().with(PART, Part.EXT), 3);
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
			BallistaService.register(pos, session.teamIndex());
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
					BallistaService.unregister(main);
				}
			} finally {
				REMOVING.remove(pos);
			}
		}
		super.onStateReplaced(state, world, pos, newState, moved);
		BallistaService.unregister(pos);
	}

	private static void removeIfThis(World world, BlockPos p) {
		if (REMOVING.contains(p)) {
			return;
		}
		if (world.getBlockState(p).isOf(NbwContent.ballista)) {
			world.removeBlock(p, false);
		}
	}

	/** 从任一从方块反查主方块：从方块在主方块的 -X/-Z 侧，所以主方块往 +X/+Z 找。 */
	private static BlockPos findMain(World world, BlockPos pos) {
		BlockPos[] candidates = {pos, pos.add(1, 0, 0), pos.add(0, 0, 1), pos.add(1, 0, 1)};
		for (BlockPos c : candidates) {
			BlockState s = world.getBlockState(c);
			if (s.isOf(NbwContent.ballista) && s.get(PART) == Part.MAIN) {
				return c;
			}
		}
		return null;
	}

	/**
	 * 装填弹药（09 §2.2）：主手拿 {@code nbw:ballista_bolt} 对炮位右键，一次装 1 发。
	 *
	 * <p>弹药记在<b>队</b>上而不是方块上：一门炮的弹药由全队共享，
	 * 造第二门炮不会把第一门的存量分走，拆炮也不会丢弹药。
	 */
	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) {
			return ActionResult.SUCCESS;
		}
		var session = RoundManager.machine().session(sp.getUuid());
		if (session == null || session.teamIndex() < 0) {
			return ActionResult.SUCCESS;
		}
		ItemStack held = sp.getMainHandStack();
		if (!held.isOf(NbwContent.ballistaBolt)) {
			sp.sendMessage(Text.literal("§7[cww] 手持弩炮弹右键可装填（本队现有 "
					+ BallistaService.ammoCount(session.teamIndex()) + " 发）"), true);
			return ActionResult.SUCCESS;
		}
		BallistaService.addAmmo(session.teamIndex(), 1);
		held.decrement(1);
		sp.sendMessage(Text.literal("§a[cww] 装填 1 发，本队弹药 "
				+ BallistaService.ammoCount(session.teamIndex()) + " 发"), true);
		return ActionResult.SUCCESS;
	}
}
