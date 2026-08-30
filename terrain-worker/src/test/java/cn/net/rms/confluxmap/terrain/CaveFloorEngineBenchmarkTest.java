package cn.net.rms.confluxmap.terrain;

import cn.net.rms.confluxmap.terrain.protocol.MaterialDescriptor;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("benchmark")
final class CaveFloorEngineBenchmarkTest {
    @Test
    void cachedViewportPivotThroughput() throws Exception {
        final int height = 384;
        final int[] states = new int[height * 256];
        for (int y = 0; y < height; y++) {
            final int state = y % 24 == 0 ? 2 : 0;
            java.util.Arrays.fill(states, y * 256, (y + 1) * 256, state);
        }
        final ChunkVolume chunk = new ChunkVolume(0, 0, 1L, -64, height, states);
        final CaveFloorEngine engine = new CaveFloorEngine();
        final Map<Integer, MaterialDescriptor> materials = Map.of(
            0, new MaterialDescriptor(true, false),
            2, new MaterialDescriptor(false, false)
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
