package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PredictionBaselineCacheTest {
    @Test
    void baselineIdentityIncludesSeedPresetReloadAndNetherHeight() {
        final PredictionBaselineCache cache = new PredictionBaselineCache(16);
        final FlatBaseline flat1 = new FlatBaseline(1, 64, 0, 0, 0, "minecraft:stone");
        final FlatBaseline flat2 = new FlatBaseline(1, 64, 0, 0, 0, "minecraft:dirt");
        final QuadrantLayout q1 = new QuadrantLayout(
            QuadrantLayout.Style.NOISE, null, QuadrantLayout.Style.NOISE, null,
            QuadrantLayout.Style.NOISE, null, QuadrantLayout.Style.NOISE, null
        );
        final QuadrantLayout q1Clone = new QuadrantLayout(
            QuadrantLayout.Style.NOISE, null, QuadrantLayout.Style.NOISE, null,
            QuadrantLayout.Style.NOISE, null, QuadrantLayout.Style.NOISE, null
        );
        final QuadrantLayout q2 = new QuadrantLayout(
            QuadrantLayout.Style.FLAT, flat1, QuadrantLayout.Style.NOISE, null,
            QuadrantLayout.Style.NOISE, null, QuadrantLayout.Style.NOISE, null
        );

        final PredictionBaselineCache.Key baseKey = fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat1, q1, 127,
            PredictionBaselineCache.ENGINE_GPU);
        cache.put(baseKey, tile());

        assertNotNull(cache.get(baseKey));
        // QuadrantLayout value equality: equal new instance must hit
        assertNotNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat1, q1Clone, 127,
            PredictionBaselineCache.ENGINE_GPU)));

        // Distinct dimension checks
        assertNull(cache.get(fullKey(0, 2, 0, 28, WorldPreset.DEFAULT, flat1, q1, 127,
            PredictionBaselineCache.ENGINE_GPU)), "seed mismatch");
        assertNull(cache.get(fullKey(0, 1, 1, 28, WorldPreset.DEFAULT, flat1, q1, 127,
            PredictionBaselineCache.ENGINE_GPU)), "generation mismatch");
        assertNull(cache.get(fullKey(0, 1, 0, 29, WorldPreset.DEFAULT, flat1, q1, 127,
            PredictionBaselineCache.ENGINE_GPU)), "version mismatch");
        assertNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.FLAT, flat1, q1, 127,
            PredictionBaselineCache.ENGINE_GPU)), "preset mismatch");
        assertNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat2, q1, 127,
            PredictionBaselineCache.ENGINE_GPU)), "flat mismatch");
        assertNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat1, q2, 127,
            PredictionBaselineCache.ENGINE_GPU)), "quadrant mismatch");
        assertNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat1, q1, 63,
            PredictionBaselineCache.ENGINE_GPU)), "netherY mismatch");
        assertNull(cache.get(fullKey(1, 1, 0, 28, WorldPreset.DEFAULT, flat1, q1, 127,
            PredictionBaselineCache.ENGINE_GPU)), "tile coord mismatch");
        // The serving-engine dimension keeps CPU-reference and GPU planes apart: the two
        // backends only agree within the recalibrated waterline drift bounds, so an entry
        // published under one engine must never serve the other.
        assertNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat1, q1, 127,
            PredictionBaselineCache.ENGINE_CPU)), "serving engine mismatch");
        assertNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat1, q1, 127,
            PredictionBaselineCache.ENGINE_NONE)), "unknown engine mismatch");

        cache.clear();
        assertEquals(0, cache.size());
    }

    private static PredictionBaselineCache.Key fullKey(
        final int x, final long seed, final long generation, final int version,
        final WorldPreset preset, final FlatBaseline flat, final QuadrantLayout quadrants,
        final int netherY, final int servingEngine
    ) {
        return new PredictionBaselineCache.Key(
            new TileKey(WorldIdentity.singleplayer("baseline-cache"), DimensionId.OVERWORLD,
                "surface!pred", 0, x, 0),
            1L, generation, seed, version, preset, flat, quadrants, netherY, servingEngine
        );
    }

    @Test
    void sharesOnlyBaselinePlanesAndEvictsLeastRecentlyUsedEntry() {
        final PredictionBaselineCache cache = new PredictionBaselineCache(2);
        final PredictionMipCache.Tile first = tile();
        cache.put(key(0, 1, 0, WorldPreset.DEFAULT, 127), first);
        cache.put(key(1, 1, 0, WorldPreset.DEFAULT, 127), tile());
        final PredictionBaselineCache.Planes planes = cache.get(key(0, 1, 0, WorldPreset.DEFAULT, 127));
        assertSame(first.packedSurfaceKind(), planes.surfaceKind());
        final BaselineGrid reconstructedGrid = new BaselineGrid(0, 0, 0);
        final DerivedGrid reconstructedDerived = new DerivedGrid();
        planes.reconstruct(reconstructedGrid, reconstructedDerived);
        for (int i = 0; i < PackedSurfacePlane.CELLS; i++) {
            assertEquals(first.fluidDepth()[i] & 0xFF, reconstructedDerived.fluidDepth[i]);
        }
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                assertEquals(first.biomes()[z * BaselineGrid.PIXELS + x] & 0xFF,
                    reconstructedGrid.biomeId[BaselineGrid.index(x, z)]);
            }
        }
        cache.put(key(2, 1, 0, WorldPreset.DEFAULT, 127), tile());
        assertNull(cache.get(key(1, 1, 0, WorldPreset.DEFAULT, 127)));
        assertNotNull(cache.get(key(0, 1, 0, WorldPreset.DEFAULT, 127)));
        cache.setLimit(1);
        assertEquals(1, cache.size());
    }

    @Test
    void adaptToViewportExpandsInstantlyAndDefersShrinkage() {
        final PredictionBaselineCache cache = new PredictionBaselineCache(256);
        assertEquals(256, cache.configuredLimit());
        // Initial limit defaults to minimal (32) to save memory on small screens
        assertEquals(32, cache.limit());

        // Small viewport (10 tiles * 1.5 = 15 <= DEFAULT_MIN_TILES=32): remains 32
        cache.adaptToViewport(10);
        assertEquals(32, cache.limit());

        // Medium viewport (40 tiles * 1.5 = 60): expands instantly to 60
        cache.adaptToViewport(40);
        assertEquals(60, cache.limit());

        // Large 2K/4K viewport (100 tiles * 1.5 = 150): expands to 150
        cache.adaptToViewport(100);
        assertEquals(150, cache.limit());

        // Extreme viewport exceeding MAX_TILES (200 tiles * 1.5 = 300): clamped to configuredLimit 256
        cache.adaptToViewport(200);
        assertEquals(256, cache.limit());

        // Shrinkage anti-churn: zooming in back to 10 tiles does NOT shrink limit immediately
        cache.adaptToViewport(10);
        assertEquals(256, cache.limit(), "Capacity must be preserved during zoom-in to prevent LRU thrashing");

        // Explicit setLimit respects new ceiling (e.g. tier switch to ULTRA_PERFORMANCE = 64)
        // Since limit was 256, switching ceiling to 64 smoothly trims active limit to 64
        cache.setLimit(64);
        assertEquals(64, cache.configuredLimit());
        assertEquals(64, cache.limit(), "Switching tier must enforce ceiling");

        // Now with configuredLimit = 64, even a huge viewport cannot exceed 64
        cache.adaptToViewport(200);
        assertEquals(64, cache.limit(), "Adaptive expansion must respect configured tier ceiling");

        // Configured ceiling lower than DEFAULT_MIN_TILES (e.g. 1 in testing) is strictly respected
        cache.setLimit(1);
        assertEquals(1, cache.configuredLimit());
        assertEquals(1, cache.limit());
        cache.adaptToViewport(100);
        assertEquals(1, cache.limit(), "Viewport adaptation must never breach configured ceiling even below minimum");
    }

    @Test
    void guardedRleRoundTripConsistencyAndFallback() {
        // Case 1: Homogeneous array (65536 identical bytes) -> massive compression
        final byte[] uniform = new byte[65536];
        Arrays.fill(uniform, (byte) 42);
        final PredictionBaselineCache.GuardedRle rleUniform = PredictionBaselineCache.compressWithGuard(uniform);
        org.junit.jupiter.api.Assertions.assertFalse(rleUniform.isRaw());
        org.junit.jupiter.api.Assertions.assertTrue(rleUniform.data().length < 600);

        final int[] outFluid = new int[65536];
        PredictionBaselineCache.decompressFluidZeroAlloc(rleUniform, outFluid);
        for (int i = 0; i < 65536; i++) {
            assertEquals(42, outFluid[i]);
        }

        // Case 2: Alternating high-frequency noise -> guarded fallback to raw
        final byte[] noise = new byte[65536];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (byte) (i & 1);
        }
        final PredictionBaselineCache.GuardedRle rleNoise = PredictionBaselineCache.compressWithGuard(noise);
        org.junit.jupiter.api.Assertions.assertTrue(rleNoise.isRaw(), "Incompressible noise must trip guard");
        assertEquals(65536, rleNoise.data().length);

        final int[] outBiomes = new int[PackedSurfacePlane.CELLS];
        PredictionBaselineCache.decompressBiomesZeroAlloc(rleNoise, outBiomes);
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                assertEquals(noise[z * BaselineGrid.PIXELS + x] & 0xFF, outBiomes[BaselineGrid.index(x, z)]);
            }
        }
    }

    private static PredictionBaselineCache.Key key(
        final int x, final long seed, final long generation, final WorldPreset preset, final int netherY
    ) {
        return new PredictionBaselineCache.Key(
            new TileKey(WorldIdentity.singleplayer("baseline-cache"), DimensionId.OVERWORLD,
                "surface!pred", 0, x, 0),
            1L, generation, seed, 28, preset, null, null, netherY,
            PredictionBaselineCache.ENGINE_CPU
        );
    }

    private static PredictionMipCache.Tile tile() {
        return new PredictionMipCache.Tile(
            0, new short[PackedSurfacePlane.CELLS], new byte[PackedSurfacePlane.CELLS],
            new byte[PatchCodec.PIXELS], new byte[PatchCodec.PIXELS], new byte[PatchCodec.MASK_BYTES],
            null, PredictionViewMode.EVERYWHERE, false, 0L, 0L, CorrectionProfile.LEGACY_V1
        );
    }
}
