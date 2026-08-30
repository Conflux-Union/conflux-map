package cn.net.rms.confluxmap.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TerrainWorkerTest {
    @Test
    void closeStopsAcceptingTerrainCommands() {
        final TerrainWorker worker = new TerrainWorker();

        assertTrue(worker.isHealthy());
        worker.close();

        assertFalse(worker.isHealthy());
        assertFalse(worker.updateView(new TerrainView(94L, 1L, 64)));
    }

    @Test
    void requestsMaterialsThenReturnsLatestPivot() throws Exception {
        try (TerrainWorker worker = new TerrainWorker()) {
            final int[] section = new int[4096];
            java.util.Arrays.fill(section, 0);
            for (int y = 0; y <= 3; y++) {
                java.util.Arrays.fill(section, y * 256, (y + 1) * 256, 7);
            }
            worker.updateView(new TerrainView(91L, 1L, 8));
            worker.submit(new EncodedChunk(
                91L, 12L, 2, -4, 0, 15, 8, 15, 0,
                List.of(new EncodedSection(0, PalettedPackets.encode(section, 8, 15)))
            ));

            final MaterialRequest request = worker.awaitMaterialRequest(Duration.ofSeconds(5));
            assertNotNull(request);
            worker.submitMaterials(Map.of(
                0, new MaterialDescriptor(true, false),
                7, new MaterialDescriptor(false, false)
            ));

            final TerrainResult result = worker.awaitResult(Duration.ofSeconds(5));
            assertNotNull(result);
            assertEquals(91L, result.sessionToken());
            assertEquals(1L, result.generation());
            assertEquals(3, result.result().surfaceY()[0]);
            assertEquals(7, result.result().floorStateId()[0]);

            worker.updateView(new TerrainView(91L, 2L, 2));
            final TerrainResult repivoted = worker.awaitResult(Duration.ofSeconds(5));
            assertNotNull(repivoted);
            assertEquals(2L, repivoted.generation());
            assertEquals(3, repivoted.result().surfaceY()[0]);
            assertEquals(false, repivoted.result().crossSection()[0]);

            worker.submitMaterials(Map.of(8, new MaterialDescriptor(false, false)));
            worker.submit(new TerrainDelta(91L, 13L, 2, -4, 0, 5, 0, 8));
            worker.updateView(new TerrainView(91L, 3L, 8));
            TerrainResult changed;
            do {
                changed = worker.awaitResult(Duration.ofSeconds(5));
                assertNotNull(changed);
            } while (changed.generation() != 3L);
            assertEquals(5, changed.result().surfaceY()[0]);
            assertEquals(8, changed.result().floorStateId()[0]);
            assertEquals(13L, changed.result().revision());

            worker.pause();
            worker.updateView(new TerrainView(91L, 4L, 8));
            TerrainResult resumed;
            do {
                resumed = worker.awaitResult(Duration.ofSeconds(5));
                assertNotNull(resumed, worker.fault());
            } while (resumed.generation() != 4L);
            assertEquals(5, resumed.result().surfaceY()[0]);
        }
    }

    @Test
    void boundedQueuesSurviveWideTallChunkRefresh() throws Exception {
        try (TerrainWorker worker = new TerrainWorker()) {
            worker.updateView(new TerrainView(92L, 1L, 64, 0, 159, 0, 0));
            worker.submitMaterials(Map.of(0, new MaterialDescriptor(true, false)));
            for (int chunkX = 0; chunkX < 160; chunkX++) {
                worker.submit(new EncodedChunk(
                    92L, chunkX, chunkX, 0, -4, 19, 8, 15, 0, List.of()
                ));
            }

            TerrainResult last = null;
            final long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && worker.isHealthy()) {
                final TerrainResult result = worker.awaitResult(Duration.ofMillis(100));
                if (result != null && result.result().chunkX() == 159) {
                    last = result;
                    break;
                }
            }

            assertNotNull(last, worker.fault());
            assertTrue(worker.isHealthy(), worker.fault());
        }
    }

    @Test
    void pausedWorkerCachesChunksAndDeltasForImmediateResume() throws Exception {
        try (TerrainWorker worker = new TerrainWorker()) {
            final int[] section = new int[4096];
            for (int y = 0; y <= 3; y++) {
                java.util.Arrays.fill(section, y * 256, (y + 1) * 256, 7);
            }
            worker.updateView(new TerrainView(93L, 1L, 2));
            worker.pause();
            worker.submit(new EncodedChunk(
                93L, 12L, 2, -4, 0, 15, 8, 15, 0,
                List.of(new EncodedSection(0, PalettedPackets.encode(section, 8, 15)))
            ));
            final MaterialRequest cachedMaterials = worker.awaitMaterialRequest(
                Duration.ofSeconds(1)
            );
            assertNotNull(cachedMaterials);
            assertTrue(cachedMaterials.stateIds().containsAll(Set.of(0, 7)));
            worker.submitMaterials(Map.of(
                0, new MaterialDescriptor(true, false),
                7, new MaterialDescriptor(false, false)
            ));
            worker.submit(new TerrainDelta(93L, 13L, 2, -4, 0, 5, 0, 8));
            final MaterialRequest deltaMaterial = worker.awaitMaterialRequest(
                Duration.ofSeconds(1)
            );
            assertNotNull(deltaMaterial);
            assertEquals(Set.of(8), deltaMaterial.stateIds());
            worker.submitMaterials(Map.of(8, new MaterialDescriptor(false, false)));
            worker.updateView(new TerrainView(93L, 2L, 8));

            final TerrainResult resumed = worker.awaitResult(Duration.ofSeconds(1));
            assertNotNull(resumed, worker.fault());
            assertEquals(2L, resumed.generation());
            assertEquals(5, resumed.result().surfaceY()[0]);
            assertEquals(8, resumed.result().floorStateId()[0]);
            assertEquals(13L, resumed.result().revision());

            worker.pause();
            worker.invalidate(93L, 2, -4);
            worker.updateView(new TerrainView(93L, 3L, 8));
            TerrainResult invalidated = worker.awaitResult(Duration.ofMillis(200));
            while (invalidated != null && invalidated.generation() != 3L) {
                invalidated = worker.awaitResult(Duration.ofMillis(200));
            }
            assertNull(invalidated, "invalidated chunk was recalculated from stale data");
        }
    }
}
