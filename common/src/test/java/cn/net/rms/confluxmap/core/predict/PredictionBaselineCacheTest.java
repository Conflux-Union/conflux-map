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

        final PredictionBaselineCache.Key baseKey = fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat1, q1, 127);
        cache.put(baseKey, tile());

        assertNotNull(cache.get(baseKey));
        // QuadrantLayout value equality: equal new instance must hit
        assertNotNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat1, q1Clone, 127)));

        // Distinct dimension checks
        assertNull(cache.get(fullKey(0, 2, 0, 28, WorldPreset.DEFAULT, flat1, q1, 127)), "seed mismatch");
        assertNull(cache.get(fullKey(0, 1, 1, 28, WorldPreset.DEFAULT, flat1, q1, 127)), "generation mismatch");
        assertNull(cache.get(fullKey(0, 1, 0, 29, WorldPreset.DEFAULT, flat1, q1, 127)), "version mismatch");
        assertNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.FLAT, flat1, q1, 127)), "preset mismatch");
        assertNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat2, q1, 127)), "flat mismatch");
        assertNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat1, q2, 127)), "quadrant mismatch");
        assertNull(cache.get(fullKey(0, 1, 0, 28, WorldPreset.DEFAULT, flat1, q1, 63)), "netherY mismatch");
        assertNull(cache.get(fullKey(1, 1, 0, 28, WorldPreset.DEFAULT, flat1, q1, 127)), "tile coord mismatch");

        cache.clear();
        assertEquals(0, cache.size());
    }

    private static PredictionBaselineCache.Key fullKey(
        final int x, final long seed, final long generation, final int version,
        final WorldPreset preset, final FlatBaseline flat, final QuadrantLayout quadrants,
        final int netherY
    ) {
        return new PredictionBaselineCache.Key(
            new TileKey(WorldIdentity.singleplayer("baseline-cache"), DimensionId.OVERWORLD,
                "surface!pred", 0, x, 0),
            1L, generation, seed, version, preset, flat, quadrants, netherY
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

    private static PredictionBaselineCache.Key key(
        final int x, final long seed, final long generation, final WorldPreset preset, final int netherY
    ) {
        return new PredictionBaselineCache.Key(
            new TileKey(WorldIdentity.singleplayer("baseline-cache"), DimensionId.OVERWORLD,
                "surface!pred", 0, x, 0),
            1L, generation, seed, 28, preset, null, null, netherY
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
