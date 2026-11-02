package cn.net.rms.confluxmap.core.terrain;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("benchmark")
final class CaveFloorEngineBenchmarkTest {
    @Test
    void cachedViewportPivotThroughput() throws Exception {
        final int height = 384;
        final int[] states = new int[height * 256];
        // Interleaved states across layers representing typical subterranean chunk composition
        for (int y = 0; y < height; y++) {
            final int baseState = (y % 16 == 0) ? (y % 7) : 0;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final int s = (x + z) % 8 == 0 ? ((x + y) % 7) : baseState;
                    states[y * 256 + z * 16 + x] = s;
                }
            }
        }
        final ChunkVolume chunk = new ChunkVolume(0, 0, 1L, -64, height, states);
        final CaveFloorEngine engine = new CaveFloorEngine();
        final Map<Integer, MaterialDescriptor> materials = Map.of(
            0, new MaterialDescriptor(true, false),
            1, new MaterialDescriptor(false, false),
            2, new MaterialDescriptor(false, false),
            3, new MaterialDescriptor(false, false),
            4, new MaterialDescriptor(false, false),
            5, new MaterialDescriptor(true, false),
            6, new MaterialDescriptor(false, false)
        );
        for (int i = 0; i < 20; i++) {
            engine.select(chunk, 160, materials);
        }
        measure("default 100-chunk cached pivot", 100, engine, chunk, materials);
        measure("large 576-chunk cached pivot", 576, engine, chunk, materials);
    }

    private static void measure(
        final String label,
        final int chunks,
        final CaveFloorEngine engine,
        final ChunkVolume chunk,
        final Map<Integer, MaterialDescriptor> materials
    ) throws Exception {
        final long started = System.nanoTime();
        for (int i = 0; i < chunks; i++) {
            engine.select(chunk, 160, materials);
        }
        final double millis = (System.nanoTime() - started) / 1_000_000.0;
        System.out.printf("%s: %.3f ms (%.3f ms/chunk)%n", label, millis, millis / chunks);
    }
}
