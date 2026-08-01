package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProgressiveSourceStampsTest {
    @Test
    void detectsMcaOrLiveChangesBeforeACompletedScanIsReused() {
        final ProgressiveSourceStamps stamps = new ProgressiveSourceStamps(2);
        stamps.record(0, 10L, 10L, 1L, 1L);
        stamps.record(1, 20L, 20L, 2L, 2L);
        assertTrue(stamps.stableScan());

        assertEquals(
            ProgressiveSourceStamps.Validation.FRESH,
            stamps.validate(i -> i == 0 ? 10L : 20L, i -> i + 1L, 2, Long.MAX_VALUE, () -> 0L)
        );
        assertEquals(
            ProgressiveSourceStamps.Validation.STALE,
            stamps.validate(i -> i == 0 ? 11L : 20L, i -> i + 1L, 2, Long.MAX_VALUE, () -> 0L)
        );
        assertEquals(
            ProgressiveSourceStamps.Validation.STALE,
            stamps.validate(i -> i == 0 ? 10L : 20L, i -> i == 0 ? 1L : 3L, 2, Long.MAX_VALUE, () -> 0L)
        );
    }

    @Test
    void refusesARegionThatChangedWhileItWasBeingScanned() {
        final ProgressiveSourceStamps stamps = new ProgressiveSourceStamps(1);
        stamps.record(0, 10L, 11L, 4L, 4L);

        assertFalse(stamps.stableScan());
        assertEquals(
            ProgressiveSourceStamps.Validation.STALE,
            stamps.validate(i -> 11L, i -> 4L, 1, Long.MAX_VALUE, () -> 0L)
        );
    }

    @Test
    void validationObeysItsPerTickBudget() {
        final ProgressiveSourceStamps stamps = new ProgressiveSourceStamps(3);
        for (int i = 0; i < 3; i++) {
            stamps.record(i, i, i, i, i);
        }
        final AtomicInteger calls = new AtomicInteger();

        assertEquals(
            ProgressiveSourceStamps.Validation.IN_PROGRESS,
            stamps.validate(i -> i, i -> i, 2, Long.MAX_VALUE, () -> calls.getAndIncrement())
        );
        assertEquals(
            ProgressiveSourceStamps.Validation.FRESH,
            stamps.validate(i -> i, i -> i, 2, Long.MAX_VALUE, () -> calls.getAndIncrement())
        );
    }
}
