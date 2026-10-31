package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import org.junit.jupiter.api.Test;

class PredictionMipCacheTest {
    private static final int PIXELS = BaselineGrid.PIXELS * BaselineGrid.PIXELS;
    /** LOD0 children aggregate into a LOD1 parent: 32 chunk cells per side. */
    private static final int PARENT_LOD = 1;
    private static final int PARENT_CHUNK_CELLS = 32 * 32;

    @Test
    void aggregateUsesTheMostConservativeCorrectionProfile() {
        final PredictionMipCache.Tile[] children = new PredictionMipCache.Tile[] {
            tile(CorrectionProfile.SOURCE_LIGHT_V2),
            tile(CorrectionProfile.SOURCE_LIGHT_V2),
            tile(CorrectionProfile.LEGACY_V1),
            tile(CorrectionProfile.SOURCE_LIGHT_V2)
        };

        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            children, PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertEquals(CorrectionProfile.LEGACY_V1, aggregate.correctionProfile());
    }

    @Test
    void dynamicLimitAdjustmentTrimsExcessTiles() {
        final PredictionMipCache cache = new PredictionMipCache(4);
        final cn.net.rms.confluxmap.core.model.DimensionId dim = cn.net.rms.confluxmap.core.model.DimensionId.OVERWORLD;
        final cn.net.rms.confluxmap.core.model.WorldIdentity world = cn.net.rms.confluxmap.core.model.WorldIdentity.singleplayer("test");
        for (int i = 0; i < 4; i++) {
            cache.put(new cn.net.rms.confluxmap.core.model.TileKey(world, dim, "surface!pred", 0, i, 0), tile(CorrectionProfile.SOURCE_LIGHT_V2));
        }
        assertEquals(4, cache.size());
        assertEquals(4, cache.limit());

        cache.setLimit(2);
        assertEquals(2, cache.size());
        assertEquals(2, cache.limit());
    }

    /** A tile built without a server differential must not carry a revision plane. */
    @Test
    void nullRevisionsAreAcceptedByTheTileInvariant() {
        final PredictionMipCache.Tile tile = new PredictionMipCache.Tile(
            0, new int[PIXELS], new byte[PIXELS], new short[PIXELS],
            new byte[PatchCodec.MASK_BYTES], null, new byte[PIXELS],
            1.0F, 0F, PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );

        assertNull(tile.syncChunkSourceRevisions());
    }

    /** The plane is chunk-granular: a per-pixel-sized array must be rejected at LOD0. */
    @Test
    void tileRejectsPerPixelRevisionPlanes() {
        assertThrows(IllegalArgumentException.class, () -> new PredictionMipCache.Tile(
            0, new int[PIXELS], new byte[PIXELS], new short[PIXELS],
            new byte[PatchCodec.MASK_BYTES], new long[PIXELS], new byte[PIXELS],
            1.0F, 0F, PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        ));
    }

    /** All four parents null: the aggregate stays null instead of allocating unknown cells. */
    @Test
    void purePredictionAggregateKeepsRevisionsUnallocated() {
        final PredictionMipCache.Tile[] children = new PredictionMipCache.Tile[] {
            pureTile(), pureTile(), pureTile(), pureTile()
        };

        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            children, PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertNull(aggregate.syncChunkSourceRevisions());
        for (int byteIndex = 0; byteIndex < PatchCodec.MASK_BYTES; byteIndex++) {
            assertEquals(0, aggregate.syncEvaluated()[byteIndex],
                "pure prediction must not mark any pixel evaluated");
        }
    }

    /** Mixed children: the corrected child's revisions survive, the null siblings are ignored. */
    @Test
    void aggregateCarriesRevisionsFromTheOnlyCorrectedChild() {
        final PredictionMipCache.Tile corrected = correctedTile(0, 0, 17L);
        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            new PredictionMipCache.Tile[] {corrected, pureTile(), pureTile(), pureTile()},
            PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertNotNull(aggregate.syncChunkSourceRevisions(),
            "a parent holding an evaluated sync pixel needs a revision plane");
        assertTrue(isEvaluated(aggregate.syncEvaluated(), 0));
        assertEquals(17L, aggregate.syncChunkSourceRevisions()[0]);
        assertEquals(PredictionMipCache.MISSING, aggregate.syncChunkSourceRevisions()[1]);
    }

    /** The parent chunk cell must track the child chunk offset, not collapse onto cell 0. */
    @Test
    void aggregateMapsChildChunkRevisionsToTheMatchingParentCell() {
        final PredictionMipCache.Tile corrected = correctedTile(2, 0, 42L);
        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            new PredictionMipCache.Tile[] {corrected, pureTile(), pureTile(), pureTile()},
            PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertEquals(PARENT_CHUNK_CELLS, aggregate.syncChunkSourceRevisions().length);
        assertEquals(42L, aggregate.syncChunkSourceRevisions()[2]);
        assertEquals(PredictionMipCache.MISSING, aggregate.syncChunkSourceRevisions()[0]);
    }

