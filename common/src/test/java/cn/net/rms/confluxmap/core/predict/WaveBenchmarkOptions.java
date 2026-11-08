package cn.net.rms.confluxmap.core.predict;

import java.util.Locale;
import java.util.Map;

/**
 * System-property configuration of {@link WaveCompositionBenchmarkTest}: fixed-seed/origin
 * scenarios, viewport filters, round counts, and backend ordering, all with the historical
 * defaults baked in so an unconfigured run reproduces the original matrix. Garbage input falls
 * back to the default per field (with a note on stdout) instead of failing the run - a benchmark
 * harness should measure, not reject.
 *
 * <p>Properties: {@code confluxmap.bench.scenario} ({@code coast}/{@code inland}/{@code mixed}
 * pin seed+origin together), {@code confluxmap.bench.seed}, {@code confluxmap.bench.originX},
 * {@code confluxmap.bench.originZ} (tile coordinates of the viewport square's corner),
 * {@code confluxmap.bench.lods}, {@code confluxmap.bench.sides} (comma-separated),
 * {@code confluxmap.bench.rounds} (measured rounds per cell), {@code confluxmap.bench.warmup},
 * and {@code confluxmap.bench.order} ({@code gpu-first}, the historical order, or {@code
 * cpu-first} to alternate the leg order across independent trials). An explicit seed or origin
 * always beats the scenario preset.
 */
final class WaveBenchmarkOptions {
    /** A named fixed (seed, origin) pairing; {@code default} is the historical benchmark cell. */
    record Scenario(String name, long seed, int originTileX, int originTileZ) {
    }

    static final Scenario DEFAULT_SCENARIO =
        new Scenario("default", 146008555L, 0, 0);

    /**
     * Probe-picked presets (seed 146008555, {@code BenchScenarioProbeTest} measured a 128-block
     * window per origin): {@code coast} (-32,-16) holds 58% water and 12 distinct surface
     * biomes, {@code inland} (0,-32) holds 0.5% water over 14 biomes, and {@code mixed} (-288,64)
     * holds 32% water over 17 biomes - the coastline/inland/mixed-biome spread the benchmark
     * matrix wants covered.
     */
    static final Map<String, Scenario> SCENARIOS = Map.of(
        "default", DEFAULT_SCENARIO,
        "coast", new Scenario("coast", 146008555L, -32, -16),
        "inland", new Scenario("inland", 146008555L, 0, -32),
        "mixed", new Scenario("mixed", 146008555L, -288, 64)
    );

    private static final String PREFIX = "confluxmap.bench.";

    private WaveBenchmarkOptions() {
    }

    static Scenario scenario() {
        final String name = System.getProperty(PREFIX + "scenario");
        if (name == null) {
            return DEFAULT_SCENARIO;
        }
        final Scenario preset = SCENARIOS.get(name.toLowerCase(Locale.ROOT));
        if (preset != null) {
            return preset;
        }
        System.out.println("[wave-bench] unknown scenario \"" + name + "\", using default");
        return DEFAULT_SCENARIO;
    }

    static long seed() {
        final String raw = System.getProperty(PREFIX + "seed");
        if (raw == null) {
            return scenario().seed();
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (final NumberFormatException e) {
            note("seed", raw);
            return scenario().seed();
        }
    }

    static int originTileX() {
        return tileCoordinate("originX");
    }

    static int originTileZ() {
        return tileCoordinate("originZ");
    }

    private static int tileCoordinate(final String key) {
        final String raw = System.getProperty(PREFIX + key);
        if (raw == null) {
            final Scenario preset = scenario();
            return "originX".equals(key) ? preset.originTileX() : preset.originTileZ();
        }
        try {
            final int value = Integer.parseInt(raw.trim());
            if (value >= -30_000_000 && value <= 30_000_000) {
                return value;
            }
        } catch (final NumberFormatException e) {
            // unparsable: the default note below logs it
        }
        note(key, raw);
        final Scenario preset = scenario();
        return "originX".equals(key) ? preset.originTileX() : preset.originTileZ();
    }

    static int[] lods() {
        return intList("lods", new int[] {1, 2, 3, 4}, 0, 4);
    }

    static int[] sides() {
        return intList("sides", new int[] {1, 3, 5}, 1, 64);
    }

    static int measuredRounds() {
        return bounded("rounds", 3, 1, 1000);
    }

    static int warmupRounds() {
        return bounded("warmup", 1, 0, 100);
    }

    /** False = the historical GPU-leg-first order; true alternates it across independent runs. */
    static boolean cpuFirst() {
        final String raw = System.getProperty(PREFIX + "order");
        return raw != null && raw.trim().equalsIgnoreCase("cpu-first");
    }

    private static int bounded(final String key, final int fallback, final int min, final int max) {
        final String raw = System.getProperty(PREFIX + key);
        if (raw == null) {
            return fallback;
        }
        try {
            final int value = Integer.parseInt(raw.trim());
            if (value >= min && value <= max) {
                return value;
            }
        } catch (final NumberFormatException e) {
            // unparsable: the default note below logs it
        }
        note(key, raw);
        return fallback;
    }

    /** Comma-separated ints; unparseable entries are skipped, an empty result falls back. */
    private static int[] intList(final String key, final int[] fallback, final int min, final int max) {
        final String raw = System.getProperty(PREFIX + key);
        if (raw == null) {
            return fallback;
        }
        final java.util.List<Integer> parsed = new java.util.ArrayList<>();
        for (final String part : raw.split(",")) {
            try {
                final int value = Integer.parseInt(part.trim());
                if (value >= min && value <= max) {
                    parsed.add(value);
                }
            } catch (final NumberFormatException e) {
                System.out.println("[wave-bench] skipping unparsable " + key + " entry \"" + part + "\"");
            }
        }
        if (parsed.isEmpty()) {
            note(key, raw);
            return fallback;
        }
        final int[] values = new int[parsed.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = parsed.get(i);
        }
        return values;
    }

    private static void note(final String key, final String raw) {
        System.out.println("[wave-bench] ignoring unparsable " + key + " \"" + raw + "\", using default");
    }
}
