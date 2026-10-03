package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.color.XaeroMapStyle;
import cn.net.rms.confluxmap.core.color.LightTint;
import cn.net.rms.confluxmap.core.color.MaterialDetailProfile;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link PredictedTileComposer} determinism, using the pure-Java {@link PositionBasedFakeSampler}. */
class PredictedTileComposerTest {
    @Test
    void xaeroStyleUsesTheSameFlatTerrainRendererForPrediction() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 0);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, 63);
        final PredictionPalette palette = PredictionPalette.defaults();

        final int[] pixels = PredictedTileComposer.compose(
            derived, grid, palette, null, PredictionViewMode.EVERYWHERE, 0,
            Proto.MAP_COLOR_NONE, derived, grid, Proto.MAP_COLOR_NONE,
            true, 0xFFFFFFFF, null,
            MapColorStyle.XAERO, XaeroMapStyle.Shadow.OVERWORLD
        );

        assertEquals(
            XaeroMapStyle.applyTerrain(
                palette.groundColor(0), 63, 63, 63, 1, true, XaeroMapStyle.Shadow.OVERWORLD
            ),
            pixels[20 * 256 + 20]
        );
    }

    /** Vanilla map colour GRASS - the id a grass block reports in every biome. */
    private static final int GRASS_MAP_COLOR = 1;

    @Test
    void residualsUseTheSourceBaselineButKeepTheCurrentPredictionForUnknownSamples() {
        final BaselineGrid currentGrid = flatGrid(1);
        final DerivedGrid currentDerived = flatDerived(80);
        final BaselineGrid legacyGrid = flatGrid(2);
        final DerivedGrid legacyDerived = flatDerived(80);
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        Arrays.fill(evaluated, (byte) 0xFF);
        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(evaluated, java.util.List.of(
                new PatchCodec.Sample(
                    0, 0, 0, SurfaceKind.UNKNOWN.ordinal(), Proto.MAP_COLOR_NONE, 0
                )
            )),
            Proto.PATCH_MODE_RESIDUAL,
            "legacy-v4",
            1_000L
        );

        final int[] composed = PredictedTileComposer.compose(
            currentDerived,
            currentGrid,
            PredictionPalette.defaults(),
            corrections,
            PredictionViewMode.EVERYWHERE,
            0,
            Proto.MAP_COLOR_NONE,
            legacyDerived,
            legacyGrid,
            Proto.MAP_COLOR_NONE
        );
        final int[] expected = PredictedTileComposer.compose(
            legacyDerived, legacyGrid, PredictionPalette.defaults()
        );
        expected[0] = PredictedTileComposer.compose(
            currentDerived, currentGrid, PredictionPalette.defaults()
        )[0];

        assertArrayEquals(expected, composed);
    }

    private static BaselineGrid flatGrid(final int biome) {
        final BaselineGrid grid = new BaselineGrid();
        Arrays.fill(grid.biomeId, biome);
        return grid;
    }

    private static DerivedGrid flatDerived(final int y) {
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(derived.surfaceY, y);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        return derived;
    }

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
    void correctedMaterialUsesTheClientResourceSampleInsteadOfMapColor() {
        final BaselineGrid grid = flatGrid(1);
        final DerivedGrid derived = flatDerived(ShadingPipeline.REFERENCE_HEIGHT);
        final int pixel = 10 * 256 + 10;
        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(java.util.List.of(new PatchCodec.Sample(
                pixel, 1, ShadingPipeline.REFERENCE_HEIGHT, SurfaceKind.LAND.ordinal(),
                4, 0, 255, "minecraft:glowstone", ""
            ))),
            Proto.PATCH_MODE_ABSOLUTE,
            "",
            1_000L
        );
        final SyncedMaterialPalette materials = new SyncedMaterialPalette();
        final int sampledGlowstone = 0xFFFFD95A;
        materials.put("minecraft:glowstone", new SyncedMaterialPalette.Sample(
            sampledGlowstone,
            MaterialDetailProfile.flat(),
            SyncedMaterialPalette.Tint.NONE,
            0xFFFFFFFF,
            1
        ));

        final int[] composed = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), corrections,
            PredictionViewMode.EVERYWHERE, 0, Proto.MAP_COLOR_NONE,
            derived, grid, Proto.MAP_COLOR_NONE, true, 0xFFFFFFFF, materials
        );

        assertEquals(sampledGlowstone, composed[pixel]);
        assertNotEquals(MapColorTable.argb(4), composed[pixel]);
    }

    @Test
    void correctedTranslucentSurfaceMaterialKeepsItsOwnAlpha() {
        final BaselineGrid grid = flatGrid(1);
        final DerivedGrid derived = flatDerived(ShadingPipeline.REFERENCE_HEIGHT);
        final int pixel = 10 * 256 + 10;
        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(java.util.List.of(new PatchCodec.Sample(
                pixel, 1, ShadingPipeline.REFERENCE_HEIGHT, SurfaceKind.LAND.ordinal(),
                8, 0, 255, "minecraft:white_stained_glass", ""
            ))),
            Proto.PATCH_MODE_ABSOLUTE,
            "",
            1_000L
        );
        final SyncedMaterialPalette materials = new SyncedMaterialPalette();
        // The live sampler averages white stained glass to white RGB at alpha 117. A glass
        // floor promoted to the visible surface keeps that translucency - it tints the map
        // background instead of painting an opaque plate - like the authoritative capture.
        materials.put("minecraft:white_stained_glass", new SyncedMaterialPalette.Sample(
            0x75FFFFFF,
            MaterialDetailProfile.flat(),
            SyncedMaterialPalette.Tint.NONE,
            0xFFFFFFFF,
            1
        ));

        final int[] composed = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), corrections,
            PredictionViewMode.EVERYWHERE, 0, Proto.MAP_COLOR_NONE,
            derived, grid, Proto.MAP_COLOR_NONE, true, 0xFFFFFFFF, materials
        );

        assertEquals(0x75FFFFFF, composed[pixel]);
    }

    @Test
    void flatTopMaterialPaintsItsOwnSampleWithoutACorrection() {
        // Origin (-16, -16) puts local (5, 5) at world (-11, -11) in the -- quadrant and local
        // (20, 20) at world (4, 4) in the ++ quadrant, so one tile spans both.
        final BaselineGrid grid = new BaselineGrid(0, -16, -16);
        Arrays.fill(grid.biomeId, 1);
        final DerivedGrid derived = flatDerived(ShadingPipeline.REFERENCE_HEIGHT);
        final int materialPixel = 20 * 256 + 20;
        final int tablePixel = 5 * 256 + 5;
        final int[] mapColorOverride = new int[256 * 256];
        Arrays.fill(mapColorOverride, Proto.MAP_COLOR_NONE);
        mapColorOverride[materialPixel] = 8;
        mapColorOverride[tablePixel] = 8;
        final SyncedMaterialPalette materials = new SyncedMaterialPalette();
        materials.put("minecraft:white_stained_glass", new SyncedMaterialPalette.Sample(
            0x75FFFFFF,
            MaterialDetailProfile.flat(),
            SyncedMaterialPalette.Tint.NONE,
            0xFFFFFFFF,
            1
        ));
        final String[] flatTopMaterials = new String[] {
            "minecraft:white_stained_glass", null, null, null
        };

        final int[] composed = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), null,
            PredictionViewMode.EVERYWHERE, 0, Proto.MAP_COLOR_NONE,
            derived, grid, Proto.MAP_COLOR_NONE, true, 0xFFFFFFFF, materials,
            MapColorStyle.CONFLUX, XaeroMapStyle.Shadow.OVERWORLD,
            mapColorOverride, flatTopMaterials
        );

        // The ++ quadrant paints the glass's own translucent sample, not the opaque map colour.
        assertEquals(0x75FFFFFF, composed[materialPixel]);
        // A quadrant with no declared material still paints the opaque map colour.
        assertEquals(0xFFFFFFFF, composed[tablePixel]);
    }

    @Test
    void overlayMaterialCompositesOverTheCorrectedSurface() {
        final BaselineGrid grid = flatGrid(1);
        final DerivedGrid derived = flatDerived(ShadingPipeline.REFERENCE_HEIGHT);
        final int tinted = 10 * 256 + 10;
        final int unresolved = 20 * 256 + 20;
        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(java.util.List.of(
                new PatchCodec.Sample(
                    tinted, 1, ShadingPipeline.REFERENCE_HEIGHT, SurfaceKind.LAND.ordinal(),
                    4, 0, 255, "minecraft:glowstone", "", "minecraft:black_stained_glass"
                ),
                new PatchCodec.Sample(
                    unresolved, 1, ShadingPipeline.REFERENCE_HEIGHT, SurfaceKind.LAND.ordinal(),
                    4, 0, 255, "minecraft:glowstone", "", "minecraft:wither_rose"
                )
            )),
            Proto.PATCH_MODE_ABSOLUTE,
            "",
            1_000L
        );
        final SyncedMaterialPalette materials = new SyncedMaterialPalette();
        final int sampledGlowstone = 0xFFFFD95A;
        materials.put("minecraft:glowstone", new SyncedMaterialPalette.Sample(
            sampledGlowstone,
            MaterialDetailProfile.flat(),
            SyncedMaterialPalette.Tint.NONE,
            0xFFFFFFFF,
            1
        ));
        final int sampledGlass = 0xAA202020;
        materials.put("minecraft:black_stained_glass", new SyncedMaterialPalette.Sample(
            sampledGlass,
            MaterialDetailProfile.flat(),
            SyncedMaterialPalette.Tint.NONE,
            0xFFFFFFFF,
            2
        ));

        final int[] composed = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), corrections,
            PredictionViewMode.EVERYWHERE, 0, Proto.MAP_COLOR_NONE,
            derived, grid, Proto.MAP_COLOR_NONE, true, 0xFFFFFFFF, materials
        );

        assertEquals(Argb.over(sampledGlass, sampledGlowstone), composed[tinted]);
        assertEquals(sampledGlowstone, composed[unresolved]);
    }

    @Test
    void xaeroStyleHidesGlassOverlaysButKeepsDecorations() {
        final BaselineGrid grid = flatGrid(1);
        final DerivedGrid derived = flatDerived(ShadingPipeline.REFERENCE_HEIGHT);
        final int glassPixel = 10 * 256 + 10;
        final int rosePixel = 20 * 256 + 20;
        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(java.util.List.of(
                new PatchCodec.Sample(
                    glassPixel, 1, ShadingPipeline.REFERENCE_HEIGHT, SurfaceKind.LAND.ordinal(),
                    4, 0, 255, "minecraft:glowstone", "", "minecraft:black_stained_glass"
                ),
                new PatchCodec.Sample(
                    rosePixel, 1, ShadingPipeline.REFERENCE_HEIGHT, SurfaceKind.LAND.ordinal(),
                    4, 0, 255, "minecraft:glowstone", "", "minecraft:wither_rose"
                )
            )),
            Proto.PATCH_MODE_ABSOLUTE,
            "",
            1_000L
        );
        final SyncedMaterialPalette materials = new SyncedMaterialPalette();
        final int sampledGlowstone = 0xFFFFD95A;
        materials.put("minecraft:glowstone", new SyncedMaterialPalette.Sample(
            sampledGlowstone,
            MaterialDetailProfile.flat(),
            SyncedMaterialPalette.Tint.NONE,
            0xFFFFFFFF,
            1
        ));
        final int sampledGlass = 0xAA202020;
        materials.put("minecraft:black_stained_glass", new SyncedMaterialPalette.Sample(
            sampledGlass,
            MaterialDetailProfile.flat(),
            SyncedMaterialPalette.Tint.NONE,
            0xFFFFFFFF,
            2
        ));
        final int sampledRose = 0xFF2B2B2B;
        materials.put("minecraft:wither_rose", new SyncedMaterialPalette.Sample(
            sampledRose,
            MaterialDetailProfile.flat(),
            SyncedMaterialPalette.Tint.NONE,
            0xFFFFFFFF,
            3
        ));

        final int[] composed = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), corrections,
            PredictionViewMode.EVERYWHERE, 0, Proto.MAP_COLOR_NONE,
            derived, grid, Proto.MAP_COLOR_NONE, true, 0xFFFFFFFF, materials,
            MapColorStyle.XAERO, XaeroMapStyle.Shadow.OVERWORLD
        );

        // Xaero renders flat terrain through its own shading, so compare against the same
        // tile recomposed without the overlays instead of raw palette colours.
        final CorrectionTile withoutOverlays = new CorrectionTile();
        withoutOverlays.applyPatch(
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(java.util.List.of(
                new PatchCodec.Sample(
                    glassPixel, 1, ShadingPipeline.REFERENCE_HEIGHT, SurfaceKind.LAND.ordinal(),
                    4, 0, 255, "minecraft:glowstone", ""
                ),
                new PatchCodec.Sample(
                    rosePixel, 1, ShadingPipeline.REFERENCE_HEIGHT, SurfaceKind.LAND.ordinal(),
                    4, 0, 255, "minecraft:glowstone", ""
                )
            )),
            Proto.PATCH_MODE_ABSOLUTE,
            "",
            1_000L
        );
        final int[] bare = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), withoutOverlays,
            PredictionViewMode.EVERYWHERE, 0, Proto.MAP_COLOR_NONE,
            derived, grid, Proto.MAP_COLOR_NONE, true, 0xFFFFFFFF, materials,
            MapColorStyle.XAERO, XaeroMapStyle.Shadow.OVERWORLD
        );

        assertEquals(bare[glassPixel], composed[glassPixel]);
        assertNotEquals(bare[rosePixel], composed[rosePixel]);
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
                ShadingPipeline.detailedHeightShade(48, ShadingPipeline.REFERENCE_HEIGHT)
            ),
            lowPlateau
        );
        assertEquals(
            ShadingPipeline.applyShade(
                paletteColor,
                ShadingPipeline.detailedHeightShade(160, ShadingPipeline.REFERENCE_HEIGHT)
            ),
            highPlateau
        );
    }

    @Test
    void netherRoofUsesTheSampledBedrockMaterialWithoutHeightShading() {
        final int netherWastes = CubiomesBiomeIds.NETHER_WASTES;
        final BaselineGrid grid = flatGrid(netherWastes);
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(derived.surfaceY, PredictionDimensions.NETHER_ROOF_Y);
        Arrays.fill(derived.kind, (byte) SurfaceKind.BEDROCK_CEILING.ordinal());
        final int sampledBedrock = 0xFF383838;
        final PredictionPalette palette = PredictionPalette.fromSamples(
            Map.of(),
            Map.of(SurfaceKind.BEDROCK_CEILING, MaterialDetailProfile.flat()),
            Map.of(),
            Map.of(SurfaceKind.BEDROCK_CEILING, sampledBedrock)
        );

        final int[] pixels = PredictedTileComposer.compose(
            derived, grid, palette, null, PredictionViewMode.EVERYWHERE, 0,
            PredictionDimensions.NETHER_ROOF_MAP_COLOR_ID, derived, grid,
            PredictionDimensions.NETHER_ROOF_MAP_COLOR_ID, false,
            LightTint.multiplier(0, 0, true)
        );

        assertEquals(
            Argb.multiply(sampledBedrock, LightTint.multiplier(0, 0, true)),
            pixels[10 * 256 + 10],
            "the predicted roof must apply the unlit Nether ambient tint used by captured tiles"
        );
    }

    @Test
    void xaeroNetherRoofBakesTheUnlitAmbientTintLikeCapturedTiles() {
        final BaselineGrid grid = flatGrid(CubiomesBiomeIds.NETHER_WASTES);
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(derived.surfaceY, PredictionDimensions.NETHER_ROOF_Y);
        Arrays.fill(derived.kind, (byte) SurfaceKind.BEDROCK_CEILING.ordinal());
        final int sampledBedrock = 0xFF383838;
        final PredictionPalette palette = PredictionPalette.fromSamples(
            Map.of(),
            Map.of(SurfaceKind.BEDROCK_CEILING, MaterialDetailProfile.flat()),
            Map.of(),
            Map.of(SurfaceKind.BEDROCK_CEILING, sampledBedrock)
        );

        final int[] pixels = PredictedTileComposer.compose(
            derived, grid, palette, null, PredictionViewMode.EVERYWHERE, 0,
            PredictionDimensions.NETHER_ROOF_MAP_COLOR_ID, derived, grid,
            PredictionDimensions.NETHER_ROOF_MAP_COLOR_ID, false,
            LightTint.multiplier(0, 0, true), null,
            MapColorStyle.XAERO, XaeroMapStyle.Shadow.NETHER
        );

        assertEquals(
            XaeroMapStyle.applyTerrain(
                Argb.multiply(sampledBedrock, LightTint.multiplier(0, 0, true)),
                PredictionDimensions.NETHER_ROOF_Y, PredictionDimensions.NETHER_ROOF_Y,
                PredictionDimensions.NETHER_ROOF_Y, 1, true, XaeroMapStyle.Shadow.NETHER
            ),
            pixels[10 * 256 + 10],
            "the predicted Xaero roof must carry the unlit Nether ambient bake captured roof tiles use"
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

    @Test
    void predictionUsesTheResourceMaterialProfileAcrossFlatTerrain() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 1);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, ShadingPipeline.REFERENCE_HEIGHT);
        final MaterialDetailProfile detail = MaterialDetailProfile.fromLuminance(
            new int[] {92, 108, 94, 106, 96, 104, 98, 102, 102, 98, 104, 96, 106, 94, 108, 92},
            0.08
        );
        final PredictionPalette palette = PredictionPalette.fromSamples(
            Map.of(), Map.of(SurfaceKind.LAND, detail)
        );

        final int[] pixels = PredictedTileComposer.compose(derived, grid, palette);
        final HashSet<Integer> colors = new HashSet<>();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                colors.add(pixels[z * 256 + x]);
            }
        }

        assertTrue(colors.size() > 2, "a resource-derived profile should break up a flat predicted colour");
    }

    @Test
    void endLandUsesItsDedicatedEndStoneMaterialProfile() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 9);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, ShadingPipeline.REFERENCE_HEIGHT);
        final MaterialDetailProfile endStone = MaterialDetailProfile.fromLuminance(
            new int[] {78, 122, 82, 118, 86, 114, 90, 110, 110, 90, 114, 86, 118, 82, 122, 78},
            0.12
        );
        final PredictionPalette palette = PredictionPalette.fromSamples(
            Map.of(),
            Map.of(SurfaceKind.LAND, MaterialDetailProfile.flat()),
            Map.of(9, endStone)
        );

        final int[] pixels = PredictedTileComposer.compose(derived, grid, palette);
        final HashSet<Integer> colors = new HashSet<>();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                colors.add(pixels[z * 256 + x]);
            }
        }

        assertTrue(colors.size() > 2, "End land should retain end-stone texture detail");
    }

    @Test
    void lod0ReliefDoesNotBleedAcrossABlockEdge() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, 1);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, 80);
        for (int z = -BaselineGrid.MARGIN; z < BaselineGrid.PIXELS + BaselineGrid.MARGIN; z++) {
            for (int x = 128; x < BaselineGrid.PIXELS + BaselineGrid.MARGIN; x++) {
                derived.surfaceY[BaselineGrid.index(x, z)] = 96;
            }
        }

        final int[] pixels = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), null, PredictionViewMode.EVERYWHERE, 0
        );
        final int z = 64;
        final int flatLow = pixels[z * BaselineGrid.PIXELS + 126];
        final int lowEdge = pixels[z * BaselineGrid.PIXELS + 127];
        final int highEdge = pixels[z * BaselineGrid.PIXELS + 128];

        assertEquals(flatLow, lowEdge, "LOD0 relief must not soften the block before a height boundary");
        assertNotEquals(lowEdge, highEdge, "the height boundary itself must remain visible");
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
            composeReliefPlanePixel(80, 1, 0),
            composeReliefPlanePixel(80, 1, 3),
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
    void swampWaterUsesItsBiomeTint() {
        final BaselineGrid grid = new BaselineGrid();
        Arrays.fill(grid.biomeId, 1);
        Arrays.fill(grid.terrainY, BaselineDeriver.WATER_LEVEL - 4);
        Arrays.fill(grid.fluidY, BaselineDeriver.WATER_LEVEL);
        Arrays.fill(grid.baseSurfaceY, BaselineDeriver.WATER_LEVEL);
        Arrays.fill(grid.surfaceFlags, BaselineGrid.SURFACE_FLUID);
        grid.biomeId[BaselineGrid.index(6, 6)] = 6;
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        final PredictionPalette palette = PredictionPalette.defaults();

        assertNotEquals(palette.waterTint(1), palette.waterTint(6), "test biomes must have different water tints");
        assertEquals(SurfaceKind.WATER.ordinal(), derived.kind[BaselineGrid.index(5, 5)]);
        assertEquals(SurfaceKind.WATER.ordinal(), derived.kind[BaselineGrid.index(6, 6)]);

        final int[] pixels = PredictedTileComposer.compose(derived, grid, palette);

        assertNotEquals(
            pixels[5 * 256 + 5],
            pixels[6 * 256 + 6],
            "swamp water must use the swamp biome tint instead of the unified ocean tint"
        );
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
    void predictedWaterShowsReliefFromTheFloorBelowAFlatSurface() {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid flat = new DerivedGrid();
        Arrays.fill(grid.biomeId, 0);
        Arrays.fill(flat.kind, (byte) SurfaceKind.WATER.ordinal());
        Arrays.fill(flat.surfaceY, BaselineDeriver.WATER_LEVEL);
        Arrays.fill(flat.fluidDepth, 10);
        final int center = BaselineGrid.index(10, 10);
        final int flatPixel = PredictedTileComposer.compose(flat, grid, PredictionPalette.defaults())[10 * 256 + 10];

        final DerivedGrid sloped = new DerivedGrid();
        System.arraycopy(flat.kind, 0, sloped.kind, 0, flat.kind.length);
        System.arraycopy(flat.surfaceY, 0, sloped.surfaceY, 0, flat.surfaceY.length);
        System.arraycopy(flat.fluidDepth, 0, sloped.fluidDepth, 0, flat.fluidDepth.length);
        sloped.fluidDepth[BaselineGrid.index(9, 10)] = 8;
        sloped.fluidDepth[BaselineGrid.index(10, 11)] = 8;
        sloped.fluidDepth[BaselineGrid.index(9, 11)] = 8;
        sloped.fluidDepth[BaselineGrid.index(11, 10)] = 12;
        sloped.fluidDepth[BaselineGrid.index(10, 9)] = 12;
        sloped.fluidDepth[BaselineGrid.index(11, 9)] = 12;

        final int slopedPixel = PredictedTileComposer.compose(
            sloped, grid, PredictionPalette.defaults()
        )[10 * 256 + 10];

        assertEquals(10, sloped.fluidDepth[center]);
        assertNotEquals(flatPixel, slopedPixel, "floor shape should remain visible through a flat water surface");
    }

    @Test
    void authoritativeFoliageCorrectionReplacesThePredictedGroundPixel() {
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

        assertNotEquals(
            baseline[pixel],
            corrected[pixel],
            "an authoritative residual must not be discarded by a visual canopy tolerance"
        );
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
            new PatchCodec.Sample(pixel, 35, 79, SurfaceKind.LAND.ordinal(), 11, 0),
            new PatchCodec.Sample(31 * 256 + 20, 35, 79, SurfaceKind.LAND.ordinal(), 11, 0),
            new PatchCodec.Sample(32 * 256 + 21, 35, 79, SurfaceKind.LAND.ordinal(), 11, 0),
            new PatchCodec.Sample(32 * 256 + 20, 35, 79, SurfaceKind.LAND.ordinal(), 11, 0),
            new PatchCodec.Sample(31 * 256 + 22, 35, 79, SurfaceKind.LAND.ordinal(), 11, 0),
            new PatchCodec.Sample(30 * 256 + 21, 35, 79, SurfaceKind.LAND.ordinal(), 11, 0),
            new PatchCodec.Sample(30 * 256 + 22, 35, 79, SurfaceKind.LAND.ordinal(), 11, 0)
        )));
        final int[] corrected = PredictedTileComposer.compose(
            derived, grid, PredictionPalette.defaults(), corrections, PredictionViewMode.EVERYWHERE, 0
        );

        assertEquals(
            ShadingPipeline.applyShade(
                MapColorTable.argb(11),
                ShadingPipeline.detailedHeightShade(79, ShadingPipeline.REFERENCE_HEIGHT)
            ),
            corrected[pixel],
            "player-built stone must remain visible through predicted canopy"
        );
    }

    @Test
    void superflatGrassUsesTheBiomePaletteInsteadOfTheFlatMapColor() {
        // A classic-flat overworld declares map color GRASS for every pixel. Painting that
        // literally put vanilla's one fixed green (#7FB238) next to the captured map's
        // biome-tinted grass, so the underlay and the explored map never lined up.
        final int plains = 1;
        final PredictionPalette palette = PredictionPalette.fromSamples(Map.of(
            plains, new int[] {0xFF91BD59, 0xFF77AB2F, 0xFF3F76E4}
        ));
        final int surfaceY = -61;
        final int[] pixels = composeUniformFlat(plains, surfaceY, GRASS_MAP_COLOR, palette);

        assertEquals(
            ShadingPipeline.applyShade(
                palette.groundColor(plains),
                ShadingPipeline.detailedHeightShade(surfaceY, ShadingPipeline.REFERENCE_HEIGHT)
            ),
            pixels[10 * 256 + 10]
        );
    }

    @Test
    void superflatStoneStillPaintsItsLiteralMapColor() {
        final int plains = 1;
        final int surfaceY = -61;
        final int stoneMapColor = 11;
        final int[] pixels = composeUniformFlat(plains, surfaceY, stoneMapColor, PredictionPalette.defaults());

        assertEquals(
            ShadingPipeline.applyShade(
                MapColorTable.argb(stoneMapColor),
                ShadingPipeline.detailedHeightShade(surfaceY, ShadingPipeline.REFERENCE_HEIGHT)
            ),
            pixels[10 * 256 + 10],
            "a non-tinted block's map color remains the best stand-in for it"
        );
    }

    @Test
    void aGrassCorrectionMatchesThePredictedGroundAroundIt() {
        final int plains = 1;
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, plains);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, 70);
        final int pixel = 24 * 256 + 12;
        final PredictionPalette palette = PredictionPalette.fromSamples(Map.of(
            plains, new int[] {0xFF91BD59, 0xFF77AB2F, 0xFF3F76E4}
        ));
        final int[] baseline = PredictedTileComposer.compose(derived, grid, palette);

        final CorrectionTile corrections = new CorrectionTile();
        corrections.applyPatch(1L, new byte[Proto.PATCH_PRESENCE_BYTES], new PatchCodec.Patch(java.util.List.of(
            new PatchCodec.Sample(pixel, plains, 70, SurfaceKind.LAND.ordinal(), GRASS_MAP_COLOR, 0)
        )));
        final int[] corrected = PredictedTileComposer.compose(
            derived, grid, palette, corrections, PredictionViewMode.EVERYWHERE, 0
        );

        assertEquals(baseline[pixel], corrected[pixel], "a grass correction must not become a flat vanilla-green speck");
    }

    /** One uniform superflat tile: every column the same biome, height, kind and declared map color. */
    private static int[] composeUniformFlat(
        final int biomeId,
        final int surfaceY,
        final int mapColorId,
        final PredictionPalette palette
    ) {
        final BaselineGrid grid = new BaselineGrid();
        final DerivedGrid derived = new DerivedGrid();
        Arrays.fill(grid.biomeId, biomeId);
        Arrays.fill(grid.terrainY, surfaceY);
        Arrays.fill(derived.kind, (byte) SurfaceKind.LAND.ordinal());
        Arrays.fill(derived.surfaceY, surfaceY);
        return PredictedTileComposer.compose(
            derived, grid, palette, null, PredictionViewMode.EVERYWHERE, 0, mapColorId
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

    private static int[] composeRiverStripeTile(final int lod) {
        final RiverStripeFakeSampler sampler = new RiverStripeFakeSampler(1 << lod);
        final BaselineGrid grid = LodSampling.sample(sampler, false, lod, 0, 0);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, 7L, lod, 0, 0);
        return PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults());
    }

    /**
     * The whole point of biome supersampling: a river that every pixel centre misses must still
     * tint the output. Before supersampling this tile came out uniformly dry, which is what made
     * a meandering river collapse into a broken straight line when zoomed out.
     */
    @Test
    void aRiverBetweenPixelCentresStillTintsCoarseLodPixels() {
        for (final int lod : new int[] {3, 4}) {
            final int[] withRiver = composeRiverStripeTile(lod);
            final int[] dryReference = composeDryPlainsTile(lod);
            assertNotEquals(
                dryReference[0], withRiver[0],
                "LOD" + lod + " must not render a river-crossed pixel identically to dry plains"
            );
            // The stripe is half of each pixel's sub-samples, so the blend has to sit strictly
            // between dry land and open water rather than snapping to either.
            final int blended = withRiver[0];
            assertTrue(
                Argb.blue(blended) > Argb.blue(dryReference[0]),
                "a river sub-sample must pull the pixel toward water, got "
                    + Integer.toHexString(blended) + " vs dry " + Integer.toHexString(dryReference[0])
            );
        }
    }

    /** Same fixture with the river moved onto the pixel centres, so every sub-sample is plains. */
    private static int[] composeDryPlainsTile(final int lod) {
        final BaselineSampler plainsOnly = new BaselineSampler() {
            private final RiverStripeFakeSampler delegate = new RiverStripeFakeSampler(1 << lod);

            @Override
            public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
                return false;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean overviewHeights(
                final int blockX, final int blockZ, final int w, final int h, final int stride,
                final int[] outTerrainY
            ) {
                return delegate.overviewHeights(blockX, blockZ, w, h, stride, outTerrainY);
            }

            @Override
            public boolean surfaceColumns(
                final int blockX, final int blockZ, final int w, final int h, final int stride,
                final int[] outSolidY, final int[] outFluidY, final int[] outSurfaceY, final int[] outFlags
            ) {
                return delegate.surfaceColumns(
                    blockX, blockZ, w, h, stride, outSolidY, outFluidY, outSurfaceY, outFlags
                );
            }

            @Override
            public boolean surfaceBiomes(
                final int blockX, final int blockZ, final int w, final int h, final int stride,
                final int[] terrainY, final int[] outBiomeIds
            ) {
                Arrays.fill(outBiomeIds, 0, w * h, RiverStripeFakeSampler.PLAINS);
                return true;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };
        final BaselineGrid grid = LodSampling.sample(plainsOnly, false, lod, 0, 0);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, 7L, lod, 0, 0);
        return PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults());
    }
}
