package cn.net.rms.confluxmap.core.util;

import java.util.ArrayList;
import java.util.List;

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

    public int maxChunkX() {
        return Math.addExact(Math.multiplyExact(regionX, REGION_CHUNKS), maxLocalChunkX);
    }

    public int maxChunkZ() {
        return Math.addExact(Math.multiplyExact(regionZ, REGION_CHUNKS), maxLocalChunkZ);
    }

    /** Removes one world-coordinate rectangle, returning at most four disjoint region slices. */
    public List<ChunkRegionSlice> excluding(final ChunkViewport excluded) {
        if (excluded == null) {
            return List.of(this);
        }
        final int overlapMinX = Math.max(minChunkX(), excluded.minChunkX());
        final int overlapMaxX = Math.min(maxChunkX(), excluded.maxChunkX());
        final int overlapMinZ = Math.max(minChunkZ(), excluded.minChunkZ());
        final int overlapMaxZ = Math.min(maxChunkZ(), excluded.maxChunkZ());
        if (overlapMinX > overlapMaxX || overlapMinZ > overlapMaxZ) {
            return List.of(this);
        }
        final List<ChunkRegionSlice> remaining = new ArrayList<>(4);
        addWorldSlice(remaining, minChunkX(), minChunkZ(), maxChunkX(), overlapMinZ - 1);
        addWorldSlice(remaining, minChunkX(), overlapMaxZ + 1, maxChunkX(), maxChunkZ());
        addWorldSlice(remaining, minChunkX(), overlapMinZ, overlapMinX - 1, overlapMaxZ);
        addWorldSlice(remaining, overlapMaxX + 1, overlapMinZ, maxChunkX(), overlapMaxZ);
        return List.copyOf(remaining);
    }

    private void addWorldSlice(
        final List<ChunkRegionSlice> slices,
        final int minX,
        final int minZ,
        final int maxX,
        final int maxZ
    ) {
        if (minX > maxX || minZ > maxZ) {
            return;
        }
        final int baseX = Math.multiplyExact(regionX, REGION_CHUNKS);
        final int baseZ = Math.multiplyExact(regionZ, REGION_CHUNKS);
        slices.add(new ChunkRegionSlice(
            regionX, regionZ,
            minX - baseX, minZ - baseZ, maxX - baseX, maxZ - baseZ
        ));
    }
}
