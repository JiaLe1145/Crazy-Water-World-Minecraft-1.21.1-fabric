package dev.nbw.combat;

import dev.nbw.content.NbwContent;
import dev.nbw.content.NbwEntities;
import dev.nbw.content.SpearItem;
import dev.nbw.round.RoundManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 投出去的矛（2026-11-25 新增，用户口径："像三叉戟一样投出，命中 5 点伤害"）。
 *
 * <p>基于 {@link PersistentProjectileEntity}（箭/三叉戟的基类）而不是自己写飞行逻辑：
 * 落地判定、插进方块、拾取、区块存档、跟踪同步都是原版现成的；这里只覆盖三件事：
 * <ul>
 *   <li><b>伤害固定 5 HP</b>：原版会把伤害乘以飞行速度（{@code ceil(speed × damage)}），
 *       所以必须覆盖 {@link #onEntityHit}，否则同一把矛"远投"和"近投"伤害不一样；</li>
 *   <li><b>队友穿透</b>：{@link #canHit} 里排掉同队玩家（服务端的 ALLOW_DAMAGE 只能挡血量，
 *       挡不住"矛扎在队友身上停住"）；</li>
 *   <li><b>入水变物品</b>：整张图都是海，矛扔进水里不该沉底消失。碰到水 5 tick 后直接
 *       换成掉落的物品实体——原版物品实体会浮在水面，玩家游过去就能捡。</li>
 * </ul>
 *
 * <p><b>不消耗/不返还</b>：投掷时由 {@link SpearItem} 扣 1 把，捡回来的就是同一把；
 * 没有忠诚附魔，也不会自动飞回。
 */
public class SpearProjectileEntity extends PersistentProjectileEntity {

	/** 入水多久后改成掉落物（tick）：0.25s，够它扎进水面而不是刚擦到就散架。 */
	private static final int WATER_REST_TICKS = 5;

	/** 兜底寿命（tick）：原版的 1200 tick 只在"插进方块"时计数，浮在水里/空中不计数。 */
	private static final int MAX_AGE_TICKS = 20 * 120;

	/** 入水计时。 */
	private int waterTicks;

	public SpearProjectileEntity(EntityType<? extends SpearProjectileEntity> type, World world) {
		super(type, world);
		this.pickupType = PickupPermission.ALLOWED;   // 谁都能捡（和箭一样），不绑定投掷者
	}

	/** 按投掷者的朝向生成一支矛；位置/速度照三叉戟（眼睛高度 + 2.5 格/tick）。 */
	public static SpearProjectileEntity throwFrom(ServerPlayerEntity shooter, ItemStack stack) {
		SpearProjectileEntity spear = new SpearProjectileEntity(NbwEntities.STONE_SPEAR, shooter.getServerWorld());
		spear.setOwner(shooter);
		spear.setPosition(shooter.getX(), shooter.getEyeY() - 0.15, shooter.getZ());
		spear.setVelocity(shooter, shooter.getPitch(), shooter.getYaw(), 0.0f, SpearItem.THROW_SPEED, 1.0f);
		spear.setDamage(SpearItem.THROW_DAMAGE);   // 供第三方读取；真实伤害在 onEntityHit 固定
		spear.setStack(stack.copyWithCount(1));    // 落地/入水后掉出来的必须是同一把矛
		spear.playSound(SoundEvents.ITEM_TRIDENT_THROW.value(), 0.8f, 1.2f);
		return spear;
	}

	@Override
	protected ItemStack getDefaultItemStack() {
		return NbwContent.stoneSpear == null ? ItemStack.EMPTY : new ItemStack(NbwContent.stoneSpear);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.getWorld().isClient()) {
			return;
		}
		if (this.age > MAX_AGE_TICKS) {
			this.dropStack(this.getItemStack().copy());   // 别让矛永久留在世界里
			this.discard();
			return;
		}
		if (this.inGround) {
			return;   // 插在方块上：交给原版的 60s 寿命与拾取
		}
		if (!this.isTouchingWater()) {
			return;
		}
		if (++this.waterTicks < WATER_REST_TICKS) {
			return;
		}
		// 整图都是海：扔进水里就"浮"着等人捞，而不是沉到海床再被寿命清掉。
		// 物品实体（ItemEntity）在原版里本来就浮在水面，所以这里直接换形态。
		this.dropStack(this.getItemStack().copy());
		this.discard();
	}

	@Override
	protected void onEntityHit(EntityHitResult result) {
		Entity target = result.getEntity();
		Entity owner = this.getOwner();
		if (owner instanceof ServerPlayerEntity shooter && target instanceof ServerPlayerEntity victim
				&& RoundManager.machine().sameTeam(shooter.getUuid(), victim.getUuid())) {
			return;   // 队友不吃矛（canHit 已拦；这里再兜一次，防其它路径直接调用）
		}
		DamageSource source = this.getDamageSources().thrown(this, owner);
		boolean applied = target.damage(source, (float) SpearItem.THROW_DAMAGE);
		if (applied) {
			this.playSound(this.getHitSound(), 1.0f, 1.2f);
			if (owner instanceof ServerPlayerEntity shooter && target instanceof ServerPlayerEntity victim) {
				CombatStats.onDamage(shooter, victim, SpearItem.THROW_DAMAGE);   // 战绩（M5-3）
			}
			if (target instanceof LivingEntity living) {
				this.knockback(living, source);   // 轻微击退，和箭一致
			}
		}
		// 不做穿透：扎中之后大幅减速，落下去/掉进水里由 tick() 处理
		this.setVelocity(this.getVelocity().multiply(0.15));
		this.setCritical(false);
	}

	/** 同队玩家直接穿过去（不在服务端改血量：那只能挡住伤害，挡不住"矛停住"）。 */
	@Override
	protected boolean canHit(Entity entity) {
		if (!super.canHit(entity)) {
			return false;
		}
		if (!this.getWorld().isClient()
				&& entity instanceof ServerPlayerEntity victim
				&& this.getOwner() instanceof ServerPlayerEntity shooter) {
			return !RoundManager.machine().sameTeam(shooter.getUuid(), victim.getUuid());
		}
		return true;
	}

	@Override
	protected float getDragInWater() {
		return 0.6f;   // 与箭一致：入水几 tick 就会被换算成掉落物，不需要浮力
	}

	/** 给探针/调试读：这矛当前的固定伤害。 */
	public double hitDamage() {
		return SpearItem.THROW_DAMAGE;
	}

	/** 给探针/调试读：是否已经插住（含入水前）。 */
	public boolean stuck() {
		return this.inGround;
	}

	/** 给探针/调试读：当前速度（格/tick），用于确认"确实飞出去了"。 */
	public Vec3d velocity() {
		return this.getVelocity();
	}
}
