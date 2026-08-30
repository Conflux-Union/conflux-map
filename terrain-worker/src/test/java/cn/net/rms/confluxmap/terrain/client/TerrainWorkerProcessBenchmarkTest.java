package cn.net.rms.confluxmap.terrain.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.terrain.PalettedPackets;
import cn.net.rms.confluxmap.terrain.protocol.EncodedChunk;
import cn.net.rms.confluxmap.terrain.protocol.EncodedSection;
import cn.net.rms.confluxmap.terrain.protocol.MaterialDescriptor;
import cn.net.rms.confluxmap.terrain.protocol.TerrainResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("benchmark")
final class TerrainWorkerProcessBenchmarkTest {
    @Test
    void refreshesLargeTallViewport() throws Exception {
        final List<EncodedSection> sections = sections();
        final long started = System.nanoTime();
        try (TerrainWorkerProcess process = TerrainWorkerProcess.launch(
            javaExecutable(), workerJar()
        )) {
            process.updateView(41L, 1L, 64, 0, 23, 0, 23);
            process.submitMaterials(Map.of(
                0, new MaterialDescriptor(true, false),
                2, new MaterialDescriptor(false, false),
                3, new MaterialDescriptor(false, true)
            ));
            for (int z = 0; z < 24; z++) {
                for (int x = 0; x < 24; x++) {
                    process.submit(new EncodedChunk(
                        41L, 1L, x, z, -4, 19, 8, 15, 0, sections
                    ));
                }
            }

            final Set<Long> completed = new HashSet<>();
            final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (completed.size() < 576 && System.nanoTime() < deadline) {
                final TerrainResult result = process.awaitResult(Duration.ofMillis(100));
                if (result != null) {
                    completed.add(((long) result.result().chunkX() << 32)
                        ^ (result.result().chunkZ() & 0xFFFFFFFFL));
                }
            }
            final double millis = (System.nanoTime() - started) / 1_000_000.0;
            System.out.printf("576-chunk process refresh: %.3f ms%n", millis);
            assertTrue(process.isHealthy(), process.fault());
            assertEquals(576, completed.size());
        }
    }

    private static List<EncodedSection> sections() throws Exception {
        final List<EncodedSection> result = new ArrayList<>();
        for (int sectionY = -4; sectionY <= 19; sectionY++) {
            final int[] states = new int[4096];
            for (int y = 0; y < 16; y++) {
                final int worldY = sectionY * 16 + y;
                final int state = Math.floorMod(worldY, 12) == 0 ? 2
                    : Math.floorMod(worldY, 7) == 0 ? 3 : 0;
                java.util.Arrays.fill(states, y * 256, (y + 1) * 256, state);
            }
            result.add(new EncodedSection(sectionY, PalettedPackets.encode(states, 8, 15)));
        }
        return List.copyOf(result);
    }

    private static Path javaExecutable() {
        final boolean windows = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }

    private static Path workerJar() {
        return Path.of(System.getProperty("confluxmap.terrain.worker.jar"));
    }
}
