package cn.net.rms.confluxmap.core.util;

import java.util.ArrayList;
import java.util.List;

/** Inclusive Minecraft chunk bounds covering one half-open screen-space map viewport. */
public record ChunkViewport(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
    private static final int CHUNK_BLOCKS = 16;

    public ChunkViewport {
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            throw new IllegalArgumentException("empty chunk viewport");
        }
    }

    public static ChunkViewport covering(
        final double centerX,
        final double centerZ,
        final int screenWidth,
        final int screenHeight,
        final double blocksPerScreenPixel
    ) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)
            || !Double.isFinite(blocksPerScreenPixel) || blocksPerScreenPixel <= 0.0
            || screenWidth <= 0 || screenHeight <= 0) {
            throw new IllegalArgumentException("invalid map viewport");
        }
        final double halfWidthBlocks = screenWidth * 0.5 * blocksPerScreenPixel;
        final double halfHeightBlocks = screenHeight * 0.5 * blocksPerScreenPixel;
        return new ChunkViewport(
            blockToChunk(centerX - halfWidthBlocks),
            blockToChunk(Math.nextDown(centerX + halfWidthBlocks)),
            blockToChunk(centerZ - halfHeightBlocks),
            blockToChunk(Math.nextDown(centerZ + halfHeightBlocks))
        );
    }

    public static ChunkViewport centered(
        final int centerChunkX, final int centerChunkZ, final int radius
    ) {
        if (radius < 0) {
            throw new IllegalArgumentException("negative chunk viewport radius");
        }
        return new ChunkViewport(
            Math.subtractExact(centerChunkX, radius), Math.addExact(centerChunkX, radius),
            Math.subtractExact(centerChunkZ, radius), Math.addExact(centerChunkZ, radius)
        );
    }

    public long chunkCount() {
        return ((long) maxChunkX - minChunkX + 1L) * ((long) maxChunkZ - minChunkZ + 1L);
    }

    public boolean contains(final int chunkX, final int chunkZ) {
        return chunkX >= minChunkX && chunkX <= maxChunkX
            && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    public List<ChunkRegionSlice> regionSlices() {
        final int minRegionX = Math.floorDiv(minChunkX, ChunkRegionSlice.REGION_CHUNKS);
        final int maxRegionX = Math.floorDiv(maxChunkX, ChunkRegionSlice.REGION_CHUNKS);
        final int minRegionZ = Math.floorDiv(minChunkZ, ChunkRegionSlice.REGION_CHUNKS);
        final int maxRegionZ = Math.floorDiv(maxChunkZ, ChunkRegionSlice.REGION_CHUNKS);
        final long count = ((long) maxRegionX - minRegionX + 1L) * ((long) maxRegionZ - minRegionZ + 1L);
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("chunk viewport contains too many regions");
        }
        final List<ChunkRegionSlice> slices = new ArrayList<>((int) count);
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                final int regionMinChunkX = Math.multiplyExact(regionX, ChunkRegionSlice.REGION_CHUNKS);
                final int regionMinChunkZ = Math.multiplyExact(regionZ, ChunkRegionSlice.REGION_CHUNKS);
                slices.add(new ChunkRegionSlice(
                    regionX,
                    regionZ,
                    Math.max(0, minChunkX - regionMinChunkX),
                    Math.max(0, minChunkZ - regionMinChunkZ),
                    Math.min(ChunkRegionSlice.REGION_CHUNKS - 1, maxChunkX - regionMinChunkX),
                    Math.min(ChunkRegionSlice.REGION_CHUNKS - 1, maxChunkZ - regionMinChunkZ)
                ));
            }
        }
        return List.copyOf(slices);
    }

    /** Region slices covering this viewport except for the player's local-authority rectangle. */
    public List<ChunkRegionSlice> regionSlicesExcluding(final ChunkViewport excluded) {
        if (excluded == null) {
            return regionSlices();
        }
        final List<ChunkRegionSlice> slices = new ArrayList<>();
        for (final ChunkRegionSlice slice : regionSlices()) {
            slices.addAll(slice.excluding(excluded));
        }
        return List.copyOf(slices);
    }

    private static int blockToChunk(final double block) {
        final double chunk = Math.floor(block / CHUNK_BLOCKS);
        if (chunk < Integer.MIN_VALUE || chunk > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("chunk viewport outside integer coordinates");
        }
        return (int) chunk;
    }
}
