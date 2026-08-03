package cn.net.rms.confluxmap.nativepredict;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a worldgen version string (as reported by a server handshake, e.g. {@code "1.17.1"})
 * to the {@code enum MCVersion} integer the vendored cubiomes commit expects in
 * {@code setupGenerator}/{@code getStructurePos}/etc.
 *
 * <p>cubiomes only gives a dedicated enum value to a minecraft release when its worldgen
 * actually changed; every other patch of that minor version reuses the constant of the last
 * release that did change. The keys here are <em>real release strings</em>, so each one maps to
 * the constant whose worldgen that release actually runs. That is deliberately not the same as
 * copying cubiomes' own {@code MC_1_19 = MC_1_19_4} style aliases: those aliases are labels for
 * "the newest patch of that line", and real {@code 1.19} does not generate like {@code 1.19.4}.
 * The authoritative per-release grouping is cubiomes' own {@code str2mc} in {@code util.c},
 * cross-checked against the {@code enum MCVersion} comments in {@code native/cubiomes/biomes.h}
 * at {@link PredictorVersion#CUBIOMES_COMMIT_12}.
 *
 * <p>Patch releases newer than anything in the table fall back to the newest constant known for
 * their minor line (see {@link #toCubiomes}), matching cubiomes' stated policy of tracking only
 * the newest patch of each release. That keeps prediction alive on a patch released after this
 * table was written, at the cost of being wrong if that patch does change worldgen - the
 * predicted underlay is a guess that live chunk data overwrites anyway, so degrading to a stale
 * generator beats switching prediction off entirely.
 */
public final class McVersions {
    /** One player-selectable worldgen family; every release in its label shares one native generator. */
    public record Selection(String worldgenVersion, String label, int cubiomesVersion) {
    }

    private static final Map<String, Integer> BY_VERSION = new ConcurrentHashMap<>();
    /** Newest cubiomes constant known for each {@code major.minor} line, for the patch fallback. */
    private static final Map<String, Integer> NEWEST_BY_LINE = new ConcurrentHashMap<>();
    private static final List<Selection> SELECTIONS;

    static {
        final List<Selection> selections = new ArrayList<>();
        putSelection(selections, 1, "1.7-1.7.10", "1.7", "1.7.10");
        putSelection(selections, 11, "1.8-1.8.9", "1.8", "1.8.9");
        putSelection(selections, 12, "1.9-1.9.4", "1.9", "1.9.1", "1.9.2", "1.9.3", "1.9.4");
        putSelection(selections, 13, "1.10-1.10.2", "1.10", "1.10.2");
        putSelection(selections, 14, "1.11-1.11.2", "1.11", "1.11.2");
        putSelection(selections, 15, "1.12-1.12.2", "1.12", "1.12.1", "1.12.2");
        putSelection(selections, 16, "1.13-1.13.2", "1.13", "1.13.1", "1.13.2");
        putSelection(selections, 17, "1.14-1.14.4", "1.14", "1.14.1", "1.14.2", "1.14.3", "1.14.4");
        putSelection(selections, 18, "1.15-1.15.2", "1.15", "1.15.1", "1.15.2");
        // 1.16.2 reworked biome placement; 1.16 and 1.16.1 still generate like MC_1_16_1.
        putSelection(selections, 19, "1.16-1.16.1", "1.16", "1.16.1");
        putSelection(selections, 20, "1.16.2-1.16.5", "1.16.2", "1.16.3", "1.16.4", "1.16.5");
        putSelection(selections, 21, "1.17-1.17.1", "1.17", "1.17.1");
        putSelection(selections, 22, "1.18-1.18.2", "1.18", "1.18.1", "1.18.2");
        // MC_1_19_2 covers the 1.19-1.19.2 line; the climate tree changed again in 1.19.3.
        putSelection(selections, 23, "1.19-1.19.2", "1.19", "1.19.1", "1.19.2");
        putSelection(selections, 24, "1.19.3-1.19.4", "1.19.3", "1.19.4");
        putSelection(
            selections, 25, "1.20-1.20.6",
            "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6"
        );
        putSelection(selections, 26, "1.21-1.21.1", "1.21", "1.21.1");
        // cubiomes renamed its MC_1_21_2 constant to MC_1_21_3 and kept "1.21.2" pointing at it.
        putSelection(selections, 27, "1.21.2-1.21.3", "1.21.2", "1.21.3");
        // MC_1_21_WD: pale garden (winter drop).
        putSelection(selections, 28, "1.21.4", "1.21.4");
        // MC_1_21_5: pale garden placement widened in 25w02a; unchanged through 1.21.11.
        putSelection(
            selections, 29, "1.21.5-1.21.11",
            "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
        );
        // 26.1 keeps 1.21.11 worldgen but has its own cubiomes enum entry and version line.
        putSelection(selections, 30, "26.1-26.1.2", "26.1", "26.1.1", "26.1.2");
        // 26.2 adds the Chaos Cubed worldgen family, including sulfur caves.
        putSelection(selections, 31, "26.2", "26.2");
        SELECTIONS = List.copyOf(selections);
    }

    private McVersions() {
    }

    private static void put(final int mc, final String... versions) {
        for (final String version : versions) {
            BY_VERSION.put(version, mc);
            NEWEST_BY_LINE.merge(lineOf(version), mc, Math::max);
        }
    }

    private static void putSelection(
        final List<Selection> selections,
        final int mc,
        final String label,
        final String... versions
    ) {
        put(mc, versions);
        selections.add(new Selection(versions[versions.length - 1], label, mc));
    }

    /** {@code "1.21.11" -> "1.21"}, {@code "1.21" -> "1.21"}; anything unparseable maps to itself. */
    private static String lineOf(final String version) {
        final int first = version.indexOf('.');
        if (first < 0) {
            return version;
        }
        final int second = version.indexOf('.', first + 1);
        return second < 0 ? version : version.substring(0, second);
    }

    /**
     * Looks up the cubiomes {@code MCVersion} int for a worldgen version string. An exact match
     * wins; otherwise a patch release of a known minor line resolves to that line's newest known
     * constant. Empty when the minor line itself is unknown (a future 1.22, a modded string).
     */
    public static OptionalInt toCubiomes(final String worldgenVersion) {
        final Integer exact = BY_VERSION.get(worldgenVersion);
        if (exact != null) {
            return OptionalInt.of(exact);
        }
        final Integer line = NEWEST_BY_LINE.get(lineOf(worldgenVersion));
        return line == null ? OptionalInt.empty() : OptionalInt.of(line);
    }

    /** Ordered oldest-to-newest choices shown by the manual seed screen. */
    public static List<Selection> selections() {
        return SELECTIONS;
    }

    /** Finds the selectable family for an exact or compatible patch version. */
    public static int selectionIndex(final String worldgenVersion) {
        final OptionalInt mapped = toCubiomes(worldgenVersion);
        if (mapped.isPresent()) {
            for (int index = 0; index < SELECTIONS.size(); index++) {
                if (SELECTIONS.get(index).cubiomesVersion() == mapped.getAsInt()) {
                    return index;
                }
            }
        }
        return SELECTIONS.size() - 1;
    }
}
