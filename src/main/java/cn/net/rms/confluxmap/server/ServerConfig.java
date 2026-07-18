package cn.net.rms.confluxmap.server;

/**
 * Server-side companion settings, serialized as one JSON document at
 * {@code <configDir>/confluxmap/server.json}. Add fields with defaults only; never
 * rename without bumping {@link #SCHEMA_VERSION}.
 *
 * <p>Security-relevant defaults are OFF ({@link #shareSeed}); the rest default ON so a
 * fresh server install gets map-sync benefits without extra setup once the operator opts
 * into seed sharing.
 */
public final class ServerConfig {
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;

    /** Master toggle for the companion. When false, no handshake replies are sent. */
    public boolean enabled = true;
    /**
     * Whether to include the world seed in {@link HelloPolicyS2C}'s per-dim entries. Default OFF -
     * sending the seed reveals the world's PRNG to every unauthenticated player, which is
     * unacceptable for PvP/rng-manipulation-sensitive servers. Operators turn this on
     * explicitly when the prediction underlay matters more than seed secrecy.
     */
    public boolean shareSeed = false;
    /** Whether the server will serve map corrections (MAP_PATCH). S3 frames the channel; S4 fills it. */
    public boolean shareCorrections = true;
    /** Whether structure-bounds info is included. S6 consumes this; S3 only carries the flag. */
    public boolean shareStructureInfo = true;
    /** Hard ceiling on which LOD the server will compute patches for; above this returns UNAVAILABLE. */
    public int maxPatchLod = 2;
    /** One MAP_VIEW_REQ carries at most this many tiles. */
    public int maxTilesPerRequest = 8;
    /** Per-player queue cap on outstanding tiles (request arrives before any patch reply). */
    public int maxPendingTilesPerPlayer = 16;
    /** Per-player token-bucket rate, bytes/sec. */
    public int maxBytesPerSecondPerPlayer = 65_536;
    /** Per-player minimum spacing between MAP_VIEW_REQ packets, milliseconds. */
    public int minRequestIntervalMs = 300;
    /** Cold-cache budget: across all players, chunk-NBT-parses/sec (S4's summarizer uses this). */
    public int maxChunkSummariesPerSecond = 4_000;

    /** Clamp out-of-range values loaded from a hand-edited file. */
    public void normalize() {
        maxPatchLod = clamp(maxPatchLod, 0, 4);
        maxTilesPerRequest = clamp(maxTilesPerRequest, 1, 255);
        maxPendingTilesPerPlayer = clamp(maxPendingTilesPerPlayer, 1, 1024);
        maxBytesPerSecondPerPlayer = clamp(maxBytesPerSecondPerPlayer, 1024, 1 << 20);
        minRequestIntervalMs = clamp(minRequestIntervalMs, 0, 60_000);
        maxChunkSummariesPerSecond = clamp(maxChunkSummariesPerSecond, 1, 60_000);
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }
}
