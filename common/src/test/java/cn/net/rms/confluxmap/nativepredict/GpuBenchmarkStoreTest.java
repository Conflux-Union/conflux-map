package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the benchmark verdict cache: a hit needs every key field equal (hardware identity,
 * library identity, workload version), a corrupt file is quarantined and read as a miss.
 */
class GpuBenchmarkStoreTest {
    private static final Logger LOGGER = LogManager.getLogger("GpuBenchmarkStoreTest");

    private static final GpuBenchmarkStore.Key KEY = new GpuBenchmarkStore.Key(
        GpuBenchmarkStore.WORKLOAD_VERSION, "Intel i7-14700HX", 20, "NVIDIA GeForce RTX 4060", 17, 27);

    @Test
    void roundTripsAnEntry(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("gpu-benchmark.json");
        final GpuBenchmarkStore store = new GpuBenchmarkStore(file, LOGGER);
        store.save(new GpuBenchmarkStore.Entry(KEY, PredictionBackend.GPU, 120.5, 98.25, 1_700_000_000_000L));

        final Optional<GpuBenchmarkStore.Entry> loaded = store.load(KEY);
        assertTrue(loaded.isPresent());
        assertEquals(PredictionBackend.GPU, loaded.get().winner());
        assertEquals(120.5, loaded.get().cpuMillisPerTile());
        assertEquals(98.25, loaded.get().gpuMillisPerTile());
        assertEquals(1_700_000_000_000L, loaded.get().timestampMillis());
    }

    @Test
    void anyKeyFieldMismatchIsAMiss(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("gpu-benchmark.json");
        final GpuBenchmarkStore store = new GpuBenchmarkStore(file, LOGGER);
        store.save(new GpuBenchmarkStore.Entry(KEY, PredictionBackend.GPU, 1, 2, 3L));

        final Stream<GpuBenchmarkStore.Key> mismatches = Stream.of(
            new GpuBenchmarkStore.Key(2, KEY.cpuBrand, KEY.logicalProcessors, KEY.gpuDevice, KEY.cfxAbi, KEY.baselineAlgo),
            new GpuBenchmarkStore.Key(KEY.workloadVersion, "Other CPU", KEY.logicalProcessors, KEY.gpuDevice, KEY.cfxAbi, KEY.baselineAlgo),
            new GpuBenchmarkStore.Key(KEY.workloadVersion, KEY.cpuBrand, 8, KEY.gpuDevice, KEY.cfxAbi, KEY.baselineAlgo),
            new GpuBenchmarkStore.Key(KEY.workloadVersion, KEY.cpuBrand, KEY.logicalProcessors, "AMD Radeon 780M", KEY.cfxAbi, KEY.baselineAlgo),
            new GpuBenchmarkStore.Key(KEY.workloadVersion, KEY.cpuBrand, KEY.logicalProcessors, KEY.gpuDevice, 18, KEY.baselineAlgo),
            new GpuBenchmarkStore.Key(KEY.workloadVersion, KEY.cpuBrand, KEY.logicalProcessors, KEY.gpuDevice, KEY.cfxAbi, 28)
        );
        mismatches.forEach(key -> assertTrue(store.load(key).isEmpty(), "must miss for " + key));
    }

    @Test
    void missingFileIsEmpty(@TempDir final Path tmp) {
        final GpuBenchmarkStore store = new GpuBenchmarkStore(tmp.resolve("gpu-benchmark.json"), LOGGER);
        assertTrue(store.load(KEY).isEmpty());
    }

    @Test
    void quarantinesCorruptCache(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("gpu-benchmark.json");
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        final GpuBenchmarkStore store = new GpuBenchmarkStore(file, LOGGER);

        assertTrue(store.load(KEY).isEmpty());
        assertTrue(Files.exists(tmp.resolve("gpu-benchmark.json.bad")),
            "a corrupt cache must be quarantined, not left to fail every startup");
    }

    @Test
    void prewarmLoadMatchesCpuVerdictIgnoringDeviceAndAge(@TempDir final Path tmp) throws IOException {
        final Path file = tmp.resolve("gpu-benchmark.json");
        final GpuBenchmarkStore store = new GpuBenchmarkStore(file, LOGGER);
        final GpuBenchmarkStore.Key stored = new GpuBenchmarkStore.Key(
            GpuBenchmarkStore.WORKLOAD_VERSION, KEY.cpuBrand, KEY.logicalProcessors, "Radeon 780M",
            KEY.cfxAbi, KEY.baselineAlgo);

        final long dayMillis = 24L * 60 * 60 * 1000;
        store.save(new GpuBenchmarkStore.Entry(
            stored, PredictionBackend.CPU, 3, 1, System.currentTimeMillis() - 10 * dayMillis));

        // Same CPU, library, workload - the device field is ignored because the OFF engine
        // never probes a device to compare against.
        assertTrue(store.loadPrewarmVerdict(KEY, 30L * 24 * 60 * 60 * 1000).isPresent());
        // A GPU verdict must not prewarm-apply: those boots warm up and re-validate fully.
        store.save(new GpuBenchmarkStore.Entry(
            stored, PredictionBackend.GPU, 3, 9, System.currentTimeMillis() - 10 * dayMillis));
        assertTrue(store.loadPrewarmVerdict(KEY, 30L * 24 * 60 * 60 * 1000).isEmpty());
        // A stale entry (older than the window) must re-measure.
        store.save(new GpuBenchmarkStore.Entry(
            stored, PredictionBackend.CPU, 3, 1, System.currentTimeMillis() - 31 * dayMillis));
        assertTrue(store.loadPrewarmVerdict(KEY, 30L * 24 * 60 * 60 * 1000).isEmpty());
        // A different CPU must not reuse the verdict.
        final GpuBenchmarkStore.Key otherCpu = new GpuBenchmarkStore.Key(
            GpuBenchmarkStore.WORKLOAD_VERSION, "Other CPU", KEY.logicalProcessors, "Radeon 780M",
            KEY.cfxAbi, KEY.baselineAlgo);
        assertTrue(store.loadPrewarmVerdict(otherCpu, 30L * 24 * 60 * 60 * 1000).isEmpty());
    }
}
