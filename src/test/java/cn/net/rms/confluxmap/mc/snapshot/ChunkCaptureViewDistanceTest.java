package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkCaptureViewDistanceTest {
    @Test
    void advertisedServerDistanceOverridesSmallerClientSetting() {
        assertEquals(12, ChunkCaptureService.captureViewDistance(4, 12));
    }

    @Test
    void clientSettingIsUsedOnlyWhenTheServerDidNotAdvertiseItsDistance() {
        assertEquals(4, ChunkCaptureService.captureViewDistance(4, -1));
    }
}
