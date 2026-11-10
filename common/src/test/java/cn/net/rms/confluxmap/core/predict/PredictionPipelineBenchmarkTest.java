package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in end-to-end tile benchmark. Unwrapped runs measure production throughput; separate
 * proxy-instrumented runs attribute sampling time to sampler calls. Proxy and nanoTime overhead
 * belongs only to the attribution runs, so their totals must not be used as throughput numbers.
 * Override confluxmap.pipelineBenchmark.output/label (or PREDICTION_PIPELINE_OUTPUT/LABEL) to
 * preserve before/after reports. Every case has two warmups and three measured runs.
 */
@Tag("benchmark")
class PredictionPipelineBenchmarkTest {
    private static final long SEED = 146008555L;
    private static final int WARMUPS = 2;
    private static final int REPEATS = 3;
    private static final int[][] ORIGINS = {{0, 0}, {4096, -8192}, {-5632, -4864}};
    private static final String[] GROUPS = {
        "overviewHeights", "overviewHeightsCoarse", "surfaceColumns", "surfaceBiomes", "biomes", "otherSampler"
    };
    private static volatile long consumedChecksum;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    @Test
    void measurePipelineStages() throws IOException {
        assertTrue(NativeLib.initForTests(), "benchmark requires a working native prediction library");
        final List<Map<String, Object>> cases = new ArrayList<>();
        final PredictionPalette palette = PredictionPalette.defaults();
        final String[] versions = setting("versions", "PREDICTION_PIPELINE_VERSIONS", "1.17.1,1.21.4").split(",");
        final int[] lods = Arrays.stream(setting("lods", "PREDICTION_PIPELINE_LODS", "0,1,2,3,4")
            .split(",")).mapToInt(Integer::parseInt).toArray();
        final int originCount = Integer.parseInt(setting("origins", "PREDICTION_PIPELINE_ORIGINS", "3"));
        assertTrue(originCount > 0 && originCount <= ORIGINS.length);
        if (THREADS.isCurrentThreadCpuTimeSupported() && !THREADS.isThreadCpuTimeEnabled()) {
            THREADS.setThreadCpuTimeEnabled(true);
        }
        for (final String version : versions) {
            final int mc = McVersions.toCubiomes(version).orElseThrow();
            final BaselineSampler nativeSampler = new NativeBaselineSampler(mc, SEED, PredictionDimensions.OVERWORLD, 0);
            final Attribution attribution = new Attribution(nativeSampler);
            for (final int lod : lods) {
                for (final int[] origin : Arrays.copyOf(ORIGINS, originCount)) {
                    for (int run = 0; run < WARMUPS; run++) {
                        pipeline(nativeSampler, lod, origin, palette);
                        pipeline(attribution.sampler, lod, origin, palette);
                    }
                    final List<Map<String, Double>> plainRuns = new ArrayList<>();
                    final List<Map<String, Double>> attributedRuns = new ArrayList<>();
                    final List<Map<String, Long>> callRuns = new ArrayList<>();
                    for (int run = 0; run < REPEATS; run++) {
                        plainRuns.add(pipeline(nativeSampler, lod, origin, palette));
                        attribution.reset();
                        final Map<String, Double> timed = pipeline(attribution.sampler, lod, origin, palette);
                        long samplerNanos = 0;
                        final Map<String, Long> calls = new LinkedHashMap<>();
                        for (int group = 0; group < GROUPS.length; group++) {
                            timed.put(GROUPS[group] + "Ms", attribution.nanos[group] / 1e6);
                            calls.put(GROUPS[group], attribution.calls[group]);
                            samplerNanos += attribution.nanos[group];
                        }
                        timed.put("samplingOutsideSamplerMs", timed.get("sampleMs") - samplerNanos / 1e6);
                        attributedRuns.add(timed);
                        callRuns.add(calls);
                    }
                    final Map<String, Object> row = new LinkedHashMap<>();
                    row.put("version", version);
                    row.put("lod", lod);
                    row.put("blockX", origin[0]);
                    row.put("blockZ", origin[1]);
                    row.put("plainMedianMs", medians(plainRuns));
                    row.put("attributedMedianMs", medians(attributedRuns));
                    row.put("plainRunsMs", plainRuns);
                    row.put("attributedRunsMs", attributedRuns);
                    row.put("samplerCallRuns", callRuns);
                    cases.add(row);
                    System.out.printf("pipeline %s LOD%d (%d,%d): %.3f ms%n", version, lod,
                        origin[0], origin[1], medians(plainRuns).get("totalMs"));
                }
            }
        }
        final Map<String, Object> report = new LinkedHashMap<>();
        report.put("label", setting("label", "PREDICTION_PIPELINE_LABEL", "current"));
        report.put("seed", SEED);
        report.put("warmupsPerCase", WARMUPS);
        report.put("measuredRunsPerCase", REPEATS);
        report.put("threadCpuTimeAvailable", THREADS.isCurrentThreadCpuTimeSupported());
        report.put("predictorVersion", PredictorVersion.full());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("timingNote", "plainMedianMs measures unwrapped production calls; attributedMedianMs uses a "
            + "dynamic proxy and nanoTime per sampler invocation and is for attribution only. Native timings "
            + "include Java adapter/JNI costs. Stage medians need not sum to the total median.");
        report.put("cases", cases);
        final Path output = outputPath();
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        System.out.println("Pipeline report: " + output.toAbsolutePath());
    }

