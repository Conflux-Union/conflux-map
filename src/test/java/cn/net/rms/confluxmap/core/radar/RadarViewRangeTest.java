package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RadarViewRangeTest {
    @Test
    void centeredViewportKeepsItsHalfDiagonalRadius() {
        final RadarViewRange range = new RadarViewRange();

        range.setForAxisAlignedViewport(
            300.0, -200.0,
            300.0, -200.0,
            800.0, 600.0,
            0.25
        );

        assertEquals(125.0, range.radius(), 1.0e-9);
    }

    @Test
    void playerCenteredScanCoversAPannedFullscreenViewportAtMaximumZoom() {
        final RadarViewRange range = new RadarViewRange();

        range.setForAxisAlignedViewport(
            0.0, 0.0,
            300.0, -200.0,
            800.0, 600.0,
            0.25
        );

        assertEquals(Math.hypot(400.0, 275.0), range.radius(), 1.0e-9);
    }
}
