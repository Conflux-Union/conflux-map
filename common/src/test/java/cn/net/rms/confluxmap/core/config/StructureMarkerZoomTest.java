package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class StructureMarkerZoomTest {
    @Test
    void eachThresholdMatchesItsDisplayedMapScale() {
        assertTrue(StructureMarkerZoom.ZOOM_0_25.displaysAt(4.0));
        assertFalse(StructureMarkerZoom.ZOOM_0_25.displaysAt(4.01));
        assertTrue(StructureMarkerZoom.ZOOM_0_125.displaysAt(8.0));
        assertFalse(StructureMarkerZoom.ZOOM_0_125.displaysAt(8.01));
        assertTrue(StructureMarkerZoom.ZOOM_0_0625.displaysAt(16.0));
        assertTrue(StructureMarkerZoom.ALWAYS.displaysAt(Double.MAX_VALUE));
    }
}
