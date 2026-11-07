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
 * 首发 5 件近战武器的注册（09 §2.1，已批准 A17；本轮只落近战切片）。
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
 */
public final class NbwWeapons {

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
			WeaponItem item = new WeaponItem(spec.id(), settings(spec));
			Registry.register(Registries.ITEM, Identifier.of(NBW.MOD_ID, spec.id()), item);
			ITEMS.put(spec.id(), item);
		}
		NBW.LOGGER.info("[weapons] 注册近战 {} 件：{}", ITEMS.size(), ITEMS.keySet());
	}

	private static Item.Settings settings(WeaponSpec spec) {
		double damageModifier = spec.damage() - 1.0;
		double attackSpeed = 20.0 / spec.cooldownTicks();
		double speedModifier = attackSpeed - 4.0;
		AttributeModifiersComponent modifiers = AttributeModifiersComponent.builder()
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE,
						new EntityAttributeModifier(Item.BASE_ATTACK_DAMAGE_MODIFIER_ID, damageModifier,
								EntityAttributeModifier.Operation.ADD_VALUE),
						AttributeModifierSlot.MAINHAND)
				.add(EntityAttributes.GENERIC_ATTACK_SPEED,
						new EntityAttributeModifier(Item.BASE_ATTACK_SPEED_MODIFIER_ID, speedModifier,
								EntityAttributeModifier.Operation.ADD_VALUE),
						AttributeModifierSlot.MAINHAND)
				.build();
		return new Item.Settings().attributeModifiers(modifiers).maxCount(1);
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
