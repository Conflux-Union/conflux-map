package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProgressivePatchTaskTest {
    @Test
    void advancesAtMostTheChunkAndTimeBudgets() {
        final ProgressivePatchTask task = new ProgressivePatchTask(4, 0, 0);
        final AtomicInteger clock = new AtomicInteger();
        final int first = task.advance(
            (x, z) -> null,
            32,
            4,
            () -> clock.getAndIncrement()
        );

        assertEquals(4, first, "time budget stops the pass after four clock units");
        assertFalse(task.complete());

        final int second = task.advance(
            (x, z) -> null,
            3,
            Long.MAX_VALUE,
            () -> 0L
        );
        assertEquals(3, second, "chunk budget independently caps the pass");
        assertEquals(7, task.processedChunks());
    }

    @Test
    void scansRegionsInContiguousRegionMajorOrder() {
        final ProgressivePatchTask task = new ProgressivePatchTask(3, -1, -1);
        final java.util.ArrayList<String> positions = new java.util.ArrayList<>();

        task.advance((x, z) -> {
            positions.add(x + "," + z);
            return null;
        }, 258, Long.MAX_VALUE, () -> 0L);

        assertEquals("-128,-128", positions.get(0));
        assertEquals("-113,-113", positions.get(255));
        assertEquals("-112,-128", positions.get(256));
        assertTrue(task.processedChunks() == 258);
    }
}
