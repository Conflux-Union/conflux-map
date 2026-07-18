package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.util.Collection;

/**
 * The server-side view of one prediction tile.
 *
 * <p>A tile always has 256 output pixels per edge. At LOD {@code n} those pixels cover
 * {@code 256 * 2^n} blocks and therefore {@code 2^n} by {@code 2^n} LOD-0 regions. Keeping this
 * mapping in one object prevents the summary reader and patch builder from silently using
 * different coordinate systems.
 */
public final class SummaryTile {
    public static final int PIXELS = 256;
    public static final int REGION_BLOCKS = 256;
    private static final int CHUNK_BLOCKS = 16;

    private final int lod;
    private final int tileX;
    private final int tileZ;
    private final int scale;
    private final int regionsPerSide;
    private final int baseRegionX;
    private final int baseRegionZ;
    private final long originBlockX;
    private final long originBlockZ;
    private final SummaryCodec.Region[] regions;
    private final long revision;
    private final byte[] presence;

    public SummaryTile(
        final int lod,
        final int tileX,
        final int tileZ,
        final Collection<SummaryCodec.Region> sourceRegions
    ) {
        if (lod < 0 || lod > 4) {
            throw new IllegalArgumentException("unsupported summary tile LOD " + lod);
        }
        if (sourceRegions == null) {
            throw new IllegalArgumentException("summary regions are null");
        }
        this.lod = lod;
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.scale = 1 << lod;
        this.regionsPerSide = scale;
        final long baseX = (long) tileX * regionsPerSide;
        final long baseZ = (long) tileZ * regionsPerSide;
        if (baseX < Integer.MIN_VALUE || baseX > Integer.MAX_VALUE
            || baseZ < Integer.MIN_VALUE || baseZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("summary tile region coordinate overflow");
        }
        this.baseRegionX = (int) baseX;
        this.baseRegionZ = (int) baseZ;
        this.originBlockX = (long) tileX * REGION_BLOCKS * scale;
        this.originBlockZ = (long) tileZ * REGION_BLOCKS * scale;
        this.regions = new SummaryCodec.Region[regionsPerSide * regionsPerSide];

        long maxRevision = 0L;
        for (final SummaryCodec.Region region : sourceRegions) {
            if (region == null) {
                continue;
            }
            final long dx = (long) region.rx() - baseRegionX;
            final long dz = (long) region.rz() - baseRegionZ;
            if (dx < 0 || dx >= regionsPerSide || dz < 0 || dz >= regionsPerSide) {
                continue;
            }
            regions[(int) dz * regionsPerSide + (int) dx] = region;
            for (final SummaryCodec.Chunk chunk : region.chunks()) {
                if (chunk.generated()) {
                    maxRevision = Math.max(maxRevision, chunk.revision());
                }
            }
        }
        revision = maxRevision;
        presence = buildPresence();
    }

    public int lod() {
        return lod;
    }

    public int tileX() {
        return tileX;
    }

    public int tileZ() {
        return tileZ;
    }

    public int scale() {
        return scale;
    }

    public long originBlockX() {
        return originBlockX;
    }

    public long originBlockZ() {
        return originBlockZ;
    }

    /** Maximum revision of any generated chunk covered by this tile. */
    public long revision() {
        return revision;
    }

    /**
     * Returns one bit per 16x16 output-pixel cell. At LOD0 a cell is exactly one Minecraft chunk;
     * at higher LODs it represents the union of all chunks touched by that output cell.
     */
    public byte[] presence() {
        return presence.clone();
    }

    /** The actual column represented by one output pixel, or a generated-without-column marker. */
    public Pixel pixel(final int pixelX, final int pixelZ) {
        if (pixelX < 0 || pixelX >= PIXELS || pixelZ < 0 || pixelZ >= PIXELS) {
            return null;
        }
        final long blockX = originBlockX + (long) pixelX * scale + (scale >>> 1);
        final long blockZ = originBlockZ + (long) pixelZ * scale + (scale >>> 1);
        return pixelAtBlock(blockX, blockZ);
    }

