package dev.nbw.content;

import dev.nbw.NBW;
import dev.nbw.arena.BuiltinWeaponsJson;
import dev.nbw.cfg.WeaponConfig;
import dev.nbw.domain.combat.CombatRule.WeaponSpec;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 近战武器注册（09 §2.1，已批准 A17）。
 *
 * <p>注册写法照 {@link NbwContent}：{@code Registry.register(Registries.ITEM, ...)}。
 * 伤害/攻速走 {@code attribute_modifiers} 组件：
 * <ul>
 *   <li><b>伤害</b>：{@code GENERIC_ATTACK_DAMAGE} 的 ADD_VALUE 修饰 = {@code damage - 1}（拳基础伤害 1），
 *       所以砍刀 9HP → 修饰 +8，满冷却一次命中 9 HP。</li>
 *   <li><b>攻速</b>：{@code GENERIC_ATTACK_SPEED} 的 ADD_VALUE 修饰 = {@code 20/cooldownTicks - 4}
 *       （基础攻速 4.0，冷却 = 20/攻速 tick），让客户端冷却条与表格一致。</li>
 * </ul>
 * 这些修饰值<b>只在注册这一刻定死</b>，取的是 jar 内置 {@code data/nbw/weapons.json}；
 * 运行时 {@code world/cww/weapons.json} 覆盖只影响服务端权威的攻速闸门/击退/审计（见 {@code combat/WeaponService}），
 * 不改物品的面板伤害——物品属性必须在注册时是常量，这是 Minecraft 的注册模型，不是我们偷懒。
 *
 * <p><b>2026-11-25 石矛</b>：{@code stone_spear} 这一条不实例化 {@link WeaponItem}，而是
 * {@link SpearItem}（多一个"右键投掷"）；另外额外给一格
 * {@code player.entity_interaction_range +1.5}，让"戳得比剑远"（原版矛 4.5 格）成立。
 */
public final class NbwWeapons {

	/** 石矛的武器表 id：注册时要换成 {@link SpearItem} 并追加攻击距离修饰。 */
	private static final String SPEAR_ID = NbwContent.STONE_SPEAR;

	/** 石矛的攻击距离加成（格）：原版矛最长 4.5 格，玩家基础 3.0 → +1.5。 */
	public static final double SPEAR_REACH_BONUS = 1.5;

	private static final Map<String, WeaponItem> ITEMS = new LinkedHashMap<>();

	private NbwWeapons() {
	}

	public static void register() {
		WeaponConfig.Loaded builtin = WeaponConfig.builtin(BuiltinWeaponsJson.SOURCE);
		if (!builtin.ok()) {
			// 内置表坏了：宁可少注册武器也不能带着错误的属性硬上，日志要让运维一眼看到。
			NBW.LOGGER.error("[weapons] 内置武器表校验失败，本轮不注册任何武器：{}", builtin.errors());
			return;
		}
		for (WeaponSpec spec : builtin.specs()) {
			if (!spec.isMelee()) {
				continue;   // RANGED/TOOL 是 §2.2/§2.3，随 M3/M4 补
			}
			Item.Settings settings = settings(spec);
			WeaponItem item = SPEAR_ID.equals(spec.id())
					? new SpearItem(spec.id(), settings)
					: new WeaponItem(spec.id(), settings);
			Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, spec.id()), item);
			ITEMS.put(spec.id(), item);
			if (item instanceof SpearItem spear) {
				NbwContent.stoneSpear = spear;   // 投掷实体与物品组要用
			}
		}
		NBW.LOGGER.info("[weapons] 注册近战 {} 件：{}", ITEMS.size(), ITEMS.keySet());
	}

	private static Item.Settings settings(WeaponSpec spec) {
		double damageModifier = spec.damage() - 1.0;
		double attackSpeed = 20.0 / spec.cooldownTicks();
		double speedModifier = attackSpeed - 4.0;
		AttributeModifiersComponent.Builder builder = AttributeModifiersComponent.builder()
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE,
						new EntityAttributeModifier(Item.BASE_ATTACK_DAMAGE_MODIFIER_ID, damageModifier,
								EntityAttributeModifier.Operation.ADD_VALUE),
						AttributeModifierSlot.MAINHAND)
				.add(EntityAttributes.GENERIC_ATTACK_SPEED,
						new EntityAttributeModifier(Item.BASE_ATTACK_SPEED_MODIFIER_ID, speedModifier,
								EntityAttributeModifier.Operation.ADD_VALUE),
						AttributeModifierSlot.MAINHAND);
		if (SPEAR_ID.equals(spec.id())) {
			// 矛的"戳得远"：原版 1.21.11 石矛攻击距离 2–4.5 格，玩家自带 3.0 →
			// +1.5 让最远命中距离对得上（"太近打不到"这一条原版没有对应属性，暂不建模）。
			builder.add(EntityAttributes.PLAYER_ENTITY_INTERACTION_RANGE,
					new EntityAttributeModifier(Identifier.of(NBW.MOD_ID, "spear_reach"), SPEAR_REACH_BONUS,
							EntityAttributeModifier.Operation.ADD_VALUE),
					AttributeModifierSlot.MAINHAND);
		}
		return new Item.Settings().attributeModifiers(builder.build()).maxCount(1);
	}

	/** 按武器 id 取已注册的 Item（give 命令用）；没注册返回 null。 */
	public static WeaponItem item(String id) {
		return ITEMS.get(id);
	}

	/** 这是不是我们的自定义武器（{@code instanceof WeaponItem} 的集中判定口）。 */
	public static boolean isWeapon(Item item) {
		return item instanceof WeaponItem;
	}

	/** 已注册的近战武器，给持有上限扫描与列表命令用。 */
	public static Collection<WeaponItem> items() {
		return ITEMS.values();
	}
}
