package dev.nbw.content;

import dev.nbw.raft.RaftCoreService;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 筏心方块（M2-4，01 §4.3.2）。
 *
 * <p>两态：{@code WRECKAGE=false} 完好，{@code true} 残骸。状态放在<b>方块属性</b>上而不是只放 BE 里，
 * 因为属性会进方块状态网络包与渲染——玩家要看得见自己家的筏心是不是已经被打掉了。
 *
 * <p><b>方块自己不做任何判定，也不持有相位。</b>唯一真相在
 * {@link RaftCoreService}，属性由它的 {@code syncState} 每 tick 回写。
 * 这样重启、跨世界、运维手改都不会出现"倒计时在走、方块看着还是好的"。
 *
 * <p><b>破坏入口不在这里。</b>1.21.1 的 {@code onBroken} 有两个归属不明的重载
 * （{@code (DamageSource, World, BlockPos)} 与 {@code (WorldAccess, BlockPos, BlockState)}），
 * 而"武器打掉筏心"本来要等 M4 的武器表落地。破坏只走一个入口：
 * {@link RaftCoreService#markDestroyed}。与其猜一个签名把破坏逻辑钉死在错的钩子上，
 * 不如等真正的伤害来源出现再挂——那时只需要在服务里多调一次，方块侧不用动。
 */
public class RaftCoreBlock extends Block implements BlockEntityProvider {

	public static final BooleanProperty WRECKAGE = BooleanProperty.of("wreckage");

	public RaftCoreBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(WRECKAGE, false));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(WRECKAGE);
	}

	@Override
	public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new RaftCoreBlockEntity(pos, state);
	}

	/**
	 * 残骸不需要工具、不需要配方，只要有人肯站在那儿按住 8 秒——
	 * 这是 A2-v2 的设计意图：补救的代价是<b>时间与暴露</b>，不是物资。
	 *
	 * <p>1.21.1 的 Yarn 把右键拆成两个钩子：{@code onUse(state, world, pos, player, hitResult)}
	 * 只接<b>空手/无物品</b>右键，拿着东西右键走 {@code onUseWithItem(stack, ...)}。
	 * 带物品那一半<b>故意留到这里没写</b>：它和客户端长按周期包是同一个问题的两面
	 * （玩家真正在修的时候手里往往拿着工具或建材），分开写会各测一半、两边都不可信。
	 * 接客户端包时一起补，一次验完。
	 *
	 * <p>一次右键 = 一次 keepalive（{@code KEEPALIVE_TICKS} 个 tick 的有效窗）。
	 * 真正的"连续按住"要等客户端模组周期发包，服务端这套判定已经就位，接上包就行。
	 */
	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
			BlockHitResult hit) {
		if (world.isClient() || state.get(WRECKAGE) != Boolean.TRUE) {
			// 完好时不给任何反馈：筏心还好好的，没什么可修
			return ActionResult.PASS;
		}
		if (!(player instanceof ServerPlayerEntity sp)) {
			return ActionResult.PASS;
		}
		// 归属从"玩家实际点到的那个 BE"读，不按坐标查表。
		// 原因：BE 在自己的 tick 里看到的 getPos() 是 plot 局部坐标（实测 x=20481032 这种量级），
		// 而交互回调给的 pos 属于哪个坐标空间没有保证——两套空间一旦错位，
		// 表现是"玩家怎么点都没反应"，而且日志里什么都不会报。
		if (!(world.getBlockEntity(pos) instanceof RaftCoreBlockEntity be) || be.teamIndex() < 0) {
			return ActionResult.PASS;
		}
		RaftCoreService.keepalive(sp.getUuid(), be.teamIndex());
		return ActionResult.SUCCESS;
	}
}

