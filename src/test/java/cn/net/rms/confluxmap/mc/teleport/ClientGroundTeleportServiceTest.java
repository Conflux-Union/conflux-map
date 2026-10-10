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

    @Test
    void capturedColumnsLandOnTheCachedSurfaceInOneCommand() {
        assertEquals(
            ClientGroundTeleportService.FirstStage.CAPTURED,
            ClientGroundTeleportService.firstStage(
                true, false,
                new ColumnStore.SurfaceLookup(true, OptionalInt.of(73)),
                ColumnStore.SurfaceLookup.UNKNOWN
            )
        );
        assertEquals(
            ClientGroundTeleportService.FirstStage.CAPTURED,
            ClientGroundTeleportService.firstStage(
                false, false,
                new ColumnStore.SurfaceLookup(true, OptionalInt.of(73)),
                new ColumnStore.SurfaceLookup(true, OptionalInt.of(70))
            )
        );
    }

    @Test
    void loadedSameDimensionChunksSampleTheLiveGroundFirst() {
        assertEquals(
            ClientGroundTeleportService.FirstStage.SAMPLED,
            ClientGroundTeleportService.firstStage(
                false, true,
                new ColumnStore.SurfaceLookup(true, OptionalInt.of(73)),
                new ColumnStore.SurfaceLookup(true, OptionalInt.of(70))
            )
        );
        // A cross-dimension target has no loadable client chunk to sample.
        assertEquals(
            ClientGroundTeleportService.FirstStage.CAPTURED,
            ClientGroundTeleportService.firstStage(
                true, true,
                new ColumnStore.SurfaceLookup(true, OptionalInt.of(73)),
                ColumnStore.SurfaceLookup.UNKNOWN
            )
        );
    }

    @Test
    void voidColumnsKeepThePlayerY() {
        assertEquals(
            ClientGroundTeleportService.FirstStage.PLAYER_Y,
            ClientGroundTeleportService.firstStage(
                false, false,
                new ColumnStore.SurfaceLookup(true, OptionalInt.empty()),
                new ColumnStore.SurfaceLookup(true, OptionalInt.empty())
            )
        );
        assertEquals(
            ClientGroundTeleportService.FirstStage.PLAYER_Y,
            ClientGroundTeleportService.firstStage(
                false, false,
                ColumnStore.SurfaceLookup.UNKNOWN,
                new ColumnStore.SurfaceLookup(true, OptionalInt.empty())
            )
        );
    }

    @Test
    void predictedOrUnknownColumnsStageForTheCorrectionTick() {
        assertEquals(
            ClientGroundTeleportService.FirstStage.STAGED,
            ClientGroundTeleportService.firstStage(
                false, false, ColumnStore.SurfaceLookup.UNKNOWN,
                new ColumnStore.SurfaceLookup(true, OptionalInt.of(70))
            )
        );
        assertEquals(
            ClientGroundTeleportService.FirstStage.STAGED,
            ClientGroundTeleportService.firstStage(
                false, false, ColumnStore.SurfaceLookup.UNKNOWN, ColumnStore.SurfaceLookup.UNKNOWN
            )
        );
        assertEquals(
            ClientGroundTeleportService.FirstStage.STAGED,
            ClientGroundTeleportService.firstStage(
                true, false, ColumnStore.SurfaceLookup.UNKNOWN, ColumnStore.SurfaceLookup.UNKNOWN
            )
        );
    }

    @Test
    void underRoofLandingSkipsTheRoofCapAndStandsOnTheFloor() {
        // Bedrock roof at 127 (double layer at 126), open gap, floor at 80.
        assertEquals(
            81,
            ClientGroundTeleportService.underRoofPlayerY(128, 0, y -> y >= 126 || y == 80)
                .orElseThrow()
        );
        // Terrain welded to the roof all the way down has no landing.
        assertTrue(ClientGroundTeleportService.underRoofPlayerY(128, 0, y -> true).isEmpty());
        // An open column down to the bottom has no landing either.
        assertTrue(ClientGroundTeleportService.underRoofPlayerY(128, 0, y -> false).isEmpty());
    }
}
