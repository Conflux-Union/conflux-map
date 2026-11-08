package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.tile.TileUpdate;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measures the production request/worker/composition/upload-queue path for a fixed 4x4 viewport.
 * The render-thread consumer is simulated at 60 Hz, with the production eight-upload frame cap.
 * NativeImage writes, GPU transfers, actual drawing, and network/server latency are excluded.
 *
 * <p>One unrecorded warmup and three measured rounds each start with a new service/cache and four
 * workers. The same service then serves an exact-cache re-request, one absolute correction per
 * tile, and validation of those unchanged corrections. Native contexts and the JVM may remain
 * warm: "cold" means an empty tile cache, not process startup. Set {@code
 * confluxmap.viewportBenchmarkOutput} (in the test JVM) or {@code
 * CONFLUX_VIEWPORT_BENCHMARK_OUTPUT} to preserve separate before/after reports.
 */
@Tag("benchmark")
class PredictionViewportBenchmarkTest {
    private static final String VERSION = "1.21.4";
    private static final long SEED = 146008555L;
    private static final int WORKERS = 4;
    private static final int SIDE = 4;
    private static final int TILE_COUNT = SIDE * SIDE;
    private static final int WARMUP_ROUNDS = 1;
    private static final int MEASURED_ROUNDS = 3;
    private static final long FRAME_NANOS = 1_000_000_000L / 60;
    private static final int UPLOADS_PER_FRAME = 8;
    private static final long TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(3);
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("prediction-viewport-benchmark");

