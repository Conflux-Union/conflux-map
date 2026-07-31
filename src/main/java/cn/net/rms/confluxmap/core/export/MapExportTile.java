package cn.net.rms.confluxmap.core.export;

/** One real map tile, its optional predicted underlay, and matching load-state plane. */
public record MapExportTile(int[] real, int[] predicted, MapExportLoadState loadState) {
    private static final int PIXELS = 256 * 256;

    public MapExportTile {
        if (real == null || real.length != PIXELS) {
            throw new IllegalArgumentException("Real export tile must contain 256x256 pixels");
        }
        if (predicted != null && predicted.length != PIXELS) {
            throw new IllegalArgumentException("Predicted export tile must contain 256x256 pixels");
        }
        if (loadState == null) {
            throw new IllegalArgumentException("Export load-state plane cannot be null");
        }
    }

    public MapExportTile(final int[] real, final int[] predicted) {
        this(real, predicted, MapExportLoadState.empty());
    }
}
