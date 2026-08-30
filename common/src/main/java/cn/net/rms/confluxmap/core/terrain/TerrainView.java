package cn.net.rms.confluxmap.core.terrain;

public record TerrainView(
    long sessionToken,
    long generation,
    int pivotY,
    int minChunkX,
    int maxChunkX,
    int minChunkZ,
    int maxChunkZ
) {
    public TerrainView(final long sessionToken, final long generation, final int pivotY) {
        this(
            sessionToken,
            generation,
            pivotY,
            Integer.MIN_VALUE,
            Integer.MAX_VALUE,
            Integer.MIN_VALUE,
            Integer.MAX_VALUE
        );
    }

    boolean contains(final int chunkX, final int chunkZ) {
        return chunkX >= minChunkX && chunkX <= maxChunkX
            && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }
}
