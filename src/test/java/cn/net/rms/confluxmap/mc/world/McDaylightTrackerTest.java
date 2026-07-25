package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class McDaylightTrackerTest {
    /** One full day, in the same unit this version's sun angle is reported in. */
    //#if MC>=12111
    //$$ private static final float DAY_SPAN = 360f;
    //#else
    private static final float DAY_SPAN = (float) (Math.PI * 2);
    //#endif

    private static final int SAMPLES = 2048;

    @Test
    void noonIsFullyLitAndMidnightIsFullyDark() {
        assertEquals(1f, McDaylightTracker.daylightFactor(0f), 1.0e-4f);
        assertEquals(0f, McDaylightTracker.daylightFactor(DAY_SPAN / 2f), 1.0e-4f);
    }

    @Test
    void oneDayProducesExactlyOneNight() {
        int darkRuns = 0;
        boolean wasDark = false;

        for (int i = 0; i < SAMPLES; i++) {
            final float factor = McDaylightTracker.daylightFactor(DAY_SPAN * i / SAMPLES);
            assertTrue(factor >= 0f && factor <= 1f, "factor out of range: " + factor);
            final boolean dark = factor < 0.5f;
            if (dark && !wasDark) {
                darkRuns++;
            }
            wasDark = dark;
        }

        assertEquals(1, darkRuns, "sun angle must be read in this version's own unit");
    }
}
