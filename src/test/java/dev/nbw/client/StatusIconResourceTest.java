package dev.nbw.client;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the five HUD status sprites that replaced the text-glyph placeholders. */
class StatusIconResourceTest {

	private static final List<String> ICONS = List.of(
			"bleeding", "fracture", "hypothermia", "low_nutrition", "low_hydration");

	@Test
	void everyStatusHasAReadableSixtyFourPixelTransparentSprite() throws Exception {
		for (String name : ICONS) {
			String path = "/assets/nbw/textures/status/" + name + ".png";
			try (InputStream in = StatusIconResourceTest.class.getResourceAsStream(path)) {
				assertNotNull(in, "缺少状态图标 " + path);
				BufferedImage image = ImageIO.read(in);
				assertNotNull(image, path + " 不是可读 PNG");
				// 2026-11-28 用户要求：贴图保持 64×64，只在 UI 里整数倍缩小显示
				assertEquals(64, image.getWidth(), path + " 宽度必须为 64");
				assertEquals(64, image.getHeight(), path + " 高度必须为 64");
				assertTrue(hasTransparentPixel(image), path + " 必须保留透明背景");
				assertTrue(hasOpaquePixel(image), path + " 不能是全透明空图");
			}
		}
	}

	private static boolean hasTransparentPixel(BufferedImage image) {
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if ((image.getRGB(x, y) >>> 24) == 0) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean hasOpaquePixel(BufferedImage image) {
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if ((image.getRGB(x, y) >>> 24) != 0) {
					return true;
				}
			}
		}
		return false;
	}
}
