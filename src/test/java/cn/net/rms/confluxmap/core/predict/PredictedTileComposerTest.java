package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import java.util.Arrays;
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

    @Test
    void predictionDoesNotApplyDiscreteSlopeContourTerm() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 1);
        Arrays.fill(derived.kind, (byte) cn.net.rms.confluxmap.core.model.SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, 80);
        Arrays.fill(derived.fluidDepth, 0);
        final int pixel = 10 * 256 + 10;
        derived.surfaceY[BaselineGrid.index(10, 10)] = 88;
        derived.surfaceY[BaselineGrid.index(9, 11)] = 70;
        final int[] pixels = PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults(), false, 1f);
        final int base = Argb.multiply(PredictionPalette.defaults().landBase, PredictionPalette.defaults().grassTint(1));
        final int expected = ShadingPipeline.applyShade(base, ShadingPipeline.heightShade(88, 80, false));
        assertEquals(expected, pixels[pixel]);
    }

    @Test
    void oceanWaterColorIsUnifiedAcrossBiomeVariants() {
        // warm ocean (id 44) and plain ocean (id 0) carry different live waterColors, but the
        // composer renders one unified ocean tint so adjacent predicted tiles along a coast don't
        // fracture into visibly different hues.
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(derived.kind, (byte) cn.net.rms.confluxmap.core.model.SurfaceKind.WATER.ordinal());
        Arrays.fill(derived.surfaceY, BaselineDeriver.WATER_LEVEL);
        Arrays.fill(derived.fluidDepth, 10);
        grid.biomeId[BaselineGrid.index(5, 5)] = 0;
        grid.biomeId[BaselineGrid.index(6, 6)] = 44;
        final int[] pixels = PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults(), false, 1f);
        assertEquals(pixels[5 * 256 + 5], pixels[6 * 256 + 6]);
    }

    @Test
    void correctedWaterUsesTheSameWaterPipelineAsPredictedWater() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 0);
        Arrays.fill(derived.kind, (byte) SurfaceKind.WATER.ordinal());
        Arrays.fill(derived.surfaceY, BaselineDeriver.WATER_LEVEL);
        Arrays.fill(derived.fluidDepth, 12);
        final int pixel = 20 * 256 + 10;
        final int[] baseline = PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults(), false, 1f);

        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(1L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of(
            new PatchCodec.Sample(pixel, 46, BaselineDeriver.WATER_LEVEL, SurfaceKind.WATER.ordinal(), 12, 12)
        )));
        final int[] corrected = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), false, 1f, corrections, PredictionViewMode.EVERYWHERE, 2
        );

        assertEquals(baseline[pixel], corrected[pixel], "a natural water correction must not become an opaque blue map-color block");
    }

    @Test
    void unknownCorrectionDoesNotPunchATransparentHoleInThePrediction() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 1);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, 70);
        final int pixel = 22 * 256 + 11;
        final int[] baseline = PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults(), false, 1f);

        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(1L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of(
            new PatchCodec.Sample(pixel, 1, 0, SurfaceKind.UNKNOWN.ordinal(), Proto.MAP_COLOR_NONE, 0)
        )));
        final int[] corrected = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), false, 1f, corrections, PredictionViewMode.EVERYWHERE, 2
        );

        assertEquals(baseline[pixel], corrected[pixel], "an incomplete server summary must not erase a valid predicted pixel");
    }
}
