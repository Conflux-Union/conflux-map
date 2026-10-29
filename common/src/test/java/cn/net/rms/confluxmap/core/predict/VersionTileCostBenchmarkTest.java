package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measures the wall-clock cost of one full {@link LodSampling#sample} Overworld tile per LOD for
 * the two generator eras the prediction pipeline spans (pre-1.18 layer stacks vs the 1.18+
 * climate model). The tile cost drives how many tiles a worker can keep warm per frame budget,
 * so it is tracked as a number, not guessed. Results land in
 * {@code common/build/tile-cost/results.json} for comparison across runs.
 */
@Tag("benchmark")
class VersionTileCostBenchmarkTest {
    private static final long SEED = 146008555L;
    private static final String[] VERSIONS = {"1.17.1", "1.21.4"};
    private static final int WARMUP_RUNS = 1;
    private static final int MEASURED_RUNS = 3;
    private static final int TILE_ORIGIN = 0;

    @BeforeEach
    void requireNative() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
    }

    @Test
    void measureTileCostPerVersionAndLod() throws IOException {
        final List<String> rows = new ArrayList<>();
        for (final String version : VERSIONS) {
            final OptionalInt mc = McVersions.toCubiomes(version);
            assertTrue(mc.isPresent(), "McVersions must know \"" + version + "\"");
            final NativeBaselineSampler sampler = new NativeBaselineSampler(
                mc.getAsInt(), SEED, PredictionDimensions.OVERWORLD, 0
            );
            for (int lod = 0; lod <= 4; lod++) {
                for (int run = 0; run < WARMUP_RUNS; run++) {
                    assertNotNull(LodSampling.sample(sampler, false, lod, TILE_ORIGIN, TILE_ORIGIN),
                        "warmup sampling must succeed");
                }
                final long[] nanos = new long[MEASURED_RUNS];
                for (int run = 0; run < MEASURED_RUNS; run++) {
                    final long start = System.nanoTime();
                    assertNotNull(LodSampling.sample(sampler, false, lod, TILE_ORIGIN, TILE_ORIGIN),
                        "measured sampling must succeed");
                    nanos[run] = System.nanoTime() - start;
                }
                Arrays.sort(nanos);
                final double medianMs = nanos[MEASURED_RUNS / 2] / 1e6;
                rows.add(String.format(
                    Locale.ROOT, "{\"version\":\"%s\",\"lod\":%d,\"medianMs\":%.1f}",
                    version, lod, medianMs
                ));
            }
        }
        final String json = "[\n" + String.join(",\n", rows) + "\n]\n";
        final Path results = resultsPath();
        Files.createDirectories(results.getParent());
        Files.write(results, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Gradle runs tests with the owning project directory as the working directory, so the
     * results land in {@code common/build/tile-cost/}; IDE runs from the repository root resolve
     * the same directory instead of scattering a stray tree elsewhere.
     */
    private static Path resultsPath() {
        final Path workingDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (workingDir.getFileName().toString().equals("common")
            && Files.isRegularFile(workingDir.resolve("build.gradle"))) {
            return workingDir.resolve("build").resolve("tile-cost").resolve("results.json");
        }
        final Path common = workingDir.resolve("common");
        assertTrue(Files.isRegularFile(common.resolve("build.gradle")),
            "cannot locate the common project directory from " + workingDir);
        return common.resolve("build").resolve("tile-cost").resolve("results.json");
    }
}
