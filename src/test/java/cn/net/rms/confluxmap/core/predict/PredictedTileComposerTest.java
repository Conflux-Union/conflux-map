package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.net.rms.confluxmap.core.util.Argb;
import org.junit.jupiter.api.Test;

/** {@link PredictedTileComposer} determinism, using the pure-Java {@link PositionBasedFakeSampler}. */
class PredictedTileComposerTest {
    private static int[] composeTile(final long seed, final int lod, final int tileOriginX, final int tileOriginZ, final boolean daylight) {
        final PositionBasedFakeSampler sampler = new PositionBasedFakeSampler();
        final BaselineGrid grid = LodSampling.sample(sampler, false, lod, tileOriginX, tileOriginZ);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, seed, lod, tileOriginX, tileOriginZ);
        return PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults(), daylight, 0.6f);
    }

    @Test
    void composingTwiceFromScratchIsBitIdentical() {
        final int[] first = composeTile(555L, 2, 4096, -8192, true);
        final int[] second = composeTile(555L, 2, 4096, -8192, true);
        assertArrayEquals(first, second);
    }

    @Test
    void producesAFullyOpaqueTileForOrdinaryTerrain() {
        // The fake sampler never returns a VOID/UNKNOWN kind, and even a translucent water pixel
        // composites to fully opaque here since its synthesized seafloor base is itself opaque
        // (Argb.over of anything over a fully-opaque bottom is always fully opaque).
        final int[] pixels = composeTile(1L, 0, 0, 0, false);
        for (final int argb : pixels) {
            assertEquals(255, Argb.alpha(argb), "expected fully opaque pixel, got " + Integer.toHexString(argb));
        }
    }

    @Test
    void daylightFactorActuallyChangesTheOutputAtNight() {
        final int[] fullDaylight = composeTile(9L, 2, 0, 0, false);
        final PositionBasedFakeSampler sampler = new PositionBasedFakeSampler();
        final BaselineGrid grid = LodSampling.sample(sampler, false, 2, 0, 0);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, 9L, 2, 0, 0);
        final int[] night = PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults(), true, 0.0f);
        assertNotEquals(fullDaylight[0], night[0], "night-time daylight factor should darken the pixel");
    }
}
