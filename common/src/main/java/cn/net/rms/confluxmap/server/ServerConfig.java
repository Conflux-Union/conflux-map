package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.nativepredict.PredictionBackend;
import cn.net.rms.confluxmap.server.shared.SharedWaypointService;
import cn.net.rms.confluxmap.server.web.WebMapConfig;

/**
 * Server-side companion settings, serialized as one JSON document at
 * {@code <configDir>/confluxmap/server.json}. Add fields with defaults only; never
 * rename without bumping {@link #SCHEMA_VERSION}.
 *
 * <p>Player-facing capabilities default ON when the existing permission, quota, and network
 * boundaries make that safe. Settings that reveal sensitive server state still default OFF.
 */
public final class ServerConfig {
    public static final int SCHEMA_VERSION = 7;

    public int schemaVersion = SCHEMA_VERSION;

    /** Master toggle for the companion. When false, no handshake replies are sent. */
    public boolean enabled = true;
    /**
     * Whether operators may push the full generated-region manifest so cooperating clients can
     * bulk-pull the entire explored map of a dimension. This discloses every generated area of
     * the world - far beyond the viewport-scoped data {@link #shareCorrections} leaks - so it is
     * a deliberate opt-in independent of that flag and defaults OFF.
     */
    public boolean fullSyncEnabled = false;
    /** Dedicated-server startup GitHub release probe; a newer version is announced on the console. */
    public boolean checkForUpdates = true;
    /**
     * Whether to include the world seed in {@link HelloPolicyS2C}'s per-dim entries. Default OFF -
     * sending the seed reveals the world's PRNG to every unauthenticated player, which is
     * unacceptable for PvP/rng-manipulation-sensitive servers. Operators turn this on
     * explicitly when the prediction underlay matters more than seed secrecy.
     */
    public boolean shareSeed = false;
    /**
     * Whether cooperating clients may use the fullscreen biome-map mode while the seed is
     * shared. This is a UI policy, not a secrecy boundary: a client that knows the seed can
     * derive biome locations independently.
     */
    public boolean allowBiomeMap = true;
    /**
     * Whether cooperating clients may render or search seed-derived structure candidates while
     * the seed is shared. This cannot prevent a modified client from deriving the same data.
     */
    public boolean allowStructureSearch = true;
    /** Whether the server will serve map corrections (MAP_PATCH). S3 frames the channel; S4 fills it. */
    public boolean shareCorrections = true;
    /** Whether clients may see the server's currently loaded chunks and effective ticket levels. */
    public boolean shareChunkLoadState = false;
    /**
     * Whether cooperating clients may use entity radar. When enabled, the companion also sends
     * every online player's position; disabling it stops that stream and hides the client radar.
     */
    public boolean allowEntityRadar = true;
    /**
     * Whether cooperating clients may render the HUD minimap. Honest-client enforcement only,
     * like {@link #allowEntityRadar}: a modified client can ignore it.
     */
    public boolean allowMinimap = true;
    /**
     * Whether cooperating clients may open the fullscreen world map (including map export,
     * annotations, and map markers reached through it).
     */
    public boolean allowWorldMap = true;
    /**
     * Whether cooperating clients may use local waypoints: the list and editor screens plus
     * in-world beam/label rendering. Independent of {@link #shareWaypoints}, which governs the
     * server-owned shared catalog.
     */
    public boolean allowWaypoints = true;
    /**
     * Whether cooperating clients may display the seed-prediction underlay. Disabling this hides
     * predicted tiles even on servers that share the seed for map-sync corrections.
     */
    public boolean allowPrediction = true;
    public String restrictionMessage = "";
    /** Whether the server-owned shared waypoint catalog is enabled. */
    public boolean shareWaypoints = true;
    /** Whether non-operators may create and manage shared waypoints they published. */
    public boolean allowNonOperatorSharedWaypointManagement = true;
    /** Maximum shared waypoints retained for one world. */
    public int maxSharedWaypointsPerWorld = SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS;
    /** Maximum shared waypoints published by one player in one world. */
    public int maxSharedWaypointsPerPlayer = 64;
    /** Per-player shared-waypoint mutation rate; a small burst is allowed. */
    public int sharedWaypointMutationsPerMinute = 30;
    /** One MAP_VIEW_REQ carries at most this many tiles. */
    public int maxTilesPerRequest = Proto.DEFAULT_MAX_TILES_PER_REQ;
    /** Per-player cap on tiles queued for paced delivery; defaults to one full subscribed viewport. */
    public int maxPendingTilesPerPlayer = Proto.MAX_MAP_SYNC_VIEW_TILES;
    /** Per-player token-bucket rate, bytes/sec. */
    public int maxBytesPerSecondPerPlayer = Proto.DEFAULT_MAX_BYTES_PER_SEC;
    /** Per-player minimum spacing between MAP_VIEW_REQ packets, milliseconds. */
    public int minRequestIntervalMs = Proto.DEFAULT_MIN_REQ_INTERVAL_MS;
    /** Global live-summary refresh budget in chunks/sec. */
    public int maxChunkSummariesPerSecond = 4_000;
    /** Loopback-only HTTP map by default; remote exposure still requires an explicit safe setup. */
    public WebMapConfig webMap = new WebMapConfig();

