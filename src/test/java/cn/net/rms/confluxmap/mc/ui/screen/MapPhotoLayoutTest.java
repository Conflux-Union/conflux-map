package cn.net.rms.confluxmap.mc.ui.screen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MapPhotoLayoutTest {
    @Test void screenshotChunksUseFloorDivisionAcrossNegativeBoundaries() {
        assertEquals(-2, MapScreenshotOverlay.chunkCoordinate(-16.01));
        assertEquals(-1, MapScreenshotOverlay.chunkCoordinate(-16));
        assertEquals(-1, MapScreenshotOverlay.chunkCoordinate(-0.01));
        assertEquals(0, MapScreenshotOverlay.chunkCoordinate(0));
        assertEquals(0, MapScreenshotOverlay.chunkCoordinate(15.99));
        assertEquals(1, MapScreenshotOverlay.chunkCoordinate(16));
    }

    @Test void editPhotoRowFitsBelowMarkerAndAboveFooterAtSupportedHeights() {
        for (int height = 240; height <= 400; height++) {
            var rows = WaypointEditLayout.rows(height, true, true);
            assertTrue(rows.imageY() >= rows.markerY() + 20, "Marker overlap at " + height);
            assertTrue(rows.imageY() + 20 <= WaypointEditLayout.footerY(height), "Footer overlap at " + height);
        }
    }
}