    @Test
    void measureViewportUploadReadiness(@TempDir final Path correctionRoot) throws IOException {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable");
        final int version = McVersions.toCubiomes(VERSION).orElseThrow();
        final List<Measurement> measurements = new ArrayList<>();
        for (final int lod : new int[] {0, 2, 4}) {
            for (int round = -WARMUP_ROUNDS; round < MEASURED_ROUNDS; round++) {
                final MapExecutors executors = new MapExecutors(WORKERS);
                try {
                    final SessionGuard guard = new SessionGuard();
                    final SessionGuard.Session session = guard.begin(WORLD, DIM);
                    final MapWorldService worlds = new MapWorldService();
                    worlds.switchSession(session);
                    final TileService uploads = new TileService(
                        worlds, executors, new ConfluxConfig(), new DaylightModel()
                    );
                    final PredictionState state = new PredictionState();
                    state.setSeed(SEED, version);
                    final PredictionTileService service = new PredictionTileService(
                        guard, state, executors, uploads
                    );
                    final CorrectionStore store = new CorrectionStore(
                        correctionRoot.resolve("lod-" + lod + "-round-" + round)
                    );
                    store.onSessionChanged(session);
                    service.bindCorrectionStore(store);
                    service.setViewport(DIM, lod, 0, SIDE - 1, 0, SIDE - 1);
                    final List<TileKey> keys = viewportKeys(lod);

                    final Measurement cold = measure("cold", lod, round, service, uploads, keys,
                        () -> keys.forEach(service::requestTile));
                    final Measurement warm = measure("warmExact", lod, round, service, uploads, keys,
                        () -> keys.forEach(service::requestTile));
                    assertEquals(cold.pixelHashes(), warm.pixelHashes(),
                        "exact-cache pixels must reproduce the cold viewport");

                    final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
                    final PatchCodec.Patch patch = new PatchCodec.Patch(List.of(
                        new PatchCodec.Sample(0, 2, 100, SurfaceKind.SAND.ordinal(), 2, 0)
                    ));
                    final Measurement corrected = measure("correctionUpdate", lod, round,
                        service, uploads, keys, () -> {
                            for (final TileKey key : keys) {
                                assertTrue(service.applyCorrection(correctionKey(key), 1L, presence,
                                    patch, Proto.PATCH_MODE_ABSOLUTE, PredictorVersion.full(),
                                    System.currentTimeMillis()));
                            }
                        });
                    final Measurement validated = measure("validationOnly", lod, round,
                        service, uploads, keys, () -> {
                            for (final TileKey key : keys) {
                                assertTrue(service.validateCorrection(correctionKey(key), 1L,
                                    presence, System.currentTimeMillis()));
                            }
                        });
                    assertEquals(corrected.pixelHashes(), validated.pixelHashes(),
                        "validation must preserve corrected pixels");
                    if (round >= 0) {
                        measurements.addAll(List.of(cold, warm, corrected, validated));
                    }
                    System.out.printf(Locale.ROOT,
                        "viewport lod=%d round=%d cold=%.1f warm=%.1f correction=%.1f validation=%.1f ms%n",
                        lod, round, cold.allMs(), warm.allMs(), corrected.allMs(), validated.allMs());
                } finally {
                    executors.shutdown(10_000L);
                }
            }
        }
        final Path output = resultsPath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, json(measurements));
        System.out.println("viewport benchmark results: " + output);
    }

    private static Measurement measure(
        final String scenario,
        final int lod,
        final int round,
        final PredictionTileService service,
        final TileService uploads,
        final List<TileKey> keys,
        final Runnable request
    ) {
        assertTrue(service.isIdleForTest(), "preceding viewport must finish before measurement");
        assertTrue(uploads.drainUploads(Integer.MAX_VALUE).isEmpty(), "no preceding upload may leak");
        final Map<TileKey, Integer> hashes = new HashMap<>();
        final long start = System.nanoTime();
        request.run();
        long nextFrame = start + FRAME_NANOS;
        long first = 0L;
        long halfway = 0L;
        long all = 0L;
        while (hashes.size() < keys.size()) {
            final long now = System.nanoTime();
            assertTrue(now - start < TIMEOUT_NANOS,
                "viewport timed out: " + scenario + " LOD" + lod + " got " + hashes.size());
            if (now < nextFrame) {
                LockSupport.parkNanos(nextFrame - now);
                continue;
            }
            final List<TileUpdate> updates = uploads.drainUploads(UPLOADS_PER_FRAME);
            final long elapsed = System.nanoTime() - start;
            for (final TileUpdate update : updates) {
                assertTrue(keys.contains(update.key()), "only the fixed viewport is requested");
                hashes.put(update.key(), Arrays.hashCode(update.argbPixels()));
            }
            if (first == 0L && !hashes.isEmpty()) {
                first = elapsed;
            }
            if (halfway == 0L && hashes.size() >= TILE_COUNT / 2) {
                halfway = elapsed;
            }
            if (hashes.size() == TILE_COUNT) {
                all = elapsed;
            }
            // A late simulated render frame cannot consume the missed frames retroactively.
            nextFrame += FRAME_NANOS;
            final long afterDrain = System.nanoTime();
            if (nextFrame <= afterDrain) {
                nextFrame = afterDrain + FRAME_NANOS;
            }
        }
        // composeAndFinish publishes before returning from its worker; allow only that tail to end.
        while (!service.isIdleForTest()) {
            assertTrue(System.nanoTime() - start < TIMEOUT_NANOS, "composition did not finish");
            LockSupport.parkNanos(100_000L);
        }
        return new Measurement(scenario, lod, round, first / 1e6, halfway / 1e6, all / 1e6, hashes);
    }

    private static List<TileKey> viewportKeys(final int lod) {
        final List<TileKey> keys = new ArrayList<>(TILE_COUNT);
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                keys.add(new TileKey(WORLD, DIM, "surface!pred", lod, x, z));
            }
        }
        return keys;
    }

    private static CorrectionStore.Key correctionKey(final TileKey key) {
        return new CorrectionStore.Key(DIM.toString(), key.lod(), key.tileX(), key.tileZ());
    }

    private static Path resultsPath() {
        final String configured = System.getProperty("confluxmap.viewportBenchmarkOutput",
            System.getenv("CONFLUX_VIEWPORT_BENCHMARK_OUTPUT"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        final Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        final Path common = working.getFileName().toString().equals("common")
            ? working : working.resolve("common");
        return common.resolve("build/tile-cost/viewport-results.json");
    }

    private static String json(final List<Measurement> measurements) {
        final List<String> rows = new ArrayList<>();
        for (final Measurement row : measurements) {
            rows.add(String.format(Locale.ROOT,
                "    {\"scenario\":\"%s\",\"lod\":%d,\"round\":%d,\"firstMs\":%.3f,\"halfMs\":%.3f,\"allMs\":%.3f,\"pixelHash\":%d}",
                row.scenario(), row.lod(), row.round(), row.firstMs(), row.halfMs(), row.allMs(),
                row.pixelHashes().hashCode()));
        }
        final List<String> summaries = new ArrayList<>();
        for (final int lod : new int[] {0, 2, 4}) {
            for (final String scenario : List.of("cold", "warmExact", "correctionUpdate", "validationOnly")) {
                final List<Measurement> matching = measurements.stream()
                    .filter(row -> row.lod() == lod && row.scenario().equals(scenario)).toList();
                summaries.add(String.format(Locale.ROOT,
                    "    {\"scenario\":\"%s\",\"lod\":%d,\"medianFirstMs\":%.3f,\"medianHalfMs\":%.3f,\"medianAllMs\":%.3f}",
                    scenario, lod, median(matching.stream().mapToDouble(Measurement::firstMs).toArray()),
                    median(matching.stream().mapToDouble(Measurement::halfMs).toArray()),
                    median(matching.stream().mapToDouble(Measurement::allMs).toArray())));
            }
        }
        return "{\n  \"createdAt\":\"" + Instant.now() + "\",\n"
            + "  \"version\":\"" + VERSION + "\",\n  \"seed\":" + SEED + ",\n"
            + "  \"workers\":" + WORKERS + ",\n  \"viewportTiles\":" + TILE_COUNT + ",\n"
            + "  \"frameHz\":60,\n  \"uploadsPerFrame\":" + UPLOADS_PER_FRAME + ",\n"
            + "  \"warmupRounds\":" + WARMUP_ROUNDS + ",\n  \"measuredRounds\":" + MEASURED_ROUNDS + ",\n"
            + "  \"includesGpuUpload\":false,\n"
            + "  \"coldMeans\":\"empty service tile cache; JVM and native contexts may be warm\",\n"
            + "  \"javaVersion\":\"" + System.getProperty("java.version") + "\",\n"
            + "  \"availableProcessors\":" + Runtime.getRuntime().availableProcessors() + ",\n"
            + "  \"predictor\":\"" + PredictorVersion.full() + "\",\n"
            + "  \"summaries\":[\n" + String.join(",\n", summaries) + "\n  ],\n"
            + "  \"runs\":[\n" + String.join(",\n", rows) + "\n  ]\n}\n";
    }

    private static double median(final double[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }

    private record Measurement(
        String scenario, int lod, int round, double firstMs, double halfMs, double allMs,
        Map<TileKey, Integer> pixelHashes
    ) {
    }
}
