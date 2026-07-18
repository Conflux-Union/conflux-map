package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * {@link PredictionTileService}'s queue discipline, independent of native availability (see
 * {@link PredictionTileService#requestTile}'s javadoc for why the availability gate lives in
 * {@code composeTile} instead of here). The viewport-pruning test pins every worker thread with
 * a blocking task before queuing real requests, so the synchronous drain inside {@code pump()}
 * (which tiles land in {@code dirty} vs. {@code inFlight}) can be asserted on deterministically
 * instead of racing a real composition to finish.
 */
class PredictionTileServiceTest {
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("prediction-tile-service-test");

    private static PredictionTileService newService(final SessionGuard sessionGuard, final MapExecutors executors, final TileService uploads) {
        final ConfluxConfig config = new ConfluxConfig();
        final DaylightModel daylightModel = new DaylightModel();
        return new PredictionTileService(sessionGuard, new PredictionState(), executors, uploads, config, daylightModel);
    }

    @Test
    void staleSessionTokenResultsAreDropped() throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionTileService predictionTiles = newService(sessionGuard, executors, uploads);

        sessionGuard.begin(WORLD, DIM);
        predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 2, 0, 0));

        // Rotate the session before the async compose can possibly run - simulates a world/dimension
        // change landing mid-flight. The queued token no longer matches sessionGuard.current().
        sessionGuard.begin(WORLD, DIM);

        awaitIdle(predictionTiles);
        assertTrue(uploads.drainUploads(10).isEmpty(), "a stale-token compose must never reach the upload queue");
        executors.shutdown(1000);
    }

    @Test
    void viewportPruningDropsOutOfRectTiles() throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionTileService predictionTiles = newService(sessionGuard, executors, uploads);
        sessionGuard.begin(WORLD, DIM);

        // Occupy every worker thread with a blocker before queuing anything real, so pump()'s
        // synchronous dirty->inFlight drain can be inspected without racing a real composition.
        final int cap = executors.workerCount();
        final CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < cap; i++) {
            executors.workers().execute(() -> {
                try {
                    release.await();
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread.sleep(50);

        try {
            final int requested = cap + 3;
            for (int i = 0; i < requested; i++) {
                predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 2, i, 0));
            }

            final Set<TileKey> pendingBefore = predictionTiles.pendingKeysForTest();
            assertTrue(
                pendingBefore.size() == 3,
                "expected exactly (requested - cap) tiles still queued, got " + pendingBefore.size() + " (cap=" + cap + ")"
            );

            // +1-padded viewport keeping only tileX in [-1, 1] - excludes every remaining queued tile
            // (tileX in [cap, cap+2], and cap >= 1) unless cap == 1, in which case tileX=1 survives.
            predictionTiles.setViewport(DIM, 2, 0, 0, 0, 0);
            final Set<TileKey> pendingAfter = predictionTiles.pendingKeysForTest();
            for (final TileKey key : pendingAfter) {
                assertTrue(key.tileX() >= -1 && key.tileX() <= 1, "pruning should have dropped tile " + key);
            }
            assertTrue(pendingAfter.size() < pendingBefore.size(), "pruning should have removed at least one out-of-rect tile");
        } finally {
            release.countDown();
            executors.shutdown(2000);
        }
    }

    private static void awaitIdle(final PredictionTileService service) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 2000;
        while (!service.isIdleForTest() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(service.isIdleForTest(), "prediction tile service never drained its queue in time");
    }
}
