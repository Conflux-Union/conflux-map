package cn.net.rms.confluxmap.mc.ui.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MinimapPlayerMarkerBoundsTest {
    @Test
    void edgeClampReservesTheRotatedNativeMarkerRadius() {
        assertEquals(
            60f - Math.hypot(8f, 8f),
            MinimapHudRenderer.playerMarkerEdgeLimit(120),
            0.0001,
            "the edge inset must contain every corner of the rotated 16x16 marker"
        );
    }

}
