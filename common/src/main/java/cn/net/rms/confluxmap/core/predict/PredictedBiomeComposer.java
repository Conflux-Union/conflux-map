package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.BiomeColorPalette;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.util.Argb;

/** Flat biome-identity rendering for the predicted fullscreen underlay. */
public final class PredictedBiomeComposer {
    private PredictedBiomeComposer() {
    }

    public static int[] compose(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final CorrectionTile corrections,
        final PredictionViewMode viewMode,
        final int lod
    ) {
        return compose(
            derived, grid, corrections, viewMode, lod, derived, grid, Argb.TRANSPARENT
        );
    }

    public static int[] compose(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final CorrectionTile corrections,
        final PredictionViewMode viewMode,
        final int lod,
        final DerivedGrid correctionDerived,
        final BaselineGrid correctionGrid
    ) {
        return compose(
            derived, grid, corrections, viewMode, lod, correctionDerived, correctionGrid,
            Argb.TRANSPARENT
        );
    }

    /** {@code voidColor} is the terrain composer's configured VOID paint, keeping both views seamless. */
    public static int[] compose(
        final DerivedGrid derived,
        final BaselineGrid grid,
        final CorrectionTile corrections,
        final PredictionViewMode viewMode,
        final int lod,
        final DerivedGrid correctionDerived,
        final BaselineGrid correctionGrid,
        final int voidColor
    ) {
        final int size = BaselineGrid.PIXELS;
        final int[] pixels = new int[size * size];
        final int[] correctedBiome = new int[pixels.length];
        final byte[] correctedKind = new byte[pixels.length];
        final boolean[] corrected = new boolean[pixels.length];
        if (corrections != null) {
            final PatchCodec.Patch correctionPatch = corrections.copyPatch();
            if (corrections.patchMode() == cn.net.rms.confluxmap.core.net.Proto.PATCH_MODE_RESIDUAL
                && correctionDerived != null && correctionGrid != null) {
                final byte[] evaluated = correctionPatch.evaluated();
                for (int pixel = 0; pixel < pixels.length; pixel++) {
                    if ((evaluated[pixel >>> 3] & (1 << (pixel & 7))) == 0) {
                        continue;
                    }
                    final int gridIndex = BaselineGrid.index(pixel & 255, pixel >>> 8);
                    corrected[pixel] = true;
                    correctedBiome[pixel] = correctionGrid.biomeId[gridIndex];
                    correctedKind[pixel] = correctionDerived.kind[gridIndex];
                }
            }
            for (final PatchCodec.Sample sample : correctionPatch.samples()) {
                final SurfaceKind kind = SurfaceKind.byOrdinal(sample.kind());
                if (kind == SurfaceKind.UNKNOWN) {
                    corrected[sample.pixelIndex()] = false;
                    continue;
                }
                corrected[sample.pixelIndex()] = true;
                correctedBiome[sample.pixelIndex()] = sample.biomeId();
                correctedKind[sample.pixelIndex()] = (byte) kind.ordinal();
            }
        }
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                final int pixel = z * size + x;
                if (!viewMode.showsPredictedPixels(corrections, pixel, lod)) {
                    pixels[pixel] = Argb.TRANSPARENT;
                    continue;
                }
                final int gridIndex = BaselineGrid.index(x, z);
                final SurfaceKind kind = corrected[pixel]
                    ? SurfaceKind.byOrdinal(correctedKind[pixel])
                    : SurfaceKind.byOrdinal(derived.kind[gridIndex]);
                if (kind == SurfaceKind.UNKNOWN) {
                    pixels[pixel] = Argb.TRANSPARENT;
                    continue;
                }
                if (kind == SurfaceKind.VOID) {
                    pixels[pixel] = voidColor;
                    continue;
                }
                final int biomeId = corrected[pixel]
                    ? correctedBiome[pixel]
                    : grid.biomeId[gridIndex];
                pixels[pixel] = BiomeColorPalette.colorForCubiomes(biomeId);
            }
        }
        return pixels;
    }
}
