package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link PredictedTileComposer} determinism, using the pure-Java {@link PositionBasedFakeSampler}. */
class PredictedTileComposerTest {
    private static int[] composeTile(final long seed, final int lod, final int tileOriginX, final int tileOriginZ) {
        final PositionBasedFakeSampler sampler = new PositionBasedFakeSampler();
        final BaselineGrid grid = LodSampling.sample(sampler, false, lod, tileOriginX, tileOriginZ);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, seed, lod, tileOriginX, tileOriginZ);
        return PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults());
    }

    @Test
    void composingTwiceFromScratchIsBitIdentical() {
        final int[] first = composeTile(555L, 2, 4096, -8192);
        final int[] second = composeTile(555L, 2, 4096, -8192);
        assertArrayEquals(first, second);
    }

    @Test
    void producesAFullyOpaqueTileForOrdinaryTerrain() {
        // The fake sampler never returns a VOID/UNKNOWN kind, and even a translucent water pixel
        // composites to fully opaque here since its synthesized seafloor base is itself opaque
        // (Argb.over of anything over a fully-opaque bottom is always fully opaque).
        final int[] pixels = composeTile(1L, 0, 0, 0);
        for (final int argb : pixels) {
            assertEquals(255, Argb.alpha(argb), "expected fully opaque pixel, got " + Integer.toHexString(argb));
        }
    }

    @Test
    void predictionKeepsDirectionalSlopeDetail() {
        final int flat = composeSlopePixel(80, 80, 0);
        final int higherNeighbor = composeSlopePixel(80, 88, 0);
        final int lowerNeighbor = composeSlopePixel(80, 72, 0);

        assertTrue(Argb.red(higherNeighbor) > Argb.red(flat), "a higher lit-side neighbor should brighten the pixel");
        assertTrue(Argb.red(lowerNeighbor) < Argb.red(flat), "a lower lit-side neighbor should darken the pixel");
    }

    @Test
    void directionalReliefPreservesAbsoluteHeightShadingOnFlatPlateaus() {
        final int lowPlateau = composeSlopePixel(48, 48, 0);
        final int highPlateau = composeSlopePixel(160, 160, 0);
        final PredictionPalette palette = PredictionPalette.defaults();
        final int paletteColor = palette.groundColor(1);

        assertEquals(
            ShadingPipeline.applyShade(
                paletteColor,
                ShadingPipeline.heightShade(48, ShadingPipeline.REFERENCE_HEIGHT, false)
            ),
            lowPlateau
        );
        assertEquals(
            ShadingPipeline.applyShade(
                paletteColor,
                ShadingPipeline.heightShade(160, ShadingPipeline.REFERENCE_HEIGHT, false)
            ),
            highPlateau
        );
    }

    @Test
    void predictionSlopeUsesContinuousMagnitudeInsteadOfAFixedContourStep() {
        final int flat = composeSlopePixel(80, 80, 0);
        final int oneBlockRise = composeSlopePixel(80, 81, 0);
        final int eightBlockRise = composeSlopePixel(80, 88, 0);
        final int base = Argb.multiply(PredictionPalette.defaults().landBase, PredictionPalette.defaults().grassTint(1));
        final int oldDiscreteStep = ShadingPipeline.applyShade(base, ShadingPipeline.slopeShade(80, 81));

        assertNotEquals(flat, oneBlockRise, "small predicted slopes must not disappear");
        assertNotEquals(oldDiscreteStep, oneBlockRise, "a one-block quantization step must not become a full contour band");
        assertTrue(
            Argb.red(eightBlockRise) - Argb.red(flat) > Argb.red(oneBlockRise) - Argb.red(flat),
            "larger slopes should produce stronger shading"
        );
    }

    @Test
    void predictionReliefMakesOrdinarySlopesVisuallyDistinct() {
        final int flat = composeReliefPlanePixel(80, 0, 0);
        final int litSlope = composeReliefPlanePixel(80, 1, 0);
        final int shadedSlope = composeReliefPlanePixel(80, -1, 0);

        assertTrue(
            Argb.red(litSlope) - Argb.red(flat) >= 20,
            "a one-block-per-block lit slope should have visible relief"
        );
        assertTrue(
            Argb.red(flat) - Argb.red(shadedSlope) >= 20,
            "a one-block-per-block shaded slope should have visible relief"
        );
    }

    private static int composeReliefPlanePixel(final int centerHeight, final int risePerBlock, final int lod) {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 1);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, centerHeight);
        final int step = risePerBlock * TileMath.blocksPerPixel(lod);
        derived.surfaceY[BaselineGrid.index(9, 10)] = centerHeight + step;
        derived.surfaceY[BaselineGrid.index(10, 11)] = centerHeight + step;
        derived.surfaceY[BaselineGrid.index(9, 11)] = centerHeight + 2 * step;
        derived.surfaceY[BaselineGrid.index(11, 10)] = centerHeight - step;
        derived.surfaceY[BaselineGrid.index(10, 9)] = centerHeight - step;
        derived.surfaceY[BaselineGrid.index(11, 9)] = centerHeight - 2 * step;
        final int[] pixels = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), null, PredictionViewMode.EVERYWHERE, lod
        );
        return pixels[10 * 256 + 10];
    }

    @Test
    void predictionSlopeNormalizesForBlocksPerPixel() {
        assertEquals(
            composeSlopePixel(80, 81, 0),
            composeSlopePixel(80, 88, 3),
            "the same one-block-per-block slope should shade equally across LODs"
        );
    }

    @Test
    void predictionDoesNotShadeAcrossVoidBoundary() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 1);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, 80);
        derived.kind[BaselineGrid.index(9, 11)] = (byte) SurfaceKind.VOID.ordinal();
        derived.surfaceY[BaselineGrid.index(11, 9)] = 72;

        final int[] pixels = PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults());

        assertEquals(composeSlopePixel(80, 80, 0), pixels[10 * 256 + 10]);
    }

    private static int composeSlopePixel(final int centerHeight, final int neighborHeight, final int lod) {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 1);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, 80);
        Arrays.fill(derived.fluidDepth, 0);
        final int pixel = 10 * 256 + 10;
        derived.surfaceY[BaselineGrid.index(10, 10)] = centerHeight;
        derived.surfaceY[BaselineGrid.index(9, 11)] = neighborHeight;
        derived.surfaceY[BaselineGrid.index(11, 9)] = centerHeight - (neighborHeight - centerHeight);
        final int[] pixels = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), null, PredictionViewMode.EVERYWHERE, lod
        );
        return pixels[pixel];
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
        final int[] pixels = PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults());
        assertEquals(pixels[5 * 256 + 5], pixels[6 * 256 + 6]);
    }

    @Test
    void endLandUsesUntintedEndStoneInsteadOfTheGrassSurfaceModel() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 9);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, ShadingPipeline.REFERENCE_HEIGHT);
        final PredictionPalette palette = PredictionPalette.fromSamples(Map.of(
            9,
            new int[] {0xFF00FF00, 0xFF00FF00, 0xFF0000FF}
        ));

        final int[] pixels = PredictedTileComposer.compose(derived, grid, palette);

        assertEquals(MapColorTable.argb(2), pixels[10 * 256 + 10]);
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
        final int[] baseline = PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults());

        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(1L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of(
            new PatchCodec.Sample(pixel, 46, BaselineDeriver.WATER_LEVEL, SurfaceKind.WATER.ordinal(), 12, 12)
        )));
        final int[] corrected = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), corrections, PredictionViewMode.EVERYWHERE, 2
        );

        assertEquals(baseline[pixel], corrected[pixel], "a natural water correction must not become an opaque blue map-color block");
    }

    @Test
    void correctedFoliageKeepsTheContinuousPredictedCanopyPlane() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 7);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, 64);
        final int pixel = 30 * 256 + 20;
        final int[] baseline = PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults());

        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(1L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of(
            new PatchCodec.Sample(pixel, 7, 68, SurfaceKind.FOLIAGE.ordinal(), 7, 0)
        )));
        final int[] corrected = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), corrections, PredictionViewMode.EVERYWHERE, 0
        );

        assertEquals(baseline[pixel], corrected[pixel], "natural foliage corrections must not create chunk-edge colour patches");
    }

    @Test
    void correctedStoneReplacesAFalsePredictedCanopy() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 35);
        Arrays.fill(derived.kind, (byte) SurfaceKind.FOLIAGE.ordinal());
        Arrays.fill(derived.surfaceY, 73);
        final int pixel = 31 * 256 + 21;

        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(1L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of(
            new PatchCodec.Sample(pixel, 35, 79, SurfaceKind.LAND.ordinal(), 11, 0)
        )));
        final int[] corrected = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), corrections, PredictionViewMode.EVERYWHERE, 0
        );

        assertEquals(
            ShadingPipeline.applyShade(
                MapColorTable.argb(11),
                ShadingPipeline.heightShade(79, ShadingPipeline.REFERENCE_HEIGHT, false)
            ),
            corrected[pixel],
            "player-built stone must remain visible through predicted canopy"
        );
    }

    @Test
    void unknownCorrectionDoesNotPunchATransparentHoleInThePrediction() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 1);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, 70);
        final int pixel = 22 * 256 + 11;
        final int[] baseline = PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults());

        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(1L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of(
            new PatchCodec.Sample(pixel, 1, 0, SurfaceKind.UNKNOWN.ordinal(), Proto.MAP_COLOR_NONE, 0)
        )));
        final int[] corrected = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), corrections, PredictionViewMode.EVERYWHERE, 2
        );

        assertEquals(baseline[pixel], corrected[pixel], "an incomplete server summary must not erase a valid predicted pixel");
    }
}
