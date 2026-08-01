package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;

/**
 * Server-side companion settings, serialized as one JSON document at
 * {@code <configDir>/confluxmap/server.json}. Add fields with defaults only; never
 * rename without bumping {@link #SCHEMA_VERSION}.
 *
 * <p>Security-relevant or not-yet-implemented capabilities default OFF. Map corrections default
 * ON so a fresh server install gets map-sync benefits without extra setup.
 */
public final class ServerConfig {
    public static final int SCHEMA_VERSION = 4;

    public int schemaVersion = SCHEMA_VERSION;

    /** Master toggle for the companion. When false, no handshake replies are sent. */
    public boolean enabled = true;
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
    /** Whether cooperating Conflux Map clients may scan and render their entity radar. */
    public boolean allowEntityRadar = true;
    /** Whether players may publish and receive server-owned shared waypoints. */
    public boolean shareWaypoints = false;
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

    /** Clamp out-of-range values loaded from a hand-edited file. */
    public void normalize() {
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

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }
}
