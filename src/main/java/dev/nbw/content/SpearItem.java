package dev.nbw.content;

import dev.nbw.combat.SpearProjectileEntity;
import dev.nbw.round.RoundManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * 石矛（2026-11-25 新增）：左键戳击 + 右键投掷，两种用法共用同一件物品。
 *
 * <p><b>左键戳击</b>走原版近战结算：伤害 3 HP（{@code weapons.json} 的 {@code stone_spear}，
 * 属性修饰 +2，拳底 1），攻速按原版石矛的 1.33（0.75s → 15 tick）；另外额外 +1.5 格
 * {@code player.entity_interaction_range}，让"戳得比剑远"这条矛的特征成立（原版矛是 4.5 格）。
 *
 * <p><b>右键投掷</b>是三叉戟那套：真正生成一个 {@link SpearProjectileEntity} 飞出去，
 * 命中固定 5 HP；不消耗创造模式物品，普通模式扣 1 把（落地/入水后可以捡回来）。
 *
 * <p>为什么不是 hitscan：{@link RangedWeaponItem} 那套（标枪）是服务端射线判定，看不到飞出去的矛。
 * 用户要的是"像三叉戟一样投出"，所以这里必须走实体——基建复用 2026-11-25 加的
 * {@code NbwEntities}（补给箱那轮建立的实体注册/渲染）。
 *
 * <p>只在 PLAY 生效：和近战、远程武器同一道闸（{@link RoundManager#phase()} 的
 * {@code allowsPvp()}），避免大厅里把矛扔进海里。
 */
public final class SpearItem extends WeaponItem {

	/** 投掷命中伤害（HP）：用户指定 5 点。实际伤害在 {@link SpearProjectileEntity#onEntityHit} 里固定。 */
	public static final double THROW_DAMAGE = 5.0;

	/** 投掷冷却（tick）：1 秒，比矛本身的 0.75s 攻速略长，避免"扔-捡-扔"刷伤害。 */
	public static final int THROW_COOLDOWN_TICKS = 20;

	/** 投掷初速（格/tick）：与三叉戟一致（2.5）。 */
	public static final float THROW_SPEED = 2.5f;

	public SpearItem(String weaponId, Settings settings) {
		super(weaponId, settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient()) {
			return TypedActionResult.consume(stack);   // 客户端只抬手；矛由服务端生成并同步
		}
		if (!(user instanceof ServerPlayerEntity sp)) {
			return TypedActionResult.pass(stack);
		}
		if (sp.isSpectator() || !RoundManager.phase().allowsPvp()) {
			return TypedActionResult.pass(stack);
		}
		if (sp.getItemCooldownManager().isCoolingDown(this)) {
			return TypedActionResult.pass(stack);   // 冷却中：不生成、不消耗
		}
		SpearProjectileEntity spear = SpearProjectileEntity.throwFrom(sp, stack);
		if (!sp.getServerWorld().spawnEntity(spear)) {
			sp.sendMessage(net.minecraft.text.Text.literal("§7[cww] 投矛失败（区块未加载）"), true);
			return TypedActionResult.pass(stack);
		}
		stack.decrementUnlessCreative(1, sp);
		sp.getItemCooldownManager().set(this, THROW_COOLDOWN_TICKS);
		return TypedActionResult.consume(stack);
	}
}
