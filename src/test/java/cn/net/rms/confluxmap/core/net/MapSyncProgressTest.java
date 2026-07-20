package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MapSyncProgressTest {
    @Test
    void completesAfterEveryRequestedTileArrives() {
        final MapSyncProgress progress = new MapSyncProgress();
        final MapViewReqC2S request = request(7, tile(2, 3), tile(4, 5));

        progress.requestStarted(request, 40, 1_000L);
        assertEquals(MapSyncProgress.State.SYNCING, progress.snapshot().state());

        progress.patchReceived(patch(7, 2, 3), 100, 2_000L);
        assertEquals(MapSyncProgress.State.SYNCING, progress.snapshot().state());

        progress.patchReceived(patch(7, 4, 5), 60, 4_500L);
        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.COMPLETED, 3_500L, 200L),
            progress.snapshot()
        );
    }

    @Test
    void overlappingRequestsFormOneVisibleSync() {
        final MapSyncProgress progress = new MapSyncProgress();

        progress.requestStarted(request(1, tile(0, 0)), 20, 1_000L);
        progress.requestStarted(request(2, tile(1, 1)), 30, 1_500L);
        progress.patchReceived(patch(1, 0, 0), 40, 2_000L);
        assertEquals(MapSyncProgress.State.SYNCING, progress.snapshot().state());

        progress.patchReceived(patch(2, 1, 1), 50, 3_000L);
        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.COMPLETED, 2_000L, 140L),
            progress.snapshot()
        );
    }

    @Test
    void ignoresUnrelatedAndDuplicatePatches() {
        final MapSyncProgress progress = new MapSyncProgress();
        progress.requestStarted(request(3, tile(8, 9), tile(10, 11)), 25, 100L);

        progress.patchReceived(patch(99, 8, 9), 1_000, 200L);
        progress.patchReceived(patch(3, 12, 13), 1_000, 300L);
        progress.patchReceived(patch(3, 8, 9), 75, 400L);
        progress.patchReceived(patch(3, 8, 9), 75, 500L);

        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.SYNCING, 0L, 100L),
            progress.snapshot()
        );
    }

    @Test
    void resetClearsCurrentAndCompletedSyncs() {
        final MapSyncProgress progress = new MapSyncProgress();
        progress.requestStarted(request(4, tile(0, 0)), 20, 1_000L);
        progress.patchReceived(patch(4, 0, 0), 30, 2_000L);

        progress.reset();

        assertEquals(MapSyncProgress.Snapshot.IDLE, progress.snapshot());
    }

    @Test
    void serverErrorEndsTheVisibleSyncAsFailed() {
        final MapSyncProgress progress = new MapSyncProgress();
        progress.requestStarted(request(5, tile(0, 0), tile(1, 1)), 20, 1_000L);

        progress.requestFailed(30, 2_500L);

        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.FAILED, 1_500L, 50L),
            progress.snapshot()
        );
    }

    private static MapViewReqC2S request(final int reqId, final MapViewReqC2S.TileReq... tiles) {
        return new MapViewReqC2S(reqId, 0, 1, List.of(tiles));
    }

    private static MapViewReqC2S.TileReq tile(final int tileX, final int tileZ) {
        return new MapViewReqC2S.TileReq(tileX, tileZ, 0L);
    }

    private static MapPatchS2C patch(final int reqId, final int tileX, final int tileZ) {
        return new MapPatchS2C(
            reqId, 0, 1, tileX, tileZ, Proto.PATCH_MODE_UNCHANGED,
            0L, new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0]
        );
    }
}
