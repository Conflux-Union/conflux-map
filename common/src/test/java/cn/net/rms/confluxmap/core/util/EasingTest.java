package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EasingTest {
    @Test
    void easeOutCubicMatchesOneMinusCubeAndClamps() {
        assertEquals(0.0f, Easing.easeOutCubic(0.0f));
        assertEquals(0.5f, Easing.easeOutCubic(0.20629947f), 1e-5f);
        assertEquals(1.0f, Easing.easeOutCubic(1.0f));
        assertEquals(0.0f, Easing.easeOutCubic(-0.5f));
        assertEquals(1.0f, Easing.easeOutCubic(1.5f));
    }

    @Test
    void easeOutBackAnchorsOnBothEndsAndClamps() {
        assertEquals(0.0f, Easing.easeOutBack(0.0f), 1e-6f);
        assertEquals(1.0f, Easing.easeOutBack(1.0f), 1e-6f);
        assertEquals(0.0f, Easing.easeOutBack(-0.5f), 1e-6f);
        assertEquals(1.0f, Easing.easeOutBack(1.5f), 1e-6f);
    }

    @Test
    void easeOutBackOvershootsThenSettlesBackToOne() {
        // The rebound must actually cross 1, or a fly-in would never read as an overshoot.
        assertTrue(Easing.easeOutBack(0.7f) > 1.0f);
        // Settling means the tail comes back down toward the anchor.
        assertTrue(Easing.easeOutBack(0.7f) > Easing.easeOutBack(0.95f) - 1e-3f);
        // Before the overshoot the curve only climbs from 0.
        assertTrue(Easing.easeOutBack(0.1f) > 0.0f);
        assertTrue(Easing.easeOutBack(0.1f) < 1.0f);
    }
}
