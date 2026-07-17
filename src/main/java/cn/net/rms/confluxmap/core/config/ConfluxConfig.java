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

    /**
     * Manual layer override cycled by {@code key.confluxmap.cycle_layer}; see
     * {@code mc.world.LayerSelector} for how each dimension interprets these
     * (e.g. FORCE_UNDERGROUND means CAVE_AUTO in the Overworld, NETHER_CEILING
     * in the Nether, and is a no-op in the End).
     */
    public enum LayerOverride { AUTO, FORCE_SURFACE, FORCE_UNDERGROUND }

    public boolean minimapEnabled = true;
    public Corner minimapCorner = Corner.TOP_RIGHT;
    public Shape minimapShape = Shape.SQUARE;
    public int minimapSize = 128;
    public boolean minimapRotate = true;
    public int minimapZoomIndex = 1;
    public boolean showCoordinates = true;
    public boolean showBiome = true;

    /** cave-nether-layers.md §1/§6: manual pin, or AUTO for the per-dimension automatic detection. */
    public LayerOverride layerOverride = LayerOverride.AUTO;
    /** Minimap/fullscreen-map info line: a small text label naming the currently active layer. */
    public boolean showLayerIndicator = true;
    /** Fixed-band Y for {@code MapLayer.CAVE_SLICE}; not yet reachable via the cycle keybind (UI deferred). */
    public int caveSliceY = 32;
    /** Fixed-band Y for {@code MapLayer.NETHER_SLICE}; not yet reachable via the cycle keybind (UI deferred). */
    public int netherSliceY = 64;

    public int snapshotBudgetPerTick = 8;
    public int gpuTileCacheLimit = 256;

    /** Master toggle for the whole entity-radar overlay (docs/reference-specs/radar-icons.md sec 4). */
    public boolean radarEnabled = true;
    public boolean radarShowPlayers = true;
    public boolean radarShowHostile = true;
    /** Spec default for the "neutral" category is off; M1's PASSIVE bucket is that same category. */
    public boolean radarShowPassive = false;
    /** Defensive fallback bucket (see {@code core.radar.RadarCategory}); off by default alongside PASSIVE. */
    public boolean radarShowOther = false;
    /** Horizontal radar tracking radius, in world blocks (independent of minimap zoom/size). */
    public int radarRange = 80;
    public boolean radarShowPlayerNames = true;
    public int radarMaxEntities = 100;
    /** 3-D straight-line blocks; 0 means "no cutoff" (see waypoint-ux.md S7). */
    public int waypointRenderDistance = 0;
    public boolean waypointEdgeIndicatorsEnabled = true;
    /** Death points kept per dimension, oldest auto-pruned; 0 disables creating new ones. */
    public int deathPointsKept = 5;
    /** In-world vertical beam at each visible waypoint's column (see {@code mc.ui.world.WaypointWorldRenderer}). */
    public boolean waypointBeamsEnabled = true;
    /** In-world floating name/distance label above each visible waypoint. */
    public boolean waypointLabelsEnabled = true;

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
        c.layerOverride = layerOverride;
        c.showLayerIndicator = showLayerIndicator;
        c.caveSliceY = caveSliceY;
        c.netherSliceY = netherSliceY;
        c.snapshotBudgetPerTick = snapshotBudgetPerTick;
        c.gpuTileCacheLimit = gpuTileCacheLimit;
        c.radarEnabled = radarEnabled;
        c.radarShowPlayers = radarShowPlayers;
        c.radarShowHostile = radarShowHostile;
        c.radarShowPassive = radarShowPassive;
        c.radarShowOther = radarShowOther;
        c.radarRange = radarRange;
        c.radarShowPlayerNames = radarShowPlayerNames;
        c.radarMaxEntities = radarMaxEntities;
        c.waypointRenderDistance = waypointRenderDistance;
        c.waypointEdgeIndicatorsEnabled = waypointEdgeIndicatorsEnabled;
        c.deathPointsKept = deathPointsKept;
        c.waypointBeamsEnabled = waypointBeamsEnabled;
        c.waypointLabelsEnabled = waypointLabelsEnabled;
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
        if (layerOverride == null) {
            layerOverride = LayerOverride.AUTO;
        }
        minimapSize = clamp(minimapSize, 64, 256);
        minimapZoomIndex = clamp(minimapZoomIndex, 0, 3);
        caveSliceY = clamp(caveSliceY, 0, 255);
        netherSliceY = clamp(netherSliceY, 0, 127);
        snapshotBudgetPerTick = clamp(snapshotBudgetPerTick, 1, 64);
        gpuTileCacheLimit = clamp(gpuTileCacheLimit, 16, 2048);
        radarRange = clamp(radarRange, 16, 256);
        radarMaxEntities = clamp(radarMaxEntities, 1, 500);
        waypointRenderDistance = clamp(waypointRenderDistance, 0, 100_000);
        deathPointsKept = clamp(deathPointsKept, 0, 50);
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }
}
