package dev.nbw.content;

import net.minecraft.item.Item;

/**
 * 自定义武器 Item（09 §4 ADR-001：直接注册自定义 {@link Item}，不再走"原版载体 + CMD overrides"）。
 *
 * <p>本类唯一比原版 {@link Item} 多出来的东西是 {@link #weaponId()}：它把注册表 id 与
 * {@code weapons.json} 里的 id 对齐，让 {@code combat/WeaponService} 与 {@code /cww weapon give}
 * 能用 {@code instanceof WeaponItem} 一眼认出"这是我们的近战"，而不必拿注册表 id 反查字符串。
 *
 * <p>伤害与攻速走 attribute modifier（{@code NbwWeapons} 注册时从内置表算好）；
 * 外观与渲染失败绝不影响判定——逻辑权威在服务端。
 */
public class WeaponItem extends Item {

	private final String weaponId;

	public WeaponItem(String weaponId, Settings settings) {
		super(settings);
		this.weaponId = weaponId;
	}

	/** 对齐 {@code weapons.json} 的武器 id（如 {@code cutlass}）。 */
	public String weaponId() {
		return weaponId;
	}
}