    /**
     * Void columns stay "known, no surface" through the mip aggregate instead of averaging.
     */
    @Test
    void voidSurfacesStayVoidThroughAggregation() {
        final PredictionMipCache.Tile voidChild = surfacedTile(BaselineGrid.NO_SURFACE_SHORT);
        final PredictionMipCache.Tile landChild = surfacedTile((short) 80);
        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            new PredictionMipCache.Tile[] {voidChild, landChild, landChild, landChild},
            PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertEquals(BaselineGrid.NO_SURFACE_SHORT, aggregate.surfaces()[0],
            "four void samples must aggregate back to the void sentinel, not to Y=0");
        assertEquals((short) 80, aggregate.surfaces()[128]);
    }

    /**
     * The int-domain aggregate skipped void samples inside a 2x2 block; the packed form must do
     * the same, otherwise one void sample drags a real column far below the world.
     */
    @Test
    void mixedVoidAndLandSamplesAverageOnlyTheLandPixels() {
        final short[][] quad = {
            {BaselineGrid.NO_SURFACE_SHORT, (short) 80, (short) 80, (short) 80},
            {(short) 80, (short) 80, (short) 80, (short) 80},
            {(short) 80, (short) 80, (short) 80, (short) 80},
            {(short) 80, (short) 80, (short) 80, (short) 80}
        };
        final PredictionMipCache.Tile[] children = new PredictionMipCache.Tile[4];
        for (int i = 0; i < 4; i++) {
            children[i] = tiledQuadrant(quad[i]);
        }

        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            children, PredictionViewMode.EVERYWHERE, PARENT_LOD
        );

        assertEquals((short) 80, aggregate.surfaces()[0],
            "a single void sample in the 2x2 block must not drag the average down");
    }

    private static PredictionMipCache.Tile tiledQuadrant(final short[] quadrant) {
        final short[] surfaces = new short[PIXELS];
        java.util.Arrays.fill(surfaces, (short) 80);
        final int[] pixels = {0, 1, 256, 257};
        for (int i = 0; i < 4; i++) {
            surfaces[pixels[i]] = quadrant[i];
        }
        return new PredictionMipCache.Tile(
            0, new int[PIXELS], new byte[PIXELS], surfaces,
            new byte[PatchCodec.MASK_BYTES], null, new byte[PIXELS],
            1.0F, 0F, PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );
    }

    private static boolean isEvaluated(final byte[] evaluated, final int pixel) {
        return (evaluated[pixel >>> 3] & (1 << (pixel & 7))) != 0;
    }

    private static PredictionMipCache.Tile pureTile() {
        return new PredictionMipCache.Tile(
            0, new int[PIXELS], new byte[PIXELS], new short[PIXELS],
            new byte[PatchCodec.MASK_BYTES], null, new byte[PIXELS],
            1.0F, 0F, PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );
    }

    /** LOD0 tile with one fully evaluated 2x2 block inside chunk ({@code chunkX}, {@code chunkZ}). */
    private static PredictionMipCache.Tile correctedTile(
        final int chunkX,
        final int chunkZ,
        final long value
    ) {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        final long[] chunkRevisions = new long[16 * 16];
        java.util.Arrays.fill(chunkRevisions, PredictionMipCache.MISSING);
        final int pixelX = chunkX * 16;
        final int pixelZ = chunkZ * 16;
        for (final int pixel : new int[] {
            pixelZ * 256 + pixelX, pixelZ * 256 + pixelX + 1,
            (pixelZ + 1) * 256 + pixelX, (pixelZ + 1) * 256 + pixelX + 1
        }) {
            evaluated[pixel >>> 3] |= (byte) (1 << (pixel & 7));
        }
        chunkRevisions[chunkZ * 16 + chunkX] = value;
        return new PredictionMipCache.Tile(
            0, new int[PIXELS], new byte[PIXELS], new short[PIXELS],
            evaluated, chunkRevisions, new byte[PIXELS],
            1.0F, 0F, PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );
    }

    private static PredictionMipCache.Tile surfacedTile(final short surfaceY) {
        final short[] surfaces = new short[PIXELS];
        java.util.Arrays.fill(surfaces, surfaceY);
        return new PredictionMipCache.Tile(
            0, new int[PIXELS], new byte[PIXELS], surfaces,
            new byte[PatchCodec.MASK_BYTES], null, new byte[PIXELS],
            1.0F, 0F, PredictionViewMode.EVERYWHERE, false, 0L, 0L,
            CorrectionProfile.LEGACY_V1
        );
    }

    private static PredictionMipCache.Tile tile(final CorrectionProfile profile) {
        return new PredictionMipCache.Tile(
            0,
            new int[PIXELS],
            new byte[PIXELS],
            new short[PIXELS],
            new byte[PatchCodec.MASK_BYTES],
            new long[16 * 16],
            new byte[PIXELS],
            1.0F,
            0F,
            PredictionViewMode.EVERYWHERE,
            false,
            0L,
            0L,
            profile
        );
    }
}
