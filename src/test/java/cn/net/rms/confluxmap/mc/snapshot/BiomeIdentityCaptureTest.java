package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BiomeIdentityCaptureTest {
    @Test
    void sampledCoordinatesMatchTheIdentityGrid() {
        assertEquals(0, BiomeIdentityCapture.coordinate(true, 0));
        assertEquals(5, BiomeIdentityCapture.coordinate(true, 1));
        assertEquals(10, BiomeIdentityCapture.coordinate(true, 2));
        assertEquals(15, BiomeIdentityCapture.coordinate(true, 3));
        assertEquals(7, BiomeIdentityCapture.coordinate(false, 7));
    }
}
