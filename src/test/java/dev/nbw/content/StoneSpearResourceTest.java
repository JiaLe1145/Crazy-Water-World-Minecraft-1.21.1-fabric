package dev.nbw.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 石矛（2026-11-25）的资源/数值守卫。
 *
 * <p>这个物品的贴图与配方是<b>从 1.21.11 原版石矛直接抄的</b>（用户要求"直接采用"），
 * 不是自己画/自己配的。所以这里把"抄来的东西"逐字钉住：
 * <ul>
 *   <li>配方必须是原版的 3×3 排布（{@code "  X" / " # " / "#  "}）、木棍 + 石质工具材料、
 *       产出 1 把 {@code nbw:stone_spear}；</li>
 *   <li>贴图必须是 1.21.11 里那张 16×16 的 {@code stone_spear.png}（分辨率变了就说明被别的东西覆盖了）；</li>
 *   <li>数值按用户口径：左键戳击 3 HP、右键投掷 5 HP。</li>
 * </ul>
 * 任何"顺手改一下"都会让这里变红，逼着改动者先想清楚是不是在偏离原版。
 */
class StoneSpearResourceTest {

	private static JsonObject json(String path) throws IOException {
		try (InputStream in = StoneSpearResourceTest.class.getResourceAsStream(path)) {
			assertNotNull(in, path + " 不在 classpath 上");
			return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	/** 原版 1.21.11 石矛配方：右上材料 + 两格木棍斜垫。 */
	@Test
	void recipeIsTheVanillaStoneSpearRecipe() throws IOException {
		JsonObject recipe = json("/data/nbw/recipe/stone_spear.json");
		assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
		assertEquals("equipment", recipe.get("category").getAsString());

		JsonArray pattern = recipe.getAsJsonArray("pattern");
		assertEquals(3, pattern.size(), "原版石矛是 3×3 排布");
		assertEquals("  X", pattern.get(0).getAsString());
		assertEquals(" # ", pattern.get(1).getAsString());
		assertEquals("#  ", pattern.get(2).getAsString());

		JsonObject key = recipe.getAsJsonObject("key");
		assertEquals("minecraft:stick", key.getAsJsonObject("#").get("item").getAsString());
		assertEquals("minecraft:stone_tool_materials", key.getAsJsonObject("X").get("tag").getAsString(),
				"材料必须是原版标签（圆石/黑石/深板岩圆石），不是写死某一种");

		JsonObject result = recipe.getAsJsonObject("result");
		assertEquals("nbw:" + NbwContent.STONE_SPEAR, result.get("id").getAsString());
		assertEquals(1, result.get("count").getAsInt());
	}

	/** 贴图直接来自 1.21.11 的原版石矛精灵图（16×16）。 */
	@Test
	void textureIsTheVanillaSixteenPixelSprite() throws IOException {
		URL url = Objects.requireNonNull(StoneSpearResourceTest.class.getResource("/assets/nbw/textures/item/stone_spear.png"),
				"缺少 stone_spear.png");
		BufferedImage image = ImageIO.read(url);
		assertNotNull(image, "stone_spear.png 不是可读图片");
		assertEquals(16, image.getWidth());
		assertEquals(16, image.getHeight());
		// 至少不能是"一张全透明的空图"（抄漏/被覆盖时的典型症状）
		boolean anyOpaque = false;
		for (int y = 0; y < image.getHeight() && !anyOpaque; y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if ((image.getRGB(x, y) >>> 24) != 0) {
					anyOpaque = true;
					break;
				}
			}
		}
		assertTrue(anyOpaque, "贴图全透明 = 玩家会看到一把空气矛");
	}

	/** 物品模型：和其他武器一致的手持模型，贴图指向我们复制进来的那张。 */
	@Test
	void itemModelPointsAtTheCopiedTexture() throws IOException {
		JsonObject model = json("/assets/nbw/models/item/stone_spear.json");
		assertEquals("minecraft:item/handheld", model.get("parent").getAsString());
		assertEquals("nbw:item/stone_spear",
				model.getAsJsonObject("textures").get("layer0").getAsString());
	}

	/** 左键戳击 3 HP：近战表是服务端权威，属性修饰由它算出来（+2，拳底 1）。 */
	@Test
	void jabIsThreeDamageInTheWeaponTable() throws IOException {
		JsonObject root = json("/data/nbw/weapons.json");
		JsonObject spear = null;
		for (com.google.gson.JsonElement element : root.getAsJsonArray("weapons")) {
			JsonObject entry = element.getAsJsonObject();
			if (NbwContent.STONE_SPEAR.equals(entry.get("id").getAsString())) {
				spear = entry;
				break;
			}
		}
		assertNotNull(spear, "weapons.json 里没有 stone_spear：物品注册了也不会受攻速闸门管");
		assertEquals("melee", spear.get("kind").getAsString());
		assertEquals(3.0, spear.get("damage").getAsDouble(), 1.0e-9, "用户口径：戳击 3 点伤害");
		// 原版石矛攻速 1.33 → 15 tick（0.75s），这里保持同一手感
		assertEquals(15, spear.get("cooldownTicks").getAsInt(), "攻速要贴着原版石矛（1.33）");
	}

	/** 右键投掷 5 HP：常量写死在物品上，实体命中时按它结算。 */
	@Test
	void throwDealsFiveDamageWithOneSecondCooldown() {
		assertEquals(5.0, SpearItem.THROW_DAMAGE, 1.0e-9, "用户口径：投掷命中 5 点伤害");
		assertEquals(20, SpearItem.THROW_COOLDOWN_TICKS, "投掷冷却 1s");
		assertEquals(2.5f, SpearItem.THROW_SPEED, 1.0e-6f, "初速照三叉戟（2.5 格/tick）");
	}

	/** 中文名必须存在，否则玩家看到的是 item.nbw.stone_spear 这串 key。 */
	@Test
	void hasChineseDisplayName() throws IOException {
		JsonObject lang = json("/assets/nbw/lang/zh_cn.json");
		assertEquals("石矛", lang.get("item.nbw.stone_spear").getAsString());
	}
}
