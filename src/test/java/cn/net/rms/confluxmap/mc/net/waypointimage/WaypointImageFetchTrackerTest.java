package cn.net.rms.confluxmap.mc.net.waypointimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WaypointImageFetchTrackerTest {
    @Test
    void completedFetchesAreImmediatelyRestartable() {
        final WaypointImageClient.FetchTracker tracker = new WaypointImageClient.FetchTracker();
        assertTrue(tracker.start("a"));
        assertFalse(tracker.start("a"), "a duplicate request must be deduplicated");
        tracker.complete("a");
        assertTrue(tracker.start("a"), "a completed fetch must be restartable");
    }

    @Test
    void sessionResetClearsEveryInFlightEntry() {
        final WaypointImageClient.FetchTracker tracker = new WaypointImageClient.FetchTracker();
        tracker.start("a");
        tracker.start("b");
        tracker.reset();
        assertEquals(0, tracker.size());
        assertTrue(tracker.start("a"));
    }
}