    private static Map<String, Double> pipeline(
        final BaselineSampler sampler, final int lod, final int[] origin, final PredictionPalette palette
    ) {
        final long cpuStart = cpuTime();
        final long start = System.nanoTime();
        final BaselineGrid grid = LodSampling.sample(sampler, false, lod, origin[0], origin[1]);
        final long sampled = System.nanoTime();
        assertNotNull(grid, "native tile sampling must succeed");
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        final long derivedAt = System.nanoTime();
        CanopyStylizer.apply(derived, grid, SEED, lod, origin[0], origin[1]);
        final long canopyAt = System.nanoTime();
        final int[] pixels = PredictedTileComposer.compose(
            derived, grid, palette, null, PredictionViewMode.EVERYWHERE, lod);
        final long composed = System.nanoTime();
        consumedChecksum ^= pixels[0] ^ pixels[pixels.length / 2] ^ pixels[pixels.length - 1];
        final Map<String, Double> result = new LinkedHashMap<>();
        if (cpuStart >= 0) {
            result.put("totalThreadCpuMs", (cpuTime() - cpuStart) / 1e6);
        }
        result.put("sampleMs", (sampled - start) / 1e6);
        result.put("deriveMs", (derivedAt - sampled) / 1e6);
        result.put("canopyMs", (canopyAt - derivedAt) / 1e6);
        result.put("composeMs", (composed - canopyAt) / 1e6);
        result.put("totalMs", (composed - start) / 1e6);
        return result;
    }

    private static long cpuTime() {
        return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1L;
    }

    private static Map<String, Double> medians(final List<Map<String, Double>> runs) {
        final Map<String, Double> medians = new LinkedHashMap<>();
        for (final String key : runs.get(0).keySet()) {
            final double[] values = runs.stream().mapToDouble(run -> run.get(key)).toArray();
            Arrays.sort(values);
            medians.put(key, values[values.length / 2]);
        }
        return medians;
    }

    private static String setting(final String property, final String environment, final String fallback) {
        final String fromEnvironment = System.getenv(environment);
        return System.getProperty("confluxmap.pipelineBenchmark." + property,
            fromEnvironment == null || fromEnvironment.isBlank() ? fallback : fromEnvironment);
    }

    private static Path outputPath() {
        final Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        final Path common = working.getFileName().toString().equals("common") ? working : working.resolve("common");
        return Path.of(setting("output", "PREDICTION_PIPELINE_OUTPUT",
            common.resolve("build/reports/prediction-pipeline/stages.json").toString()));
    }

    private static final class Attribution {
        final long[] nanos = new long[GROUPS.length];
        final long[] calls = new long[GROUPS.length];
        final BaselineSampler sampler;

        Attribution(final BaselineSampler delegate) {
            sampler = (BaselineSampler) Proxy.newProxyInstance(BaselineSampler.class.getClassLoader(),
                new Class<?>[] {BaselineSampler.class}, (proxy, method, args) -> {
                    final String name = method.getName();
                    final int group = name.equals("overviewHeights") ? 0
                        : name.equals("overviewHeightsCoarse") ? 1 : name.equals("surfaceColumns") ? 2
                        : name.equals("surfaceBiomes") ? 3 : name.startsWith("biomes") ? 4 : 5;
                    final long start = System.nanoTime();
                    try {
                        return method.invoke(delegate, args);
                    } catch (final InvocationTargetException fault) {
                        throw fault.getCause();
                    } finally {
                        nanos[group] += System.nanoTime() - start;
                        calls[group]++;
                    }
                });
        }

        void reset() {
            Arrays.fill(nanos, 0);
            Arrays.fill(calls, 0);
        }
    }
}
