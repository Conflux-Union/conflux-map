package cn.net.rms.confluxmap.mc.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void unresolvedGroundFiresGroundUnresolvedAndReturnsToTheOrigin() {
        final ClientGroundTeleportService.CorrectionStep step = ClientGroundTeleportService.nextCorrectionStep(
            false, true, true, false
        );

        assertTrue(step.clearsPending());
        assertFalse(step.sendsTarget());
        assertTrue(step.sendsReturn());
        assertEquals(ClientGroundTeleportService.GROUND_UNRESOLVED_KEY, step.feedbackKey());
    }

    @Test
    void resolvedGroundSendsTheTargetCommandAndStaysSilent() {
        final ClientGroundTeleportService.CorrectionStep step = ClientGroundTeleportService.nextCorrectionStep(
            false, true, true, true
        );

        assertTrue(step.clearsPending());
        assertTrue(step.sendsTarget());
        assertFalse(step.sendsReturn());
        assertNull(step.feedbackKey());
    }

    @Test
    void timeoutWithoutReachingTheTargetChunkFiresTheTimeoutKey() {
        final ClientGroundTeleportService.CorrectionStep step = ClientGroundTeleportService.nextCorrectionStep(
            true, false, false, false
        );

        assertTrue(step.clearsPending());
        assertFalse(step.sendsTarget());
        assertFalse(step.sendsReturn());
        assertEquals(ClientGroundTeleportService.TIMEOUT_KEY, step.feedbackKey());
    }

    @Test
    void timeoutWhileInsideTheTargetChunkReturnsToTheOriginWithoutFeedback() {
        final ClientGroundTeleportService.CorrectionStep step = ClientGroundTeleportService.nextCorrectionStep(
            true, true, false, false
        );

        assertTrue(step.clearsPending());
        assertTrue(step.sendsReturn());
        assertNull(step.feedbackKey());
    }

    @Test
    void waitingTicksStaySilent() {
        final ClientGroundTeleportService.CorrectionStep notArrived = ClientGroundTeleportService.nextCorrectionStep(
            false, false, false, false
        );
        final ClientGroundTeleportService.CorrectionStep chunkNotLoaded = ClientGroundTeleportService.nextCorrectionStep(
            false, true, false, false
        );

        for (final ClientGroundTeleportService.CorrectionStep step : List.of(notArrived, chunkNotLoaded)) {
            assertFalse(step.clearsPending());
            assertFalse(step.sendsTarget());
            assertFalse(step.sendsReturn());
            assertNull(step.feedbackKey());
        }
    }
}
