package cn.net.rms.confluxmap.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerrainWorkerSnapshotFreshnessTest {
    @Test
    void submitMaterialsImmediatelyUpdatesVolatileSnapshotAndComputesChunkWithoutViewportReset() throws Exception {
        try (TerrainWorker worker = new TerrainWorker()) {
            final int[] section = new int[4096];
            java.util.Arrays.fill(section, 0); // Air
            for (int y = 0; y <= 2; y++) {
                java.util.Arrays.fill(section, y * 256, (y + 1) * 256, 42); // Unknown material 42
            }

            final long sessionToken = 1001L;
            worker.updateView(new TerrainView(sessionToken, 1L, 8));

            // Submit chunk with unknown material 42
            worker.submit(new EncodedChunk(
                sessionToken, 1L, 0, 0, 0, 15, 8, 15, 0,
                List.of(new EncodedSection(0, PalettedPackets.encode(section, 8, 15)))
            ));

            // Must request material 42
            final MaterialRequest request = worker.awaitMaterialRequest(Duration.ofSeconds(5));
            assertNotNull(request, "Worker must produce a MaterialRequest for unknown state 42");
            assertTrue(request.stateIds().contains(42), "Request must include state 42");

            // Provide definition for both air (0) and state 42
            worker.submitMaterials(Map.of(
                0, new MaterialDescriptor(true, false),
                42, new MaterialDescriptor(false, false)
            ));

            // Without updating the view or resetting the session, the worker must successfully compute
            // the result using the freshly published volatile snapshot
            final TerrainResult result = worker.awaitResult(Duration.ofSeconds(5));
            assertNotNull(result, "Chunk must be successfully computed after materials arrive");
            assertEquals(sessionToken, result.sessionToken());
            assertEquals(2, result.result().surfaceY()[0]);
            assertEquals(42, result.result().floorStateId()[0]);
            assertTrue(worker.isHealthy(), "Worker must remain healthy without fault");
        }
    }
}