    /**
     * Engine for the companion's own prediction baseline (patch generation and seed-derived
     * features). GPU uses the OpenCL executor when the host has a working device and serves
     * the exact CPU reference otherwise. The effective engine participates in the prediction
     * identity ({@code GpuBackend}), so this companion only residual-diff-codes against
     * clients whose prediction engines actually agree with its own; everyone else gets
     * absolute patches without any protocol change.
     */
    public PredictionBackend predictionBackend = PredictionBackend.GPU;

    /** Clamp out-of-range values loaded from a hand-edited file. */
    public void normalize() {
        if (predictionBackend == null) {
            predictionBackend = PredictionBackend.GPU;
        }
        restrictionMessage = restrictionMessage == null ? "" : restrictionMessage.strip();
        int messageEnd = 0;
        int messageBytes = 0;
        while (messageEnd < restrictionMessage.length()) {
            final int codePoint = restrictionMessage.codePointAt(messageEnd);
            messageBytes += codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
            if (messageBytes > Proto.MAX_UTF8_BYTES) {
                break;
            }
            messageEnd += Character.charCount(codePoint);
        }
        restrictionMessage = restrictionMessage.substring(0, messageEnd);
        if (webMap == null) {
            webMap = new WebMapConfig();
        }
        webMap.normalize();
        maxTilesPerRequest = clamp(maxTilesPerRequest, 1, 255);
        maxPendingTilesPerPlayer = clamp(maxPendingTilesPerPlayer, 1, 1024);
        maxBytesPerSecondPerPlayer = clamp(maxBytesPerSecondPerPlayer, 1024, 1 << 20);
        minRequestIntervalMs = clamp(minRequestIntervalMs, 0, 60_000);
        maxChunkSummariesPerSecond = clamp(maxChunkSummariesPerSecond, 1, 60_000);
        maxSharedWaypointsPerWorld = clamp(
            maxSharedWaypointsPerWorld,
            1,
            SharedWaypointProto.MAX_SNAPSHOT_WAYPOINTS
        );
        maxSharedWaypointsPerPlayer = clamp(maxSharedWaypointsPerPlayer, 1, maxSharedWaypointsPerWorld);
        sharedWaypointMutationsPerMinute = clamp(sharedWaypointMutationsPerMinute, 1, 6_000);
    }

    public SharedWaypointService.AccessPolicy sharedWaypointAccessPolicy() {
        return allowNonOperatorSharedWaypointManagement
            ? SharedWaypointService.AccessPolicy.OWNER_MANAGED
            : SharedWaypointService.AccessPolicy.OPERATOR_ONLY;
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }
}
