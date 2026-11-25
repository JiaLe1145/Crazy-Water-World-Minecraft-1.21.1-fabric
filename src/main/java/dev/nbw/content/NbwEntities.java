package dev.nbw.content;

import dev.nbw.NBW;
import dev.nbw.combat.SpearProjectileEntity;
import dev.nbw.loot.FloatingCrate;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 实体类型注册（2026-11-25 新增）。
 *
 * <p>当前两个：
 * <ul>
 *   <li>{@link #FLOATING_CRATE}：海面补给箱。之前补给箱是 {@code minecraft:barrel} 方块，
 *       钉在水面那一格里；方块没有起伏、没有漂移，只能演出"插在水里的桶"。换成实体后才有
 *       浮力线、起伏、洋流漂移与下沉。</li>
 *   <li>{@link #STONE_SPEAR}：投出去的石矛（三叉戟那套真正的投射物）。</li>
 * </ul>
 *
 * <p>登记写法与方块/物品一致（{@code Registry.register}），{@code build} 里的字符串必须与
 * 注册 id 完全相同——1.21.1 的 {@code EntityType.Builder#build(String)} 把它写进 datafixer 的
 * choice type，两处不一致会在读存档时报 "Unknown entity type"。
 */
public final class NbwEntities {

	public static final String FLOATING_CRATE_ID = "floating_crate";
	public static final String STONE_SPEAR_ID = "stone_spear";

	/**
	 * 海面补给箱。
	 *
	 * <p>碰撞箱 0.8×0.8（略小于一格，水面上看起来像漂着一只箱子）；追踪距离 8 个区块
	 * （128 格）——补给箱要能"远处看见有东西在漂"，默认值太短。
	 *
	 * <p>{@code trackingTickInterval(1)}：这个实体每 tick 都在动（洋流 + 起伏）。默认的
	 * 位置同步节流会让客户端隔几 tick 才收到一次坐标，近距离看是"一跳一跳"的；补给箱同屏
	 * 最多 24 个，每 tick 全同步的带宽可以忽略，所以这里直接钉成 1。
	 */
	public static final EntityType<FloatingCrate> FLOATING_CRATE = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(NBW.MOD_ID, FLOATING_CRATE_ID),
			EntityType.Builder.<FloatingCrate>create(FloatingCrate::new, SpawnGroup.MISC)
					.dimensions(0.8f, 0.8f)
					.maxTrackingRange(8)
					.trackingTickInterval(1)
					.build(NBW.MOD_ID + ":" + FLOATING_CRATE_ID));

	/**
	 * 投出去的石矛。
	 *
	 * <p>0.5×0.5 的细长碰撞箱（比箭的 0.5 略窄，但不再区分轴：Minecraft 的实体碰撞箱是方柱）；
	 * 追踪 8 个区块、每 tick 同步——投射物一 tick 飞 2.5 格，节流一次就会看成一跳一跳。
	 */
	public static final EntityType<SpearProjectileEntity> STONE_SPEAR = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(NBW.MOD_ID, STONE_SPEAR_ID),
			EntityType.Builder.<SpearProjectileEntity>create(SpearProjectileEntity::new, SpawnGroup.MISC)
					.dimensions(0.5f, 0.5f)
					.maxTrackingRange(8)
					.trackingTickInterval(1)
					.build(NBW.MOD_ID + ":" + STONE_SPEAR_ID));

	private NbwEntities() {
	}

	/** 触发类初始化（{@code NBW.onInitialize} 里调用）；静态字段自己完成注册。 */
	public static void register() {
		NBW.LOGGER.info("[content] 实体类型已注册：{}, {}（{} / {}）", FLOATING_CRATE_ID, STONE_SPEAR_ID,
				Registries.ENTITY_TYPE.getId(FLOATING_CRATE), Registries.ENTITY_TYPE.getId(STONE_SPEAR));
	}
}
