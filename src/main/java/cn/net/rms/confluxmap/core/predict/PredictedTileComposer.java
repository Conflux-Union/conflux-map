package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.net.DiffSpec;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.util.Argb;

/**
 * Turns a {@link DerivedGrid} (+ the {@link BaselineGrid} it was derived from, for biome ids)
 * into a 256x256 ARGB pixel array, mirroring {@code
 * cn.net.rms.confluxmap.core.tile.TileService#composeRegion}'s visual pipeline as closely as a
 * baseline with no real block/light data can: continuous height shading via {@link
 * ShadingPipeline#heightShade} against the same fixed {@link
 * ShadingPipeline#REFERENCE_HEIGHT} reference, water rendered as a translucent surface color
 * pre-composited over an approximated seafloor (see {@link #seafloorColor}, since there is no
 * real seafloor block to sample - cubiomes gives biome + terrain height only). Day/night lighting
 * is deliberately not baked into these cached pixels: the fullscreen renderer applies one global
 * tint to the entire predicted plane, so tiles composed at different times stay identical.
 *
 * <p>Deterministic: every input here is already-sampled/derived data plus a per-session {@link
 * PredictionPalette}; no randomness, no wall-clock or otherwise non-reproducible state.
 */
public final class PredictedTileComposer {
    /** Opaque sand/dirt-like stand-in for an unseen seafloor, darkened by {@link #seafloorColor}. */
    private static final int SEAFLOOR_BASE = 0xFFC2A876;
    private static final float SEAFLOOR_DARKEN_RANGE_BLOCKS = 48f;
    private static final float SEAFLOOR_MIN_BRIGHTNESS = 0.25f;

    private PredictedTileComposer() {
    }

    public static int[] compose(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final PredictionPalette palette
    ) {
        return compose(derived, grid, palette, null, PredictionViewMode.EVERYWHERE, 0);
    }

    /** Composes with an optional absolute correction overlay and generated-only mask. */
    public static int[] compose(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final PredictionPalette palette,
        final CorrectionTile corrections,
        final PredictionViewMode viewMode,
        final int lod
    ) {
        final int size = BaselineGrid.PIXELS;
        final int[] out = new int[size * size];
        final int[] surface = derived.surfaceY.clone();
        final byte[] kinds = derived.kind.clone();
        final int[] fluids = derived.fluidDepth.clone();
        final int[] biomes = grid.biomeId.clone();
        final int[] colors = new int[size * size];
        final boolean[] corrected = new boolean[size * size];
        if (corrections != null) {
            for (final PatchCodec.Sample sample : corrections.copyPatch().samples()) {
                final int pixel = sample.pixelIndex();
                final SurfaceKind correctedKind = SurfaceKind.byOrdinal(sample.kind());
                // UNKNOWN means the server summary did not have a usable surface column (most
                // commonly a structure_starts chunk without heightmaps). It is not authoritative
                // terrain and must never erase the deterministic baseline underneath it.
                if (correctedKind == SurfaceKind.UNKNOWN) {
                    continue;
                }
                final int gridIndex = BaselineGrid.index(pixel & 255, pixel >>> 8);
                final SurfaceKind predictedKind = SurfaceKind.byOrdinal(kinds[gridIndex]);
                if (DiffSpec.keepsPredictedCanopy(predictedKind, correctedKind, sample.mapColorId())) {
                    continue;
                }
                surface[gridIndex] = sample.surfaceY();
                kinds[gridIndex] = (byte) sample.kind();
                fluids[gridIndex] = sample.fluidDepth();
                biomes[gridIndex] = sample.biomeId();
                colors[pixel] = sample.mapColorId();
                corrected[pixel] = true;
            }
        }
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                final int idx = BaselineGrid.index(x, z);
                final int outIdx = z * size + x;
                if (!viewMode.showsPredictedPixels(corrections, outIdx, lod)) {
                    out[outIdx] = Argb.TRANSPARENT;
                    continue;
                }
                final SurfaceKind kind = SurfaceKind.byOrdinal(kinds[idx]);
                if (kind == SurfaceKind.UNKNOWN || kind == SurfaceKind.VOID) {
                    out[outIdx] = Argb.TRANSPARENT;
                    continue;
                }

                final int surfaceY = surface[idx];
                final double shade = ShadingPipeline.combinedShade(
                    // Predicted heights are integer-quantized interpolations. Applying the real
                    // map's discrete +/-1/8 diagonal slope term to every predicted pixel creates
                    // a regular contour/stripe artifact, so prediction uses only continuous
                    // absolute-height shading. Real captured tiles retain slope shading.
                    true, false, surfaceY, ShadingPipeline.REFERENCE_HEIGHT, null
                );
                final int biomeId = biomes[idx];

                int composed;
                if (kind == SurfaceKind.WATER) {
                    // A single unified ocean tint instead of the per-biome waterColor: warm/cold/
                    // lukewarm ocean each carry different hues, and cubiomes' coarse biome grid makes
                    // adjacent predicted tiles snap to different ocean variants along a coast, fracturing
                    // one body of water into visibly different-colored tiles. Predicted water is already
                    // an approximation (a seafloor stand-in composited beneath), so continuity wins here.
                    final int water = Argb.multiply(palette.waterBase, BiomeTable.DEFAULT_WATER_TINT);
                    final int floor = seafloorColor(fluids[idx]);
                    final int shadedWater = ShadingPipeline.applyShade(water, shade);
                    final int shadedFloor = ShadingPipeline.applyShade(floor, shade);
                    composed = ShadingPipeline.compositeOver(shadedWater, shadedFloor);
                } else if (corrected[outIdx] && colors[outIdx] != 0xFF) {
                    composed = ShadingPipeline.applyShade(MapColorTable.argb(colors[outIdx]), shade);
                } else {
                    composed = ShadingPipeline.applyShade(colorFor(kind, biomeId, palette), shade);
                }
                out[outIdx] = composed;
            }
        }
        return out;
    }

    private static int colorFor(final SurfaceKind kind, final int biomeId, final PredictionPalette palette) {
        switch (kind) {
            case SAND:
                return palette.sandBase;
            case SNOW:
                return palette.snowBase;
            case ICE:
                return palette.iceBase;
            case FOLIAGE:
                return Argb.multiply(palette.foliageBase, palette.foliageTint(biomeId));
            case LAND:
            default:
                return Argb.multiply(palette.landBase, palette.grassTint(biomeId));
        }
    }

    /** Depth-darkened stand-in for a seafloor cubiomes never actually tells us about. */
    private static int seafloorColor(final int fluidDepth) {
        final float brightness = Math.max(SEAFLOOR_MIN_BRIGHTNESS, 1f - fluidDepth / SEAFLOOR_DARKEN_RANGE_BLOCKS);
        return Argb.scale(SEAFLOOR_BASE, brightness);
    }

}
