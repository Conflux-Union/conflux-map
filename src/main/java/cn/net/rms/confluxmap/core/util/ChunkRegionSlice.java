package cn.net.rms.confluxmap.core.util;

/** A rectangular subset of one 16x16-chunk summary region. */
public record ChunkRegionSlice(
    int regionX,
    int regionZ,
    int minLocalChunkX,
    int minLocalChunkZ,
    int maxLocalChunkX,
    int maxLocalChunkZ
) {
    public static final int REGION_CHUNKS = 16;

    public ChunkRegionSlice {
        if (minLocalChunkX < 0 || minLocalChunkZ < 0
            || maxLocalChunkX >= REGION_CHUNKS || maxLocalChunkZ >= REGION_CHUNKS
            || minLocalChunkX > maxLocalChunkX || minLocalChunkZ > maxLocalChunkZ) {
            throw new IllegalArgumentException("invalid chunk region slice");
        }
    }

    public int width() {
        return maxLocalChunkX - minLocalChunkX + 1;
    }

    public int height() {
        return maxLocalChunkZ - minLocalChunkZ + 1;
    }

    public long chunkCount() {
        return (long) width() * height();
    }

    public int minChunkX() {
        return Math.addExact(Math.multiplyExact(regionX, REGION_CHUNKS), minLocalChunkX);
    }

    public int minChunkZ() {
        return Math.addExact(Math.multiplyExact(regionZ, REGION_CHUNKS), minLocalChunkZ);
    }
}
