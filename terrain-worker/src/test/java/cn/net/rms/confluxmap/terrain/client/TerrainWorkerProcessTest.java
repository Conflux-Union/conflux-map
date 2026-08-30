package cn.net.rms.confluxmap.terrain.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.terrain.PalettedPackets;
import cn.net.rms.confluxmap.terrain.protocol.EncodedChunk;
import cn.net.rms.confluxmap.terrain.protocol.EncodedSection;
import cn.net.rms.confluxmap.terrain.protocol.MaterialDescriptor;
import cn.net.rms.confluxmap.terrain.protocol.MaterialRequest;
import cn.net.rms.confluxmap.terrain.protocol.TerrainDelta;
import cn.net.rms.confluxmap.terrain.protocol.TerrainResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TerrainWorkerProcessTest {
    @Test
    void childProcessRequestsMaterialsThenReturnsLatestPivot() throws Exception {
        try (TerrainWorkerProcess process = TerrainWorkerProcess.launch(
            javaExecutable(), workerJar()
        )) {
            final int[] section = new int[4096];
            java.util.Arrays.fill(section, 0);
            for (int y = 0; y <= 3; y++) {
                java.util.Arrays.fill(section, y * 256, (y + 1) * 256, 7);
            }
            process.updateView(91L, 1L, 8);
            process.submit(new EncodedChunk(
                91L, 12L, 2, -4, 0, 15, 8, 15, 0,
                List.of(new EncodedSection(0, PalettedPackets.encode(section, 8, 15)))
            ));

            final MaterialRequest request = process.awaitMaterialRequest(Duration.ofSeconds(5));
            assertNotNull(request);
            process.submitMaterials(Map.of(
                0, new MaterialDescriptor(true, false),
                7, new MaterialDescriptor(false, false)
            ));

            final TerrainResult result = process.awaitResult(Duration.ofSeconds(5));
            assertNotNull(result);
            assertEquals(91L, result.sessionToken());
            assertEquals(1L, result.generation());
            assertEquals(3, result.result().surfaceY()[0]);
            assertEquals(7, result.result().floorStateId()[0]);

            process.updateView(91L, 2L, 2);
            final TerrainResult repivoted = process.awaitResult(Duration.ofSeconds(5));
            assertNotNull(repivoted);
            assertEquals(2L, repivoted.generation());
            assertEquals(3, repivoted.result().surfaceY()[0]);
            assertEquals(false, repivoted.result().crossSection()[0]);

            process.submitMaterials(Map.of(8, new MaterialDescriptor(false, false)));
            process.submit(new TerrainDelta(91L, 13L, 2, -4, 0, 5, 0, 8));
            process.updateView(91L, 3L, 8);
            TerrainResult changed;
            do {
                changed = process.awaitResult(Duration.ofSeconds(5));
                assertNotNull(changed);
            } while (changed.generation() != 3L);
            assertEquals(5, changed.result().surfaceY()[0]);
            assertEquals(8, changed.result().floorStateId()[0]);
            assertEquals(13L, changed.result().revision());

            process.pause();
            process.updateView(91L, 4L, 8);
            TerrainResult resumed;
            do {
                resumed = process.awaitResult(Duration.ofSeconds(5));
                assertNotNull(resumed, process.fault());
            } while (resumed.generation() != 4L);
            assertEquals(5, resumed.result().surfaceY()[0]);
        }
    }

    @Test
    void boundedHeapSurvivesWideTallChunkRefresh() throws Exception {
        try (TerrainWorkerProcess process = TerrainWorkerProcess.launch(
            javaExecutable(), workerJar(), 32
        )) {
            process.updateView(92L, 1L, 64, 0, 159, 0, 0);
            process.submitMaterials(Map.of(0, new MaterialDescriptor(true, false)));
            for (int chunkX = 0; chunkX < 160; chunkX++) {
                process.submit(new EncodedChunk(
                    92L, chunkX, chunkX, 0, -4, 19, 8, 15, 0, List.of()
                ));
            }

            TerrainResult last = null;
            final long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && process.isHealthy()) {
                final TerrainResult result = process.awaitResult(Duration.ofMillis(100));
                if (result != null && result.result().chunkX() == 159) {
                    last = result;
                    break;
                }
            }

            assertNotNull(last, process.fault());
            assertTrue(process.isHealthy(), process.fault());
        }
    }

    @Test
    void pausedWorkerCachesChunksAndDeltasForImmediateResume() throws Exception {
        try (TerrainWorkerProcess process = TerrainWorkerProcess.launch(
            javaExecutable(), workerJar()
        )) {
            final int[] section = new int[4096];
            for (int y = 0; y <= 3; y++) {
                java.util.Arrays.fill(section, y * 256, (y + 1) * 256, 7);
            }
            process.updateView(93L, 1L, 2);
            process.pause();
            process.submit(new EncodedChunk(
                93L, 12L, 2, -4, 0, 15, 8, 15, 0,
                List.of(new EncodedSection(0, PalettedPackets.encode(section, 8, 15)))
            ));
            final MaterialRequest cachedMaterials = process.awaitMaterialRequest(
                Duration.ofSeconds(1)
            );
            assertNotNull(cachedMaterials);
            assertTrue(cachedMaterials.stateIds().containsAll(Set.of(0, 7)));
            process.submitMaterials(Map.of(
                0, new MaterialDescriptor(true, false),
                7, new MaterialDescriptor(false, false)
            ));
            process.submit(new TerrainDelta(93L, 13L, 2, -4, 0, 5, 0, 8));
            final MaterialRequest deltaMaterial = process.awaitMaterialRequest(
                Duration.ofSeconds(1)
            );
            assertNotNull(deltaMaterial);
            assertEquals(Set.of(8), deltaMaterial.stateIds());
            process.submitMaterials(Map.of(8, new MaterialDescriptor(false, false)));
            process.updateView(93L, 2L, 8);

            final TerrainResult resumed = process.awaitResult(Duration.ofSeconds(1));
            assertNotNull(resumed, process.fault());
            assertEquals(2L, resumed.generation());
            assertEquals(5, resumed.result().surfaceY()[0]);
            assertEquals(8, resumed.result().floorStateId()[0]);
            assertEquals(13L, resumed.result().revision());

            process.pause();
            process.invalidate(93L, 2, -4);
            process.updateView(93L, 3L, 8);
            TerrainResult invalidated = process.awaitResult(Duration.ofMillis(200));
            while (invalidated != null && invalidated.generation() != 3L) {
                invalidated = process.awaitResult(Duration.ofMillis(200));
            }
            assertNull(invalidated, "invalidated chunk was recalculated from stale data");
        }
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
