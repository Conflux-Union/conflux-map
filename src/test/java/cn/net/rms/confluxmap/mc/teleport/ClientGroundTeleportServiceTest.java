package cn.net.rms.confluxmap.mc.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.store.ColumnStore;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ClientGroundTeleportServiceTest {
    @Test
    void cubiomesEstimateIsOnlyUsedForAHeadroomStagingPosition() {
        assertEquals(105, ClientGroundTeleportService.stagingY(OptionalInt.of(73), -64, 320));
        assertEquals(320, ClientGroundTeleportService.stagingY(OptionalInt.empty(), -64, 320));
        assertEquals(320, ClientGroundTeleportService.stagingY(OptionalInt.of(310), -64, 320));
    }

    @Test
    void knownSurfaceEstimatesAFeetPositionAndVoidHasNone() {
        assertEquals(
            74,
            ClientGroundTeleportService.estimatedPlayerY(
                new ColumnStore.SurfaceLookup(true, OptionalInt.of(73))
            ).orElseThrow()
        );
        assertTrue(ClientGroundTeleportService.estimatedPlayerY(
            new ColumnStore.SurfaceLookup(false, OptionalInt.empty())
        ).isEmpty());
        assertTrue(ClientGroundTeleportService.estimatedPlayerY(
            new ColumnStore.SurfaceLookup(true, OptionalInt.empty())
        ).isEmpty());
    }

    @Test
    void motionBlockingTopBlockIsConvertedToPlayerFeetY() {
        assertEquals(92, ClientGroundTeleportService.groundY(91, -64, 320).orElseThrow());
        assertTrue(ClientGroundTeleportService.groundY(-64, -64, 320).isEmpty());
    }

    @Test
    void commandCentersTheTargetBlockIncludingNegativeCoordinates() {
        assertEquals("tp -0.5 91 8.5", ClientGroundTeleportService.commandAt(-1, 91, 8));
    }

    @Test
    void correctionWaitsUntilThePlayerReachesTheTargetChunk() {
        assertTrue(ClientGroundTeleportService.isInTargetChunk(-0.5, 8.5, -1, 8));
        assertFalse(ClientGroundTeleportService.isInTargetChunk(32.5, 8.5, -1, 8));
    }

    @Test
    void loadedTargetChunkSendsTheTargetCommandAndStaysSilent() {
        // Ground and a resolved void column share this step: which Y to land on is picked in
        // tick() from the sample, falling back to the saved pre-teleport Y over void.
        final ClientGroundTeleportService.CorrectionStep step = ClientGroundTeleportService.nextCorrectionStep(
            false, true, true
        );

        assertTrue(step.clearsPending());
        assertTrue(step.sendsTarget());
        assertFalse(step.sendsReturn());
        assertNull(step.feedbackKey());
    }

    @Test
    void timeoutWithoutReachingTheTargetChunkFiresTheTimeoutKey() {
        final ClientGroundTeleportService.CorrectionStep step = ClientGroundTeleportService.nextCorrectionStep(
            true, false, false
        );

        assertTrue(step.clearsPending());
        assertFalse(step.sendsTarget());
        assertFalse(step.sendsReturn());
        assertEquals(ClientGroundTeleportService.TIMEOUT_KEY, step.feedbackKey());
    }

    @Test
    void timeoutWhileInsideTheTargetChunkReturnsToTheOriginWithoutFeedback() {
        final ClientGroundTeleportService.CorrectionStep step = ClientGroundTeleportService.nextCorrectionStep(
            true, true, false
        );

        assertTrue(step.clearsPending());
        assertFalse(step.sendsTarget());
        assertTrue(step.sendsReturn());
        assertNull(step.feedbackKey());
    }

    @Test
    void waitingTicksStaySilent() {
        final ClientGroundTeleportService.CorrectionStep notArrived = ClientGroundTeleportService.nextCorrectionStep(
            false, false, false
        );
        final ClientGroundTeleportService.CorrectionStep chunkNotLoaded = ClientGroundTeleportService.nextCorrectionStep(
            false, true, false
        );

        for (final ClientGroundTeleportService.CorrectionStep step : List.of(notArrived, chunkNotLoaded)) {
            assertFalse(step.clearsPending());
            assertFalse(step.sendsTarget());
            assertFalse(step.sendsReturn());
            assertNull(step.feedbackKey());
        }
    }
}
