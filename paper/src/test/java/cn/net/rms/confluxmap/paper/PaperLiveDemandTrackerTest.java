package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.net.MapSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaperLiveDemandTrackerTest {
    @Test
    void requestDemandExpiresAfterTheFabricTwoSecondWindow() {
        final PaperLiveDemandTracker tracker = new PaperLiveDemandTracker();
        final long requestedAt = 10_000L;
        tracker.nominate(new MapViewReqC2S(
            1, 2, 1, List.of(new MapViewReqC2S.TileReq(-1, 3, Long.MIN_VALUE))
        ), requestedAt);

        assertTrue(tracker.contains(2, -1, 96, requestedAt));
        assertTrue(tracker.contains(2, -1, 127, requestedAt + 1_999_999_999L));
        assertFalse(tracker.contains(2, -1, 127, requestedAt + 2_000_000_000L));
    }

    @Test
    void viewportDemandSurvivesUntilUnsubscribed() {
        final PaperLiveDemandTracker tracker = new PaperLiveDemandTracker();
        final UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertTrue(tracker.watch(player, new MapSyncSubscribeC2S(
            0, 0, true, 4, 4, -2, -2
        )));
        assertTrue(tracker.contains(0, 64, -32, Long.MAX_VALUE - 1L));
        assertTrue(tracker.watch(player, new MapSyncSubscribeC2S(
            -1, 0, false, 0, 0, 0, 0
        )));
        assertFalse(tracker.contains(0, 64, -32, 1L));
    }

    @Test
    void tickExpiresRequestsWithoutAnyLoadedChunkInspection() {
        final PaperLiveDemandTracker tracker = new PaperLiveDemandTracker();
        final long requestedAt = 50_000L;
        tracker.nominate(new MapViewReqC2S(
            1, 0, 0, List.of(new MapViewReqC2S.TileReq(0, 0, Long.MIN_VALUE))
        ), requestedAt);

        tracker.tick(requestedAt + 2_000_000_000L);

        assertEquals(0, tracker.pendingRequests());
    }
}
