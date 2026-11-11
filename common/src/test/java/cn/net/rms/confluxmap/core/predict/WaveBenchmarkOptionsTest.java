package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link WaveBenchmarkOptions} property parsing: the shipped defaults reproduce the historical
 * fixed matrix exactly, explicit overrides win, and garbage input falls back to the default
 * instead of poisoning a benchmark run with a half-parsed configuration.
 */
class WaveBenchmarkOptionsTest {
    @BeforeEach
    void clearBenchmarkProperties() {
        clear();
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private static void clear() {
        System.clearProperty("confluxmap.bench.scenario");
        System.clearProperty("confluxmap.bench.seed");
        System.clearProperty("confluxmap.bench.originX");
        System.clearProperty("confluxmap.bench.originZ");
        System.clearProperty("confluxmap.bench.lods");
        System.clearProperty("confluxmap.bench.sides");
        System.clearProperty("confluxmap.bench.rounds");
        System.clearProperty("confluxmap.bench.warmup");
        System.clearProperty("confluxmap.bench.order");
    }

    @Test
    void defaultsReproduceTheHistoricalFixedMatrix() {
        assertEquals(146008555L, WaveBenchmarkOptions.seed());
        assertEquals(0, WaveBenchmarkOptions.originTileX());
        assertEquals(0, WaveBenchmarkOptions.originTileZ());
        assertArrayEquals(new int[] {1, 2, 3, 4}, WaveBenchmarkOptions.lods());
        assertArrayEquals(new int[] {1, 3, 5}, WaveBenchmarkOptions.sides());
        assertEquals(3, WaveBenchmarkOptions.measuredRounds());
        assertEquals(1, WaveBenchmarkOptions.warmupRounds());
        assertFalse(WaveBenchmarkOptions.cpuFirst(), "historical order is GPU leg first");
    }

    @Test
    void explicitOverridesWin() {
        System.setProperty("confluxmap.bench.seed", "99");
        System.setProperty("confluxmap.bench.originX", "160");
        System.setProperty("confluxmap.bench.originZ", "-128");
        System.setProperty("confluxmap.bench.lods", "2,4");
        System.setProperty("confluxmap.bench.sides", "3");
        System.setProperty("confluxmap.bench.rounds", "7");
        System.setProperty("confluxmap.bench.warmup", "3");
        System.setProperty("confluxmap.bench.order", "cpu-first");

        assertEquals(99L, WaveBenchmarkOptions.seed());
        assertEquals(160, WaveBenchmarkOptions.originTileX());
        assertEquals(-128, WaveBenchmarkOptions.originTileZ());
        assertArrayEquals(new int[] {2, 4}, WaveBenchmarkOptions.lods());
        assertArrayEquals(new int[] {3}, WaveBenchmarkOptions.sides());
        assertEquals(7, WaveBenchmarkOptions.measuredRounds());
        assertEquals(3, WaveBenchmarkOptions.warmupRounds());
        assertTrue(WaveBenchmarkOptions.cpuFirst());
    }

    @Test
    void garbageValuesFallBackToDefaults() {
        System.setProperty("confluxmap.bench.seed", "not-a-number");
        System.setProperty("confluxmap.bench.originX", "99999999999999");
        System.setProperty("confluxmap.bench.lods", "1,oops,4");
        System.setProperty("confluxmap.bench.rounds", "0");
        System.setProperty("confluxmap.bench.warmup", "-3");
        System.setProperty("confluxmap.bench.order", "sideways");

        assertEquals(146008555L, WaveBenchmarkOptions.seed(), "garbage seed falls back");
        assertEquals(0, WaveBenchmarkOptions.originTileX(), "out-of-range origin falls back");
        assertArrayEquals(new int[] {1, 4}, WaveBenchmarkOptions.lods(), "bad entries are skipped");
        assertEquals(3, WaveBenchmarkOptions.measuredRounds(), "rounds below 1 fall back");
        assertEquals(1, WaveBenchmarkOptions.warmupRounds(), "negative warmup falls back");
        assertFalse(WaveBenchmarkOptions.cpuFirst(), "unknown order falls back to gpu-first");
    }

    @Test
    void scenarioPresetPinsSeedAndOrigin() {
        System.setProperty("confluxmap.bench.scenario", "default");
        assertEquals(WaveBenchmarkOptions.DEFAULT_SCENARIO, WaveBenchmarkOptions.scenario());

        System.setProperty("confluxmap.bench.scenario", "no-such-scenario");
        assertEquals(WaveBenchmarkOptions.DEFAULT_SCENARIO, WaveBenchmarkOptions.scenario());
        for (final WaveBenchmarkOptions.Scenario preset : WaveBenchmarkOptions.SCENARIOS.values()) {
            assertTrue(preset.seed() != 0L, "every preset pins a seed");
        }
    }

    @Test
    void scenarioPresetsCoverCoastInlandAndMixed() {
        for (final String name : new String[] {"coast", "inland", "mixed"}) {
            final WaveBenchmarkOptions.Scenario preset = WaveBenchmarkOptions.SCENARIOS.get(name);
            assertTrue(preset != null, "preset \"" + name + "\" must exist");
            assertTrue(preset.seed() != 0L, "preset \"" + name + "\" must pin a seed");
            System.setProperty("confluxmap.bench.scenario", name);
            assertEquals(preset, WaveBenchmarkOptions.scenario(), "preset \"" + name + "\" must resolve");
        }
    }

    @Test
    void explicitSeedOverridesScenario() {
        System.setProperty("confluxmap.bench.scenario", "coast");
        System.setProperty("confluxmap.bench.seed", "7");
        assertEquals(7L, WaveBenchmarkOptions.seed(), "an explicit seed beats the scenario preset");
    }
}
