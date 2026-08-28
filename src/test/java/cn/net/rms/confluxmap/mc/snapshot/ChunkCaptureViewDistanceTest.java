package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChunkCaptureViewDistanceTest {
    @Test
    void tintNeighborsAreOnlyRequiredWhenBiomeBlendIsEnabled() {
        assertFalse(ChunkCaptureService.needsTintNeighbors(0));
        assertTrue(ChunkCaptureService.needsTintNeighbors(1));
    }

    @Test
    void advertisedServerDistanceOverridesSmallerClientSetting() {
        assertEquals(12, ChunkCaptureService.captureViewDistance(4, 12));
    }

    @Test
    void clientSettingIsUsedOnlyWhenTheServerDidNotAdvertiseItsDistance() {
        assertEquals(4, ChunkCaptureService.captureViewDistance(4, -1));
    }
}
