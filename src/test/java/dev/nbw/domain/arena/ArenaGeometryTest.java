package dev.nbw.domain.arena;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 预热用的区块盒几何。最有价值的一条是**负坐标**：
 * 用截断除法 (-1060)/16 会得到 -66，而正确的区块是 -67 —— 差一格就会漏掉一整列区块，
  * 表现为海域西侧的锚点永远预热不到。
 */
class ArenaGeometryTest {

	@Test
	void negativeCoordinatesUseFloorDivision() {
		// test_sea：圆心 -1000,1000 半径 60 → 方块盒 -1060..-940 / 940..1060
		ArenaGeometry.ChunkBox box = ArenaGeometry.boxOfCircle(-1000, 1000, 60);
		assertEquals(-67, box.minChunkX(), "截断除法会算成 -66，那一列区块就漏了");
		assertEquals(-59, box.maxChunkX());
		assertEquals(58, box.minChunkZ());
		assertEquals(66, box.maxChunkZ());
		assertEquals(81, box.count());
	}

	@Test
	void productionSeaCovers324Chunks() {
		ArenaGeometry.ChunkBox box = ArenaGeometry.boxOfCircle(0, 0, 140);
		assertEquals(-9, box.minChunkX(), "floor(-140/16) 是 -9 不是 -8");
		assertEquals(8, box.maxChunkX());
		assertEquals(324, box.count());
		assertEquals(box.count(), ArenaGeometry.chunkCoords(box).size(), "展开的坐标数必须等于盒内区块数");
	}

	@Test
	void coordsWalkRowMajorAndCoverEveryChunk() {
		ArenaGeometry.ChunkBox box = ArenaGeometry.boxOfCircle(0, 0, 17);
		List<int[]> coords = ArenaGeometry.chunkCoords(box);
		assertEquals(16, coords.size(), () -> "半径 17 → 每轴 4 个区块（-2..1），共 16，实际 " + coords.size());
		int hits = 0;
		for (int cx = box.minChunkX(); cx <= box.maxChunkX(); cx++) {
			for (int cz = box.minChunkZ(); cz <= box.maxChunkZ(); cz++) {
				final int x = cx;
				final int z = cz;
				if (coords.stream().anyMatch(c -> c[0] == x && c[1] == z)) {
					hits++;
				}
			}
		}
		assertEquals(coords.size(), hits, "展开表必须既不重也不漏");
	}

	@Test
	void badRadiusIsRejectedLoudly() {
		assertThrows(IllegalArgumentException.class, () -> ArenaGeometry.boxOfCircle(0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> ArenaGeometry.boxOfCircle(0, 0, -5));
	}
}
