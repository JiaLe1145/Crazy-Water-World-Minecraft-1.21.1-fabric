package dev.nbw.content;

import dev.nbw.NBW;
import dev.nbw.raft.RaftCoreService;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;

/**
 * 筏心方块实体（M2-4）。
 *
 * <p><b>这是全工程第二个允许 import Sable 的地方，属于刻意记录的例外。</b>
 * 原因不是偷懒：Sable 靠 {@code blockEntity instanceof BlockEntitySubLevelActor} 来决定
 * 要不要把这个 BE 收进 plot 的 actor 表（{@code LevelPlot.java:410-416}），
 * 所以<b>必须由 BE 自己实现那个接口</b>，中间加一层适配器根本不会被注册。
 * 例外只到"实现接口"为止：接口方法里要调的 Sable 能力，仍然走 {@code sable/} 包。
 *
 * <p>为什么要实现它：{@code ServerLevelPlot.tick()} 只跑光照与 contraption 清理，
 * <b>不 tick 方块实体</b>。sub-level 上的 BE 想有每 tick 行为，唯一入口就是
 * {@code sable$tick}（{@code ServerSubLevel.java:299}）与 {@code sable$physicsTick}
 * （{@code SubLevelPhysicsSystem.java:226}）。用原版 {@code getTicker} 是拿不到的。
 */
public class RaftCoreBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {

	/** 归属队伍。-1 = 还没被分配（比如运维手动摆了一个筏心方块）。 */
	private int teamIndex = -1;

	/** 已经把自己登记进 RaftCoreService 了没有。第一次 sable$tick 时登记。 */
	private boolean registered;

	public RaftCoreBlockEntity(BlockPos pos, net.minecraft.block.BlockState state) {
		super(NbwContent.raftCoreEntity, pos, state);
	}

	public int teamIndex() {
		return teamIndex;
	}

	public void setTeam(int teamIndex) {
		this.teamIndex = teamIndex;
		markDirty();
	}

	// ---------------------------------------------------------------- Sable actor 通道

	@Override
	public void sable$tick(ServerSubLevel subLevel) {
		if (world == null) {
			return;
		}
		if (!registered) {
			registered = RaftCoreService.registerCore(world.getServer(), getPos(), teamIndex);
			if (registered) {
				// 这条日志是"自定义 BE 在 sub-level plot 里确实被 Sable tick 到了"的唯一直接证据，
				// 别删。ServerLevelPlot.tick() 只跑光照与 contraption 清理，不 tick BE，
				// 走 actor 通道是唯一的每 tick 入口。
				NBW.LOGGER.info("[content] 筏心登记（BE 已被 Sable tick）plot 局部 pos={} team={} sub-level={}",
						getPos(), teamIndex, subLevel.getUniqueId());
			}
		}
		// 方块状态要跟服务端的权威状态一致：重启后 BE 里的 teamIndex 在，
		// 但 wreckage 属性得从筏心服务的相位回写，否则会出现"倒计时在走、方块看着还是好的"
		// 方块状态由服务端权威相位回写；teamIndex 由 BE 自己带着，交互路径不查坐标表
		RaftCoreService.syncState(world, getPos(), teamIndex);
	}

	@Override
	public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
		// 筏心本身不施力。留空是刻意的：一旦这里开始施力，就说明有人把动力逻辑写进了内容层，
		// 那属于 sable/ 与 raft/，不该由一个方块实体承担。
	}

	// ---------------------------------------------------------------- NBT

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.writeNbt(nbt, registries);
		nbt.putInt("nbw_team", teamIndex);
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.readNbt(nbt, registries);
		teamIndex = nbt.contains("nbw_team") ? nbt.getInt("nbw_team") : -1;
		registered = false;   // 换世界/重启后要重新登记
	}
}
