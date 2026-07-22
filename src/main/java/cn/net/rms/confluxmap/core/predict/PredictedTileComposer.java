package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.net.DiffSpec;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.TileMath;

/**
 * Turns a {@link DerivedGrid} (+ the {@link BaselineGrid} it was derived from, for biome ids)
 * into a 256x256 ARGB pixel array. The prediction plane layers LOD-normalized directional relief
 * over the captured-map absolute-height curve, and renders water as a translucent surface color
 * pre-composited over an approximated seafloor (see
 * {@link #seafloorColor}, since there is no real seafloor block to sample - cubiomes gives biome +
 * terrain height only). Day/night lighting is deliberately not baked into these cached pixels:
 * the fullscreen renderer applies one global tint to the entire predicted plane, so tiles composed
 * at different times stay identical.
 *
 * <p>Deterministic: every input here is already-sampled/derived data plus a per-session {@link
 * PredictionPalette}; no randomness, no wall-clock or otherwise non-reproducible state.
 */
public final class PredictedTileComposer {
    /** Opaque sand/dirt-like stand-in for an unseen seafloor, darkened by {@link #seafloorColor}. */
    private static final int SEAFLOOR_BASE = 0xFFC2A876;
    private static final float SEAFLOOR_DARKEN_RANGE_BLOCKS = 48f;
    private static final float SEAFLOOR_MIN_BRIGHTNESS = 0.25f;
    private static final double RELIEF_CONTRAST = 0.36;

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
        return compose(derived, grid, palette, corrections, viewMode, lod, Proto.MAP_COLOR_NONE);
    }

    /**
     * Full form: {@code baselineMapColorId} pins every non-water, non-corrected baseline pixel to
     * one vanilla map color instead of the biome palette - a superflat underlay renders the
     * actual top-layer block (stone, sandstone, ...) this way, matching the color corrections
     * would use for the same block. {@link Proto#MAP_COLOR_NONE} keeps the biome-palette path.
     */
    public static int[] compose(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final PredictionPalette palette,
        final CorrectionTile corrections,
        final PredictionViewMode viewMode,
        final int lod,
        final int baselineMapColorId
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

                final double reliefMultiplier = directionalReliefMultiplier(
                    surface, kinds, x, z, TileMath.blocksPerPixel(lod)
                );
                final double heightShade = ShadingPipeline.heightShade(
                    surface[idx], ShadingPipeline.REFERENCE_HEIGHT, false
                );
                final int biomeId = biomes[idx];

                int composed;
                if (kind == SurfaceKind.WATER) {
                    // Keep ocean/river water unified: cubiomes' coarse biome grid otherwise fractures
                    // one body of water along warm/cold variant boundaries. Water formed inside a land
                    // biome still needs that biome's tint, most visibly the murky swamp water color.
                    final int waterTint = BiomeTable.get(biomeId).waterBiome()
                        ? BiomeTable.DEFAULT_WATER_TINT
                        : palette.waterTint(biomeId);
                    final int water = Argb.multiply(palette.waterBase, waterTint);
                    final int floor = seafloorColor(fluids[idx]);
                    composed = ShadingPipeline.compositeOver(water, floor);
                } else if (corrected[outIdx] && colors[outIdx] != 0xFF) {
                    composed = MapColorTable.argb(colors[outIdx]);
                } else if (!corrected[outIdx] && baselineMapColorId != Proto.MAP_COLOR_NONE) {
                    composed = MapColorTable.argb(baselineMapColorId);
                } else {
                    composed = colorFor(kind, biomeId, palette);
                }
                final int heightShaded = ShadingPipeline.applyShade(composed, heightShade);
                out[outIdx] = ShadingPipeline.applyBrightnessMultiplier(heightShaded, reliefMultiplier);
            }
        }
        return out;
    }

    /**
     * Directional relief from two three-sample shoulders around the output pixel. Averaging the
     * axial and diagonal samples suppresses single-cell height noise, while the fixed southwest
     * light direction keeps adjacent tiles visually coherent. A one-block-per-axis diagonal rise
     * reaches full contrast; steeper terrain is clamped instead of washing out its biome colour.
     */
    private static double directionalReliefMultiplier(
        final int[] surface,
        final byte[] kinds,
        final int x,
        final int z,
        final int blocksPerPixel
    ) {
        if (blocksPerPixel == 1) {
            return blockAlignedReliefMultiplier(surface, kinds, x, z);
        }
        final Integer litWest = slopeSampleHeight(surface, kinds, x - 1, z);
        final Integer litSouth = slopeSampleHeight(surface, kinds, x, z + 1);
        final Integer litDiagonal = slopeSampleHeight(surface, kinds, x - 1, z + 1);
        final Integer darkEast = slopeSampleHeight(surface, kinds, x + 1, z);
        final Integer darkNorth = slopeSampleHeight(surface, kinds, x, z - 1);
        final Integer darkDiagonal = slopeSampleHeight(surface, kinds, x + 1, z - 1);
        if (litWest == null || litSouth == null || litDiagonal == null
            || darkEast == null || darkNorth == null || darkDiagonal == null) {
            return 1.0;
        }

        final double litMean = (litWest + litSouth + litDiagonal) / 3.0;
        final double darkMean = (darkEast + darkNorth + darkDiagonal) / 3.0;
        final double risePerBlock = (litMean - darkMean) / (2.0 * blocksPerPixel);
        final double normalized = Math.max(-1.0, Math.min(1.0, risePerBlock));
        return 1.0 + RELIEF_CONTRAST * normalized;
    }

    /**
     * LOD0 has one output texel per block, so its relief follows the captured map's fixed
     * southwest neighbor instead of sampling across both sides of an edge. The magnitude remains
     * continuous to avoid turning every one-block interpolation step into a full contour band.
     */
    private static double blockAlignedReliefMultiplier(
        final int[] surface,
        final byte[] kinds,
        final int x,
        final int z
    ) {
        final Integer litDiagonal = slopeSampleHeight(surface, kinds, x - 1, z + 1);
        if (litDiagonal == null) {
            return 1.0;
        }
        final int center = surface[BaselineGrid.index(x, z)];
        final double risePerBlock = (litDiagonal - center) / 2.0;
        final double normalized = Math.max(-1.0, Math.min(1.0, risePerBlock));
        return 1.0 + RELIEF_CONTRAST * normalized;
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
                return palette.groundColor(biomeId);
        }
    }

    /** Depth-darkened stand-in for a seafloor cubiomes never actually tells us about. */
    private static int seafloorColor(final int fluidDepth) {
        final float brightness = Math.max(SEAFLOOR_MIN_BRIGHTNESS, 1f - fluidDepth / SEAFLOOR_DARKEN_RANGE_BLOCKS);
        return Argb.scale(SEAFLOOR_BASE, brightness);
    }

    /** Height at one slope sample, with void/unknown boundaries treated as unavailable. */
    private static Integer slopeSampleHeight(
        final int[] surface,
        final byte[] kinds,
        final int x,
        final int z
    ) {
        final int idx = BaselineGrid.index(x, z);
        final SurfaceKind neighborKind = SurfaceKind.byOrdinal(kinds[idx]);
        if (neighborKind == SurfaceKind.VOID || neighborKind == SurfaceKind.UNKNOWN) {
            return null;
        }
        return surface[idx];
    }

}
