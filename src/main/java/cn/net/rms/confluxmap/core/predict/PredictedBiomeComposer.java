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
        final int size = BaselineGrid.PIXELS;
        final int[] pixels = new int[size * size];
        final int[] correctedBiome = new int[pixels.length];
        final byte[] correctedKind = new byte[pixels.length];
        final boolean[] corrected = new boolean[pixels.length];
        if (corrections != null) {
            for (final PatchCodec.Sample sample : corrections.copyPatch().samples()) {
                final SurfaceKind kind = SurfaceKind.byOrdinal(sample.kind());
                if (kind == SurfaceKind.UNKNOWN) {
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
                if (kind == SurfaceKind.UNKNOWN || kind == SurfaceKind.VOID) {
                    pixels[pixel] = Argb.TRANSPARENT;
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