    /** Looks up a summary column using world block coordinates with floor semantics. */
    public Pixel pixelAtBlock(final long blockX, final long blockZ) {
        final SummaryCodec.Chunk chunk = chunkAtBlock(blockX, blockZ);
        if (chunk == null || !chunk.generated()) {
            return chunk == null ? null : new Pixel(chunk, null);
        }
        final int localX = (int) Math.floorMod(blockX, REGION_BLOCKS);
        final int localZ = (int) Math.floorMod(blockZ, REGION_BLOCKS);
        final SummaryCodec.Column column = chunk.columns()[(localZ & 15) * 16 + (localX & 15)];
        return new Pixel(chunk, column);
    }

    public SummaryCodec.Chunk chunkAtBlock(final long blockX, final long blockZ) {
        final long regionX = Math.floorDiv(blockX, REGION_BLOCKS);
        final long regionZ = Math.floorDiv(blockZ, REGION_BLOCKS);
        final long dx = regionX - baseRegionX;
        final long dz = regionZ - baseRegionZ;
        if (dx < 0 || dx >= regionsPerSide || dz < 0 || dz >= regionsPerSide) {
            return null;
        }
        final SummaryCodec.Region region = regions[(int) dz * regionsPerSide + (int) dx];
        if (region == null) {
            return null;
        }
        final int localX = (int) Math.floorMod(blockX, REGION_BLOCKS);
        final int localZ = (int) Math.floorMod(blockZ, REGION_BLOCKS);
        return region.chunks()[(localZ >>> 4) * 16 + (localX >>> 4)];
    }

    private byte[] buildPresence() {
        final byte[] bits = new byte[Proto.PATCH_PRESENCE_BYTES];
        for (int cellZ = 0; cellZ < 16; cellZ++) {
            for (int cellX = 0; cellX < 16; cellX++) {
                final long firstBlockX = originBlockX + (long) cellX * 16 * scale;
                final long firstBlockZ = originBlockZ + (long) cellZ * 16 * scale;
                final long lastBlockX = firstBlockX + 16L * scale - 1L;
                final long lastBlockZ = firstBlockZ + 16L * scale - 1L;
                final long firstChunkX = Math.floorDiv(firstBlockX, CHUNK_BLOCKS);
                final long firstChunkZ = Math.floorDiv(firstBlockZ, CHUNK_BLOCKS);
                final long lastChunkX = Math.floorDiv(lastBlockX, CHUNK_BLOCKS);
                final long lastChunkZ = Math.floorDiv(lastBlockZ, CHUNK_BLOCKS);
                boolean generated = false;
                for (long chunkZ = firstChunkZ; chunkZ <= lastChunkZ && !generated; chunkZ++) {
                    for (long chunkX = firstChunkX; chunkX <= lastChunkX; chunkX++) {
                        final SummaryCodec.Chunk chunk = chunkAtChunk(chunkX, chunkZ);
                        if (chunk != null && chunk.generated()) {
                            generated = true;
                            break;
                        }
                    }
                }
                if (generated) {
                    final int bit = cellZ * 16 + cellX;
                    bits[bit >>> 3] |= (byte) (1 << (bit & 7));
                }
            }
        }
        return bits;
    }

    private SummaryCodec.Chunk chunkAtChunk(final long chunkX, final long chunkZ) {
        final long regionX = Math.floorDiv(chunkX, 16L);
        final long regionZ = Math.floorDiv(chunkZ, 16L);
        final long dx = regionX - baseRegionX;
        final long dz = regionZ - baseRegionZ;
        if (dx < 0 || dx >= regionsPerSide || dz < 0 || dz >= regionsPerSide) {
            return null;
        }
        final SummaryCodec.Region region = regions[(int) dz * regionsPerSide + (int) dx];
        if (region == null) {
            return null;
        }
        final int localX = (int) Math.floorMod(chunkX, 16L);
        final int localZ = (int) Math.floorMod(chunkZ, 16L);
        return region.chunks()[localZ * 16 + localX];
    }

    public record Pixel(SummaryCodec.Chunk chunk, SummaryCodec.Column column) {
    }
}
