package cn.net.rms.confluxmap.core.config;

/**
 * All client settings, serialized as one JSON document.
 * Add fields with defaults only; never rename without bumping
 * {@link #SCHEMA_VERSION} and adding a migration in {@link ConfigIo}.
 */
public final class ConfluxConfig {
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;

    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public enum Shape { SQUARE, CIRCLE }

    public boolean minimapEnabled = true;
    public Corner minimapCorner = Corner.TOP_RIGHT;
    public Shape minimapShape = Shape.SQUARE;
    public int minimapSize = 128;
    public boolean minimapRotate = false;
    public int minimapZoomIndex = 1;
    public boolean showCoordinates = true;
    public boolean showBiome = true;

    public int snapshotBudgetPerTick = 8;
    public int gpuTileCacheLimit = 256;

    public ConfluxConfig copy() {
        final ConfluxConfig c = new ConfluxConfig();
        c.schemaVersion = schemaVersion;
        c.minimapEnabled = minimapEnabled;
        c.minimapCorner = minimapCorner;
        c.minimapShape = minimapShape;
        c.minimapSize = minimapSize;
        c.minimapRotate = minimapRotate;
        c.minimapZoomIndex = minimapZoomIndex;
        c.showCoordinates = showCoordinates;
        c.showBiome = showBiome;
        c.snapshotBudgetPerTick = snapshotBudgetPerTick;
        c.gpuTileCacheLimit = gpuTileCacheLimit;
        return c;
    }

    /** Clamp out-of-range values loaded from a hand-edited file. */
    public void normalize() {
        if (minimapCorner == null) {
            minimapCorner = Corner.TOP_RIGHT;
        }
        if (minimapShape == null) {
            minimapShape = Shape.SQUARE;
        }
        minimapSize = clamp(minimapSize, 64, 256);
        minimapZoomIndex = clamp(minimapZoomIndex, 0, 3);
        snapshotBudgetPerTick = clamp(snapshotBudgetPerTick, 1, 64);
        gpuTileCacheLimit = clamp(gpuTileCacheLimit, 16, 2048);
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }
}
