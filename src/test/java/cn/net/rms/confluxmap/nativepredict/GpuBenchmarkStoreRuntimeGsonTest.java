package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the verdict cache round-trip on the game's bundled Gson, which on this version
 * predates record deserialization: the cache types must stay reflective no-arg classes
 * with plain fields, or every AUTO boot crashes in the client entrypoint (caught only
 * by a real client run, never by the common tests on their newer Gson).
 */
class GpuBenchmarkStoreRuntimeGsonTest {
    @Test
    void roundTripsOnTheBundledGson(@TempDir final Path tmp) {
        final GpuBenchmarkStore store = new GpuBenchmarkStore(
            tmp.resolve("gpu-benchmark.json"), LogManager.getLogger("GpuBenchmarkStoreRuntimeGsonTest"));
        final GpuBenchmarkStore.Key key = new GpuBenchmarkStore.Key(1, "CPU", 8, "GPU", 17, 27);
        store.save(new GpuBenchmarkStore.Entry(key, PredictionBackend.GPU, 4.3, 0.6, 123L));

        final GpuBenchmarkStore.Entry loaded = store.load(key).orElseThrow();
        assertEquals(PredictionBackend.GPU, loaded.winner());
        assertEquals(4.3, loaded.cpuMillisPerTile());
        assertEquals(0.6, loaded.gpuMillisPerTile());
        assertTrue(store.loadPrewarmVerdict(key, Long.MAX_VALUE).isEmpty(), "GPU verdicts never prewarm-apply");
    }
}
