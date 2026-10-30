package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import org.junit.jupiter.api.Test;

class PredictionMipCacheTest {
    @Test
    void aggregateUsesTheMostConservativeCorrectionProfile() {
        final PredictionMipCache.Tile[] children = new PredictionMipCache.Tile[] {
            tile(CorrectionProfile.SOURCE_LIGHT_V2),
            tile(CorrectionProfile.SOURCE_LIGHT_V2),
            tile(CorrectionProfile.LEGACY_V1),
            tile(CorrectionProfile.SOURCE_LIGHT_V2)
        };

        final PredictionMipCache.Tile aggregate = PredictionMipCache.aggregate(
            children, PredictionViewMode.EVERYWHERE
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

    private static PredictionMipCache.Tile tile(final CorrectionProfile profile) {
        final int pixels = BaselineGrid.PIXELS * BaselineGrid.PIXELS;
        return new PredictionMipCache.Tile(
            new int[pixels],
            new byte[pixels],
            new int[pixels],
            new byte[PatchCodec.MASK_BYTES],
            new long[pixels],
            new byte[pixels],
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
