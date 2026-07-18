package cn.net.rms.confluxmap.core.model;

import java.util.Locale;

/**
 * Identifies where map data belongs on disk and in memory.
 * {@code serverId} is a sanitized server address for multiplayer or "local" for singleplayer;
 * {@code worldId} is the level/save name (singleplayer) or a server-provided/world marker.
 */
public record WorldIdentity(String serverId, String worldId) {
    public static final WorldIdentity NONE = new WorldIdentity("none", "none");

    /**
     * Non-companion multiplayer: {@code worldId} stays at the literal {@code "world"} so existing
     * caches keep working bit-for-bit. Companion servers go through {@link #multiplayer(String, String)}
     * instead with the UUID the server advertised.
     */
    public static WorldIdentity multiplayer(final String address) {
        return new WorldIdentity(sanitize(address), "world");
    }

    /**
     * Companion-aware multiplayer: the server handed us a stable {@code worldId} (UUID string),
     * so we adopt it as the cache namespace. The address-based {@code serverId} stays the same
     * as the non-companion path so a server operator can still find the right cache directory.
     */
    public static WorldIdentity multiplayer(final String address, final String worldId) {
        return new WorldIdentity(sanitize(address), sanitize(worldId));
    }

    public static WorldIdentity singleplayer(final String levelName) {
        return new WorldIdentity("local", sanitize(levelName));
    }

    private static String sanitize(final String s) {
        final String cleaned = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    public boolean isPresent() {
        return this != NONE;
    }
}
