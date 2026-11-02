package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
