package dev.nbw.content;

import dev.nbw.NBW;
import dev.nbw.domain.raft.OarModel;
import dev.nbw.raft.OarService;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;

/**
 * 桨台方块实体（M2-5）。
 *
 * <p>它是<b>桨位身份的唯一真相</b>：队号、桨位号、推力分量、以及挂桨时用的 plot 局部偏移。
 * 三件事都进 NBT，所以 {@link OarService} 那张运行时索引丢了也能靠 BE 回填
 * （重启后不需要重新挂桨，也不会出现"方块在、表空、怎么按都没动力"）。
 *
 * <p>为什么桨要把身份写在 BE 上，而帆只用 {@code Sailing} 里的一张表：
 * 帆是被动受风，永远由服务端自己按表算；桨的输入来自"玩家点到哪台桨"，
 * 而 sub-level 里交互回调给的坐标与 BE 自己的 {@code getPos()} 不保证同一空间
 * （实测 {@code x=20481032} 这种量级）。拿坐标去查表一旦错位，表现是"按了没反应"
 * 且日志一句不报。身份挂在被点到的对象上，就没有这个坑。
 *
 * <p>与 {@link RaftCoreBlockEntity} 一样实现 {@code BlockEntitySubLevelActor}，
 * 但用途不同：这里<b>只</b>用 {@code sable$tick} 做一次自我登记，之后不再每 tick 做事
 * （推力由 {@link OarService} 统一施加，不散进内容层）。留 {@code sable$physicsTick}
 * 空实现是接口要求，不是待办。
 */
public class OarBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {

	/** 归属队伍。-1 = 还没被分配（运维手工摆的桨台，划不动）。 */
	private int teamIndex = -1;

	/** 队内桨位号，0..{@code OarService.MAX_OARS_PER_TEAM-1}。-1 = 未分配。 */
	private int slot = -1;

	/** 推力分量（轴对齐单位矢量，恰好一个非零）。全 0 = 未 rig。 */
	private double dirX;
	private double dirZ;

	/** 挂桨时写入的 plot 局部偏移；拆桨要靠它，重启后也只有这里还记得。 */
	private int offX;
	private int offY;
	private int offZ;

	/** 已经把自己回填进 OarService 了没有。第一次 sable$tick 时回填。 */
	private boolean registered;

	public OarBlockEntity(BlockPos pos, BlockState state) {
		super(NbwContent.oarEntity, pos, state);
	}

	public int teamIndex() {
		return teamIndex;
	}

	public int slot() {
		return slot;
	}

	/** 只有"队号 + 桨位 + 合法方向"三者齐备，这台桨才允许出力。 */
	public boolean isRigged() {
		// 方向口径不在这层重写：OarModel.isAxisAligned 是唯一真相
		return teamIndex >= 0 && slot >= 0 && OarModel.isAxisAligned(dirX, dirZ);
	}

	/**
	 * 由 rig 流程写入身份。方向在这里定死并落盘，之后任何路径都只读它，
	 * 客户端与探针都只能选"哪台桨"，不能自选方向。
	 */
	public void setRig(int teamIndex, int slot, double dirX, double dirZ, int offX, int offY, int offZ) {
		this.teamIndex = teamIndex;
		this.slot = slot;
		this.dirX = dirX;
		this.dirZ = dirZ;
		this.offX = offX;
		this.offY = offY;
		this.offZ = offZ;
		registered = false;   // 身份变了要重新回填一次索引
		markDirty();
	}

	/** 拆桨时清空身份；不清会残留上一队的归属，下一队白捡动力。 */
	public void clearRig() {
		teamIndex = -1;
		slot = -1;
		dirX = 0.0;
		dirZ = 0.0;
		registered = false;
		markDirty();
	}

	// ---------------------------------------------------------------- Sable actor 通道

	@Override
	public void sable$tick(ServerSubLevel subLevel) {
		if (registered || world == null) {
			return;
		}
		if (!isRigged()) {
			registered = true;   // 手工摆的桨台没有归属，不必每 tick 再试
			return;
		}
		registered = OarService.registerOar(teamIndex, slot, dirX, dirZ, offX, offY, offZ,
				String.valueOf(subLevel.getUniqueId()));
		if (registered) {
			// 这条日志是"桨台 BE 确实被 Sable tick 到、且索引已回填"的唯一直接证据；
			// 重启后看到它就说明不用重新挂桨。别删。
			NBW.LOGGER.info("[oar] 桨位回填 team={} slot={} dir=({}, {}) plot 偏移=({},{},{})",
					teamIndex, slot, dirX, dirZ, offX, offY, offZ);
		}
	}

	@Override
	public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
		// 桨台不施力。这里一旦开始施力，就说明有人把动力逻辑写进了内容层，
		// 那属于 raft/ 与 domain/，不该由一个方块实体承担。
	}

	// ---------------------------------------------------------------- NBT

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.writeNbt(nbt, registries);
		nbt.putInt("nbw_team", teamIndex);
		nbt.putInt("nbw_slot", slot);
		nbt.putDouble("nbw_dir_x", dirX);
		nbt.putDouble("nbw_dir_z", dirZ);
		nbt.putInt("nbw_off_x", offX);
		nbt.putInt("nbw_off_y", offY);
		nbt.putInt("nbw_off_z", offZ);
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.readNbt(nbt, registries);
		teamIndex = nbt.contains("nbw_team") ? nbt.getInt("nbw_team") : -1;
		slot = nbt.contains("nbw_slot") ? nbt.getInt("nbw_slot") : -1;
		dirX = nbt.contains("nbw_dir_x") ? nbt.getDouble("nbw_dir_x") : 0.0;
		dirZ = nbt.contains("nbw_dir_z") ? nbt.getDouble("nbw_dir_z") : 0.0;
		offX = nbt.contains("nbw_off_x") ? nbt.getInt("nbw_off_x") : 0;
		offY = nbt.contains("nbw_off_y") ? nbt.getInt("nbw_off_y") : 0;
		offZ = nbt.contains("nbw_off_z") ? nbt.getInt("nbw_off_z") : 0;
		registered = false;   // 换世界/重启后重新回填索引
	}
}
