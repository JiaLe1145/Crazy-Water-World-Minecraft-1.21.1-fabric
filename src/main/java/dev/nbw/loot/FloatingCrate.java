package dev.nbw.loot;

import dev.nbw.content.NbwEntities;
import dev.nbw.domain.loot.FlotsamMotion;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 海面补给箱（2026-11-25 起）：从"水面上的一格 barrel 方块"换成**有水面物理的实体**。
 *
 * <p><b>为什么要换</b>：方块版的箱子钉在 {@code sea.waterLevel} 那一格上，既不随浪起伏，
 * 也不会被海流带走，看起来像"插在水里的桶"。改成实体后：贴着水面起伏、随全图共享的洋流
 * 漂移（见 {@link FlotsamMotion}）、压到岸/筏上会被挡住、到期原地沉没。
 *
 * <p><b>保真点</b>：
 * <ul>
 *   <li>外观仍是 barrel 的方块模型（{@code FloatingCrateRenderer} 用 {@code renderBlockAsEntity} 画），
 *       玩家看到的就是"漂着的补给箱"，不是新贴图；</li>
 *   <li>右键开箱走原版 3×9 箱子界面（{@link GenericContainerScreenHandler#createGeneric9x3}），
 *       内容与以前一样按 tier 抽稀有度/设施池；</li>
 *   <li>水平位移**不走** {@code setVelocity}/流体推力，而是直接按洋流写位置——否则 Sable 的筏、
 *       原版水流、爆炸击退都会让箱子偏离"所有箱子同向"这条产品口径。</li>
 * </ul>
 *
 * <p><b>生命周期</b>：{@link #LIFETIME_TICKS} 后进入 {@link #SINK_TICKS} tick 的下沉动画再
 * {@code discard()}；因为没有重力，不 tick（未加载区块）时它只是"停在那里"，重新加载后按
 * {@code spawnedAt} 继续算寿命。
 */
public class FloatingCrate extends Entity implements NamedScreenHandlerFactory {

	/** 容器格数：原版 3×9，与旧 barrel 一致。 */
	public static final int SLOTS = 27;

	/** 存活 120s（02 §4 standard 口径）：到点沉没，不能一直漂着占同屏上限。 */
	public static final int LIFETIME_TICKS = 120 * 20;

	/** 沉没动画：下沉 1s，给玩家"箱子沉了"的反馈，而不是凭空消失。 */
	public static final int SINK_TICKS = 20;

	/** 沉没动画每 tick 下移（格）。1s × 0.04 ≈ 0.8 格，看起来刚好没入水面。 */
	private static final double SINK_STEP = 0.04;

	/** 判定"脚下是不是水"用的半宽：比碰撞箱略小，避免卡在格子缝里。 */
	private static final double FLOAT_HALF_WIDTH = 0.34;

	/** 起伏相位要同步到客户端（渲染摇晃用），所以走 DataTracker，而不是只写进 NBT。 */
	private static final TrackedData<Float> BOB_PHASE = DataTracker.registerData(FloatingCrate.class,
			TrackedDataHandlerRegistry.FLOAT);

	private final SimpleInventory inventory = new SimpleInventory(SLOTS);

	/** 撒出时刻（世界 tick）：跨存档重启后继续算寿命。 */
	private long spawnedAt;
	/** 水面基准线（世界 y）：箱子围绕它做上下起伏。 */
	private double surfaceY;
	/** 沉没动画剩余 tick。 */
	private int sinkTicks;
	private boolean sinking;

	public FloatingCrate(EntityType<? extends FloatingCrate> type, World world) {
		super(type, world);
	}

	/**
	 * 在世界里撒一个补给箱。
	 *
	 * <p>只负责"造实体 + 定位 + 入世界"；装什么物资由 {@link LootService} 决定。
	 *
	 * @param surfaceY 水面基准线，取 {@code arena.sea.waterLevel + 0.5}（旧 barrel 方块的正中心）
	 * @param phase    起伏相位，{@code [0, 2π)}；同一批箱子不要同相位
	 * @return 撒成功返回实体；区块没加载/位置非法导致入世界失败时返回 {@code null}
	 */
	public static @Nullable FloatingCrate spawn(World world, double x, double surfaceY, double z, long now,
			double phase) {
		FloatingCrate crate = new FloatingCrate(NbwEntities.FLOATING_CRATE, world);
		crate.spawnedAt = now;
		crate.surfaceY = surfaceY;
		crate.setBobPhase(phase);
		// 用 DataTracker 里那份（已取模）相位算起始高度，保证服务端位置与客户端摇晃同一相位
		crate.setPosition(x, surfaceY + FlotsamMotion.bob(now, crate.bobPhase()), z);
		if (!world.spawnEntity(crate)) {
			crate.discard();
			return null;
		}
		return crate;
	}

	/**
	 * 该点能不能浮着（漂移前的"前方是不是水"判定）。
	 *
	 * <p>四角都要是水：只要有一角压到岸、筏面、石墙，就不许漂过去——否则箱子会卡进方块里，
	 * 看起来像穿模。纯几何判定，撒箱和每 tick 漂移共用。
	 */
	public static boolean canFloatAt(World world, double x, double surfaceY, double z) {
		int blockY = MathHelper.floor(surfaceY - 0.5);
		for (int sx = -1; sx <= 1; sx += 2) {
			for (int sz = -1; sz <= 1; sz += 2) {
				BlockPos pos = BlockPos.ofFloored(x + sx * FLOAT_HALF_WIDTH, blockY, z + sz * FLOAT_HALF_WIDTH);
				if (!world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER)) {
					return false;
				}
			}
		}
		return true;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.getWorld().isClient()) {
			return;   // 位置/姿态以服务端为准；客户端只做插值
		}
		long now = this.getWorld().getTime();
		if (this.spawnedAt <= 0L) {
			this.spawnedAt = now;   // 老存档/手搓实体兜底：从这 tick 开始算寿命
		}
		if (!this.sinking && now - this.spawnedAt >= LIFETIME_TICKS) {
			this.sinking = true;
			this.sinkTicks = 0;
		}
		if (this.sinking) {
			tickSinking();
			return;
		}
		double[] drift = FlotsamMotion.velocity(now);
		double nextX = this.getX() + drift[0];
		double nextZ = this.getZ() + drift[1];
		double surfaceLine = this.surfaceY + FlotsamMotion.bob(now, this.bobPhase());
		if (canFloatAt(this.getWorld(), nextX, this.surfaceY, nextZ)) {
			this.setPosition(nextX, surfaceLine, nextZ);
		} else {
			this.setPosition(this.getX(), surfaceLine, this.getZ());   // 顶到岸/筏/墙：原地起伏，不许穿进去
		}
	}

	private void tickSinking() {
		this.sinkTicks++;
		this.setPosition(this.getX(), this.getY() - SINK_STEP, this.getZ());
		if (this.sinkTicks >= SINK_TICKS) {
			this.discard();
		}
	}

	@Override
	public ActionResult interact(PlayerEntity player, Hand hand) {
		if (this.getWorld().isClient()) {
			return ActionResult.SUCCESS;
		}
		if (this.sinking) {
			return ActionResult.FAIL;   // 已经在下沉：不给开，避免开一个马上消失的界面
		}
		player.openHandledScreen(this);
		return ActionResult.SUCCESS;
	}

	@Override
	public boolean isPushable() {
		return false;   // 漂移方向由洋流唯一决定：被玩家/筏/爆炸推开就违反"所有箱子同向"
	}

	@Override
	public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
		// 自己写 canUse：箱子会漂、会沉。原版箱子的"走远了自动关界面"靠 canUse，这里同理——
		// 否则玩家可以开着界面游走，甚至对着已经沉掉的箱子把东西拿走。
		return new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory,
				this.inventory, 3) {
			@Override
			public boolean canUse(PlayerEntity opener) {
				return !FloatingCrate.this.isRemoved()
						&& !FloatingCrate.this.isSinking()
						&& opener.squaredDistanceTo(FloatingCrate.this) <= 64.0;   // 8 格
			}
		};
	}

	@Override
	public Text getDisplayName() {
		return Text.translatable("container.nbw.floating_crate");
	}

	/** 装什么由 {@link LootService} 决定；这里只暴露容器。 */
	public SimpleInventory getInventory() {
		return this.inventory;
	}

	public boolean isSinking() {
		return this.sinking;
	}

	/** 起伏相位（弧度）：客户端渲染摇晃要用，服务端撒箱时写入。 */
	public double bobPhase() {
		return this.dataTracker.get(BOB_PHASE);
	}

	private void setBobPhase(double phase) {
		float wrapped = (float) (phase % (Math.PI * 2.0));
		this.dataTracker.set(BOB_PHASE, wrapped);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		builder.add(BOB_PHASE, 0.0f);
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		this.spawnedAt = nbt.getLong("SpawnedAt");
		this.surfaceY = nbt.contains("SurfaceY") ? nbt.getDouble("SurfaceY") : this.getY();
		this.sinkTicks = nbt.getInt("SinkTicks");
		this.sinking = nbt.getBoolean("Sinking");
		this.setBobPhase(nbt.getDouble("BobPhase"));
		if (nbt.contains("Items", NbtElement.LIST_TYPE)) {
			this.inventory.readNbtList(nbt.getList("Items", NbtElement.COMPOUND_TYPE),
					this.getWorld().getRegistryManager());
		}
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putLong("SpawnedAt", this.spawnedAt);
		nbt.putDouble("SurfaceY", this.surfaceY);
		nbt.putInt("SinkTicks", this.sinkTicks);
		nbt.putBoolean("Sinking", this.sinking);
		nbt.putDouble("BobPhase", this.bobPhase());
		nbt.put("Items", this.inventory.toNbtList(this.getWorld().getRegistryManager()));
	}
}
