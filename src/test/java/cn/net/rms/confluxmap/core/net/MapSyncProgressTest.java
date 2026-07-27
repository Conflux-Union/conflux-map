package cn.net.rms.confluxmap.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapSyncProgressTest {
    @Test
    void completesAfterEveryRequestedTileArrives() {
        final MapSyncProgress progress = new MapSyncProgress();
        beginBatch(progress, tile(2, 3), tile(4, 5));
        final MapViewReqC2S request = request(7, tile(2, 3), tile(4, 5));

        progress.requestStarted(request, 40, 1_000L);
        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.SYNCING, 0, 2, 0L, 40L),
            progress.snapshot()
        );

        progress.patchReceived(patch(7, 2, 3), 100, 2_000L);
        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.SYNCING, 1, 2, 1_000L, 140L),
            progress.snapshot()
        );

        progress.patchReceived(patch(7, 4, 5), 60, 4_500L);
        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.COMPLETED, 2, 2, 3_500L, 200L),
            progress.snapshot()
        );
    }

    @Test
    void overlappingRequestsFormOneVisibleBatch() {
        final MapSyncProgress progress = new MapSyncProgress();
        beginBatch(progress, tile(0, 0), tile(1, 1));

        progress.requestStarted(request(1, tile(0, 0)), 20, 1_000L);
        progress.requestStarted(request(2, tile(1, 1)), 30, 1_500L);
        progress.patchReceived(patch(1, 0, 0), 40, 2_000L);
        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.SYNCING, 1, 2, 1_000L, 90L),
            progress.snapshot()
        );

        progress.patchReceived(patch(2, 1, 1), 50, 3_000L);
        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.COMPLETED, 2, 2, 2_000L, 140L),
            progress.snapshot()
        );
    }

    @Test
    void sequentialRequestsKeepBatchTotals() {
        final MapSyncProgress progress = new MapSyncProgress();
        beginBatch(progress, tile(0, 0), tile(1, 1), tile(2, 2));

        progress.requestStarted(request(1, tile(0, 0)), 20, 1_000L);
        progress.patchReceived(patch(1, 0, 0), 40, 2_000L);
        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.SYNCING, 1, 3, 1_000L, 60L),
            progress.snapshot()
        );

        progress.requestStarted(request(2, tile(1, 1)), 30, 4_000L);
        progress.patchReceived(patch(2, 1, 1), 50, 5_000L);
        progress.requestStarted(request(3, tile(2, 2)), 25, 6_000L);
        progress.patchReceived(patch(3, 2, 2), 35, 8_000L);

        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.COMPLETED, 3, 3, 7_000L, 200L),
            progress.snapshot()
        );
    }

    @Test
    void partialPatchKeepsTilePendingAcrossRetry() {
        final MapSyncProgress progress = new MapSyncProgress();
        beginBatch(progress, tile(8, 9));

        progress.requestStarted(request(1, tile(8, 9)), 20, 1_000L);
        progress.patchReceived(patch(1, 8, 9, Proto.PATCH_MODE_PARTIAL), 30, 2_000L);
        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.SYNCING, 0, 1, 1_000L, 50L),
            progress.snapshot()
        );

        progress.patchReceived(patch(1, 8, 9, Proto.PATCH_MODE_PARTIAL), 30, 2_500L);
        progress.requestStarted(request(2, tile(8, 9)), 25, 3_000L);
        progress.patchReceived(patch(2, 8, 9), 40, 5_000L);

        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.COMPLETED, 1, 1, 4_000L, 115L),
            progress.snapshot()
        );
    }

    @Test
    void ignoresUnrelatedAndDuplicatePatches() {
        final MapSyncProgress progress = new MapSyncProgress();
        beginBatch(progress, tile(8, 9), tile(10, 11));
        progress.requestStarted(request(3, tile(8, 9), tile(10, 11)), 25, 100L);

        progress.patchReceived(patch(99, 8, 9), 1_000, 200L);
        progress.patchReceived(patch(3, 12, 13), 1_000, 300L);
        progress.patchReceived(patch(3, 8, 9), 75, 400L);
        progress.patchReceived(patch(3, 8, 9), 75, 500L);

        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.SYNCING, 1, 2, 300L, 100L),
            progress.snapshot()
        );
    }

    @Test
    void snapshotReportsCurrentWholeBatchDuration() {
        final MapSyncProgress progress = new MapSyncProgress();
        beginBatch(progress, tile(0, 0), tile(1, 1));
        progress.requestStarted(request(4, tile(0, 0)), 20, 1_000L);

        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.SYNCING, 0, 2, 4_000L, 20L),
            progress.snapshot(5_000L)
        );
    }

    @Test
    void resetClearsCurrentAndCompletedBatches() {
        final MapSyncProgress progress = new MapSyncProgress();
        beginBatch(progress, tile(0, 0));
        progress.requestStarted(request(4, tile(0, 0)), 20, 1_000L);
        progress.patchReceived(patch(4, 0, 0), 30, 2_000L);

        progress.reset();

        assertEquals(MapSyncProgress.Snapshot.IDLE, progress.snapshot());
    }

    @Test
    void serverErrorPreservesCurrentBatchTotals() {
        final MapSyncProgress progress = new MapSyncProgress();
        beginBatch(progress, tile(0, 0), tile(1, 1));
        progress.requestStarted(request(5, tile(0, 0), tile(1, 1)), 20, 1_000L);
        progress.patchReceived(patch(5, 0, 0), 40, 2_000L);

        progress.requestFailed(30, 2_500L);

        assertEquals(
            new MapSyncProgress.Snapshot(MapSyncProgress.State.FAILED, 1, 2, 1_500L, 90L),
            progress.snapshot()
        );
    }

    private static void beginBatch(
        final MapSyncProgress progress, final MapViewReqC2S.TileReq... tiles
    ) {
        progress.beginBatch(
            0,
            1,
            Arrays.stream(tiles)
                .map(tile -> new MapSyncProgress.BatchTile(tile.tileX(), tile.tileZ()))
                .toList()
        );
    }

    private static MapViewReqC2S request(final int reqId, final MapViewReqC2S.TileReq... tiles) {
        return new MapViewReqC2S(reqId, 0, 1, List.of(tiles));
    }

    private static MapViewReqC2S.TileReq tile(final int tileX, final int tileZ) {
        return new MapViewReqC2S.TileReq(tileX, tileZ, 0L);
    }

    private static MapPatchS2C patch(final int reqId, final int tileX, final int tileZ) {
        return patch(reqId, tileX, tileZ, Proto.PATCH_MODE_UNCHANGED);
    }

    private static MapPatchS2C patch(
        final int reqId, final int tileX, final int tileZ, final int mode
    ) {
        return new MapPatchS2C(
            reqId, 0, 1, tileX, tileZ, mode,
            0L, new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0]
        );
    }
}
