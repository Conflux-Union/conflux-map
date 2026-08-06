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
            PredictionViewMode.EVERYWHERE,
            false,
            0L,
            0L,
            profile
        );
    }
}
