package cn.net.rms.confluxmap.nativepredict;

import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a worldgen version string (as reported by a server handshake, e.g. {@code "1.17.1"})
 * to the {@code enum MCVersion} integer the vendored cubiomes commit expects in
 * {@code setupGenerator}/{@code getStructurePos}/etc.
 *
 * <p>cubiomes only gives a dedicated enum value to a minecraft release when its worldgen
 * actually changed; every other patch of that minor version aliases the "latest patch" value
 * (see the comment on {@code enum MCVersion} in {@code native/cubiomes/biomes.h}). This table
 * mirrors that: patch strings that cubiomes calls out by name map to their own constant,
 * everything else in the same minor line falls back to that line's latest-patch constant.
 * Values were read directly off the pinned commit's {@code biomes.h} (see {@link
 * PredictorVersion#CUBIOMES_COMMIT_12}), not guessed from the changelog.
 */
public final class McVersions {
    private static final Map<String, Integer> BY_VERSION = new ConcurrentHashMap<>();

    static {
        put(1, "1.7", "1.7.10");
        put(11, "1.8", "1.8.9");
        put(12, "1.9", "1.9.1", "1.9.2", "1.9.3", "1.9.4");
        put(13, "1.10", "1.10.2");
        put(14, "1.11", "1.11.2");
        put(15, "1.12", "1.12.1", "1.12.2");
        put(16, "1.13", "1.13.1", "1.13.2");
        put(17, "1.14", "1.14.1", "1.14.2", "1.14.3", "1.14.4");
        put(18, "1.15", "1.15.1", "1.15.2");
        put(19, "1.16.1");
        put(20, "1.16", "1.16.2", "1.16.3", "1.16.4", "1.16.5");
        put(21, "1.17", "1.17.1");
        put(22, "1.18", "1.18.1", "1.18.2");
        put(23, "1.19.2");
        put(24, "1.19", "1.19.1", "1.19.3", "1.19.4");
        put(25, "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6");
        put(26, "1.21.1");
        put(27, "1.21.3");
        put(28, "1.21", "1.21.2");
    }

    private McVersions() {
    }

    private static void put(final int mc, final String... versions) {
        for (final String version : versions) {
            BY_VERSION.put(version, mc);
        }
    }

    /** Looks up the cubiomes {@code MCVersion} int for a worldgen version string; empty if unknown. */
    public static OptionalInt toCubiomes(final String worldgenVersion) {
        final Integer mc = BY_VERSION.get(worldgenVersion);
        return mc == null ? OptionalInt.empty() : OptionalInt.of(mc);
    }
}
