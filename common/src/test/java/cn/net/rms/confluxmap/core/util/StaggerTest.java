package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class StaggerTest {
    private static final float FRACTION = 0.25f;

    @Test
    void firstItemStartsImmediatelyAndLastStartsLatest() {
        assertEquals(0.0f, Stagger.slice(0.0f, 0, 4, FRACTION), 1e-6f);
        assertEquals(0.0f, Stagger.slice(0.0f, 3, 4, FRACTION), 1e-6f);
        // Halfway through the shared timeline the first button is well underway while the
        // last has only just been given its slice.
        assertTrue(Stagger.slice(0.5f, 0, 4, FRACTION) > Stagger.slice(0.5f, 3, 4, FRACTION));
        assertTrue(Stagger.slice(0.5f, 3, 4, FRACTION) > 0.0f);
    }

    @Test
    void everyItemFinishesTogetherAtFullProgress() {
        for (int index = 0; index < 4; index++) {
            assertEquals(1.0f, Stagger.slice(1.0f, index, 4, FRACTION), 1e-6f);
        }
    }

    @Test
    void slicesStayOrderedAndClampInsideTheSharedTimeline() {
        for (int step = 0; step <= 10; step++) {
            final float progress = step / 10.0f;
            float next = 1.0f;
            for (int index = 0; index < 4; index++) {
                final float slice = Stagger.slice(progress, index, 4, FRACTION);
                assertTrue(slice <= next, "later items must never lead earlier ones");
                assertTrue(slice >= 0.0f && slice <= 1.0f);
                next = slice;
            }
        }
    }

    @Test
    void singleItemIsJustTheSharedProgress() {
        assertEquals(0.0f, Stagger.slice(0.0f, 0, 1, FRACTION), 1e-6f);
        assertEquals(0.3f, Stagger.slice(0.3f, 0, 1, FRACTION), 1e-6f);
        assertEquals(1.0f, Stagger.slice(1.0f, 0, 1, FRACTION), 1e-6f);
    }

    @Test
    void outOfRangeSharedProgressClampsEverySlice() {
        for (int index = 0; index < 4; index++) {
            assertEquals(1.0f, Stagger.slice(1.5f, index, 4, FRACTION), 1e-6f);
            assertEquals(0.0f, Stagger.slice(-0.5f, index, 4, FRACTION), 1e-6f);
        }
    }
}
