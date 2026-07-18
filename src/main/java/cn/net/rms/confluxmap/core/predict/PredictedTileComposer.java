package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.util.Argb;

/**
 * Turns a {@link DerivedGrid} (+ the {@link BaselineGrid} it was derived from, for biome ids)
 * into a 256x256 ARGB pixel array, mirroring {@code
 * cn.net.rms.confluxmap.core.tile.TileService#composeRegion}'s visual pipeline as closely as a
 * baseline with no real block/light data can: height/slope shading via {@link
 * ShadingPipeline#combinedShade} against the same fixed diagonal neighbor and {@link
 * ShadingPipeline#REFERENCE_HEIGHT} reference, water rendered as a translucent surface color
 * pre-composited over an approximated seafloor (see {@link #seafloorColor}, since there is no
 * real seafloor block to sample - cubiomes gives biome + terrain height only), and the same
 * day/night {@link ShadingPipeline#applyDaylight} blend applied to Overworld SURFACE tiles only
 * (never End, exactly like {@code MapLayer.Type.END_SURFACE} never gets it in the real pipeline -
 * the caller simply never passes {@code applyDaylight=true} for an End tile).
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
        final PredictionPalette palette,
        final boolean applyDaylight,
        final float daylightFactor
    ) {
        final int size = BaselineGrid.PIXELS;
        final int[] out = new int[size * size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                final int idx = BaselineGrid.index(x, z);
                final int outIdx = z * size + x;
                final SurfaceKind kind = SurfaceKind.byOrdinal(derived.kind[idx]);
                if (kind == SurfaceKind.UNKNOWN || kind == SurfaceKind.VOID) {
                    out[outIdx] = Argb.TRANSPARENT;
                    continue;
                }

                final int surfaceY = derived.surfaceY[idx];
                final Integer neighborHeight = neighborHeight(derived, x, z);
                final double shade = ShadingPipeline.combinedShade(
                    true, true, surfaceY, ShadingPipeline.REFERENCE_HEIGHT, neighborHeight
                );
                final int biomeId = grid.biomeId[idx];

                int composed;
                if (kind == SurfaceKind.WATER) {
                    final int water = Argb.multiply(palette.waterBase, palette.waterTint(biomeId));
                    final int floor = seafloorColor(derived.fluidDepth[idx]);
                    final int shadedWater = ShadingPipeline.applyShade(water, shade);
                    final int shadedFloor = ShadingPipeline.applyShade(floor, shade);
                    composed = ShadingPipeline.compositeOver(shadedWater, shadedFloor);
                } else {
                    composed = ShadingPipeline.applyShade(colorFor(kind, biomeId, palette), shade);
                }
                if (applyDaylight) {
                    // Predicted tiles have no per-column block-light data at all, so the darkening
                    // curve reduces to the same "no artificial light" case a real unlit outdoor
                    // column takes - see ShadingPipeline#applyDaylight's own javadoc.
                    composed = ShadingPipeline.applyDaylight(composed, daylightFactor, 0);
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

    /**
     * The fixed diagonal neighbor (x-1, z+1), read from this same margin-inclusive grid (see
     * {@link BaselineGrid}) - null (the shading pipeline's "missing neighbor" case) only where
     * that neighbor is itself void (End only; every Overworld cell always has a real height).
     */
    private static Integer neighborHeight(final DerivedGrid derived, final int x, final int z) {
        final int idx = BaselineGrid.index(x - 1, z + 1);
        final SurfaceKind neighborKind = SurfaceKind.byOrdinal(derived.kind[idx]);
        if (neighborKind == SurfaceKind.VOID || neighborKind == SurfaceKind.UNKNOWN) {
            return null;
        }
        return derived.surfaceY[idx];
    }
}
