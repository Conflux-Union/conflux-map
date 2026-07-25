package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TilePresenceTest {
    @Test
    void lod0CellIsExactlyOneChunk() {
        final byte[] bits = TilePresence.build(0, 0, 0, lookupOf(Map.of(key(0, 0), onlyChunk(3, 5))));

        assertEquals(1, setCells(bits), "one generated chunk must set exactly one cell at LOD 0");
        assertTrue(cell(bits, 3, 5));
    }

    @Test
    void lod4CellIsExactlyOneRegion() {
        // A LOD-4 cell spans 16x16 chunks, which is precisely one summary region.
        final byte[] bits = TilePresence.build(4, 0, 0, lookupOf(Map.of(key(2, 3), onlyChunk(9, 14))));

        assertEquals(1, setCells(bits), "any chunk of a region must set that region's single cell");
        assertTrue(cell(bits, 2, 3));
    }

    @Test
    void coarseCellUnionsEveryChunkItCovers() {
        // At LOD 2 a cell spans 4x4 chunks, so chunks (4..7, 0..3) all land in cell (1, 0).
        for (final int[] chunk : new int[][] {{4, 0}, {7, 3}, {5, 2}}) {
            final byte[] bits = TilePresence.build(2, 0, 0, lookupOf(Map.of(key(0, 0), onlyChunk(chunk[0], chunk[1]))));

            assertEquals(1, setCells(bits), "chunk " + chunk[0] + "," + chunk[1] + " must set one cell");
            assertTrue(cell(bits, 1, 0), "chunk " + chunk[0] + "," + chunk[1] + " belongs to cell 1,0");
        }
    }

    @Test
    void missingRegionsReadAsNotGenerated() {
        final byte[] bits = TilePresence.build(3, 7, -2, (regionX, regionZ) -> null);

        assertEquals(0, setCells(bits));
    }

    @Test
    void negativeTileCoordinatesAddressTheirOwnRegions() {
        final List<long[]> asked = new ArrayList<>();
        TilePresence.build(1, -1, -1, (regionX, regionZ) -> {
            asked.add(new long[] {regionX, regionZ});
            return null;
        });

        assertEquals(4, asked.size(), "a LOD-1 tile covers 2x2 regions");
        assertTrue(asked.stream().anyMatch(rc -> rc[0] == -2 && rc[1] == -2), "tile -1,-1 starts at region -2,-2");
        assertTrue(asked.stream().anyMatch(rc -> rc[0] == -1 && rc[1] == -1), "tile -1,-1 ends at region -1,-1");
    }

    /**
     * The bitmap a coarse answer sends must be the one the full summary path would have produced;
     * otherwise a tile's presence would flip as it crosses the correction ceiling.
     */
    @Test
    void matchesTheBitmapBuiltFromFullSummaries() {
        for (int lod = 0; lod <= 2; lod++) {
            final int regionsPerSide = 1 << lod;
            final int tileX = 3;
            final int tileZ = -2;
            final Map<Long, boolean[]> flags = new HashMap<>();
            final List<SummaryCodec.Region> regions = new ArrayList<>();
            for (int dz = 0; dz < regionsPerSide; dz++) {
                for (int dx = 0; dx < regionsPerSide; dx++) {
                    final int regionX = tileX * regionsPerSide + dx;
                    final int regionZ = tileZ * regionsPerSide + dz;
                    final boolean[] generated = scatteredChunks(regionX, regionZ);
                    flags.put(key(regionX, regionZ), generated);
                    regions.add(regionOf(regionX, regionZ, generated));
                }
            }

            final byte[] fromSummaries = new SummaryTile(lod, tileX, tileZ, regions).presence();
            final byte[] fromFlags = TilePresence.build(lod, tileX, tileZ, lookupOf(flags));

            assertArrayEquals(fromSummaries, fromFlags, "presence disagreed at LOD " + lod);
        }
    }

    @Test
    void anUngeneratedRegionLeavesItsCellsClear() {
        final byte[] bits = TilePresence.build(4, 0, 0, lookupOf(Map.of(key(5, 5), new boolean[SummaryCodec.CHUNKS])));

        assertEquals(0, setCells(bits));
        assertFalse(cell(bits, 5, 5));
    }

    @Test
    void rejectsUnsupportedInput() {
        assertThrows(IllegalArgumentException.class, () -> TilePresence.build(5, 0, 0, (x, z) -> null));
        assertThrows(IllegalArgumentException.class, () -> TilePresence.build(-1, 0, 0, (x, z) -> null));
        assertThrows(IllegalArgumentException.class, () -> TilePresence.build(0, 0, 0, null));
        assertThrows(
            IllegalArgumentException.class,
            () -> TilePresence.build(0, 0, 0, (x, z) -> new boolean[8])
        );
    }

    // ---- helpers ----

    private static long key(final int regionX, final int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    private static TilePresence.RegionLookup lookupOf(final Map<Long, boolean[]> regions) {
        return (regionX, regionZ) -> regions.get(key(regionX, regionZ));
    }

    private static boolean[] onlyChunk(final int localX, final int localZ) {
        final boolean[] flags = new boolean[SummaryCodec.CHUNKS];
        flags[localZ * 16 + localX] = true;
        return flags;
    }

    /** A deterministic sparse pattern, so neighbouring regions do not share the same layout. */
    private static boolean[] scatteredChunks(final int regionX, final int regionZ) {
        final boolean[] flags = new boolean[SummaryCodec.CHUNKS];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = ((i * 31 + regionX * 7 + regionZ * 13) & 7) == 0;
        }
        return flags;
    }

    private static SummaryCodec.Region regionOf(final int regionX, final int regionZ, final boolean[] generated) {
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = generated[i]
                ? new SummaryCodec.Chunk(true, 1L, new SummaryCodec.Column[SummaryCodec.COLUMNS])
                : SummaryCodec.Chunk.empty();
        }
        return new SummaryCodec.Region(regionX, regionZ, 1L, chunks);
    }

    private static boolean cell(final byte[] bits, final int cellX, final int cellZ) {
        final int index = cellZ * 16 + cellX;
        return (bits[index >>> 3] & (1 << (index & 7))) != 0;
    }

    private static int setCells(final byte[] bits) {
        assertEquals(Proto.PATCH_PRESENCE_BYTES, bits.length);
        int count = 0;
        for (final byte b : bits) {
            count += Integer.bitCount(b & 0xFF);
        }
        return count;
    }

    static {
        // Guard the helper itself: a fully-clear pattern would make the equivalence test vacuous.
        final boolean[] sample = scatteredChunks(3, -2);
        int generated = 0;
        for (final boolean flag : sample) {
            generated += flag ? 1 : 0;
        }
        if (generated == 0 || generated == sample.length) {
            throw new AssertionError("scatteredChunks must be a mixed pattern, got " + Arrays.toString(sample));
        }
    }
}
