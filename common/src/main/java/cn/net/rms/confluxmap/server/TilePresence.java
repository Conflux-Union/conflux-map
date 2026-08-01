package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;

/**
 * Builds one prediction tile's coarse presence bitmap from per-region chunk-generation flags.
 *
 * <p>A tile is always 16x16 presence cells. At LOD {@code n} a cell spans {@code 16 * 2^n} blocks,
 * which is {@code 2^n} by {@code 2^n} chunks, so a cell is set when any chunk it covers is
 * generated - the same union {@link SummaryTile#presence()} derives from full summaries. Working
 * from flags alone is what makes coarse tiles affordable: LOD 4 spans 256 regions, and decoding
 * their columns would allocate millions of records to produce 32 bytes.
 */
public final class TilePresence {
    /** Chunk-generation flags of one LOD-0 region, or {@code null} when nothing is cached. */
    @FunctionalInterface
    public interface RegionLookup {
        boolean[] generated(int regionX, int regionZ);
    }

    private TilePresence() {
    }

    /** Regions the lookup cannot supply are simply left unset, which reads as "not generated". */
    public static byte[] build(final int lod, final int tileX, final int tileZ, final RegionLookup lookup) {
        if (lod < 0 || lod > 4) {
            throw new IllegalArgumentException("unsupported presence tile LOD " + lod);
        }
        if (lookup == null) {
            throw new IllegalArgumentException("region lookup is null");
        }
        final byte[] bits = new byte[Proto.PATCH_PRESENCE_BYTES];
        final int regionsPerSide = 1 << lod;
        final long baseRegionX = (long) tileX * regionsPerSide;
        final long baseRegionZ = (long) tileZ * regionsPerSide;
        for (int dz = 0; dz < regionsPerSide; dz++) {
            for (int dx = 0; dx < regionsPerSide; dx++) {
                final long regionX = baseRegionX + dx;
                final long regionZ = baseRegionZ + dz;
                if (regionX < Integer.MIN_VALUE || regionX > Integer.MAX_VALUE
                    || regionZ < Integer.MIN_VALUE || regionZ > Integer.MAX_VALUE) {
                    continue;
                }
                final boolean[] flags = lookup.generated((int) regionX, (int) regionZ);
                if (flags == null) {
                    continue;
                }
                if (flags.length != SummaryCodec.CHUNKS) {
                    throw new IllegalArgumentException(
                        "region must carry " + SummaryCodec.CHUNKS + " chunk flags, got " + flags.length
                    );
                }
                accumulate(bits, flags, lod, dx, dz);
            }
        }
        return bits;
    }

    /** ORs one region's generated chunks into whichever cells they fall in. */
    private static void accumulate(final byte[] bits, final boolean[] flags, final int lod, final int dx, final int dz) {
        for (int index = 0; index < SummaryCodec.CHUNKS; index++) {
            if (!flags[index]) {
                continue;
            }
            // Chunk offset from the tile origin; a region is 16x16 chunks in row-major order.
            final int chunkX = (dx << 4) | (index & 15);
            final int chunkZ = (dz << 4) | (index >>> 4);
            final int cell = (chunkZ >> lod) * 16 + (chunkX >> lod);
            bits[cell >>> 3] |= (byte) (1 << (cell & 7));
        }
    }
}
