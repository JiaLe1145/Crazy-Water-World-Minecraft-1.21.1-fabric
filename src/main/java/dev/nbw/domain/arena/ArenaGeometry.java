package dev.nbw.domain.arena;

/**
 * arena 的区块级几何（纯函数，零 Minecraft 依赖）。
 *
 * <p>为什么单独一层：预热要按区块逐个下票，而"哪些区块属于这片海"必须能离线算出来并单测；
 * 负坐标尤其容易算错 —— 用算术除法截断方向不对，只有 floorDiv 才对。
 */
public final class ArenaGeometry {

	public static final int CHUNK_EDGE = 16;

	/** 区块包围盒：[minChunkX, maxChunkX] × [minChunkZ, maxChunkZ]，两端都含。 */
	public record ChunkBox(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
		public int count() {
			return (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
		}
	}

	private ArenaGeometry() {
	}

	/** 圆心 (centerX,centerZ)、半径 radius 的圆覆盖到的区块盒（按外接方块盒取整）。 */
	public static ChunkBox boxOfCircle(int centerX, int centerZ, int radius) {
		if (radius <= 0) {
			throw new IllegalArgumentException("radius 必须 > 0，当前 " + radius);
		}
		int minX = Math.floorDiv(centerX - radius, CHUNK_EDGE);
		int maxX = Math.floorDiv(centerX + radius, CHUNK_EDGE);
		int minZ = Math.floorDiv(centerZ - radius, CHUNK_EDGE);
		int maxZ = Math.floorDiv(centerZ + radius, CHUNK_EDGE);
		return new ChunkBox(minX, maxX, minZ, maxZ);
	}

	/** 按行优先展开成确定的坐标序列，保证预热顺序稳定、撤票范围能一一对上。 */
	public static java.util.List<int[]> chunkCoords(ChunkBox box) {
		java.util.List<int[]> out = new java.util.ArrayList<>();
		for (int cz = box.minChunkZ(); cz <= box.maxChunkZ(); cz++) {
			for (int cx = box.minChunkX(); cx <= box.maxChunkX(); cx++) {
				out.add(new int[]{cx, cz});
			}
		}
		return out;
	}
}
