package cn.net.rms.confluxmap.core.predict;

/** Controls how much of the locally predicted plane is shown. */
public enum PredictionViewMode {
    EVERYWHERE,
    GENERATED_ONLY,
    VISITED_ONLY;

    public PredictionViewMode next() {
        final PredictionViewMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public boolean showsPredictedPixels(final CorrectionTile corrections, final int pixelIndex, final int lod) {
        if (this == VISITED_ONLY) {
            return false;
        }
        if (this == EVERYWHERE) {
            return true;
        }
        return corrections != null && corrections.hasGeneratedChunkForPixel(pixelIndex, lod);
    }
}
