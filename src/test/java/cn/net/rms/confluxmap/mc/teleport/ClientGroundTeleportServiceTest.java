package cn.net.rms.confluxmap.mc.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void motionBlockingHeightIsUsedAsTheFinalPlayerFeetY() {
        assertEquals(91, ClientGroundTeleportService.groundY(91, -64, 320).orElseThrow());
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
}
