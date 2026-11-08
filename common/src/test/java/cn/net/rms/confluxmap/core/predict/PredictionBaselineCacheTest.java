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
        final PredictionBaselineCache cache = new PredictionBaselineCache(8);
        cache.put(key(0, 1, 0, WorldPreset.DEFAULT, 127), tile());
        assertNotNull(cache.get(key(0, 1, 0, WorldPreset.DEFAULT, 127)));
        assertNull(cache.get(key(0, 2, 0, WorldPreset.DEFAULT, 127)));
        assertNull(cache.get(key(0, 1, 1, WorldPreset.DEFAULT, 127)));
        assertNull(cache.get(key(0, 1, 0, WorldPreset.FLAT, 127)));
        assertNull(cache.get(key(0, 1, 0, WorldPreset.DEFAULT, 63)));
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void sharesOnlyBaselinePlanesAndEvictsLeastRecentlyUsedEntry() {
        final PredictionBaselineCache cache = new PredictionBaselineCache(2);
        final PredictionMipCache.Tile first = tile();
        cache.put(key(0, 1, 0, WorldPreset.DEFAULT, 127), first);
        cache.put(key(1, 1, 0, WorldPreset.DEFAULT, 127), tile());
        final PredictionBaselineCache.Planes planes = cache.get(key(0, 1, 0, WorldPreset.DEFAULT, 127));
        assertSame(first.packedSurfaceKind(), planes.surfaceKind());
        assertSame(first.fluidDepth(), planes.fluidDepth());
        assertSame(first.biomes(), planes.biomes());
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
