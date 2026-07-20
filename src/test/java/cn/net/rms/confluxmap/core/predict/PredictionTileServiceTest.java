package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        return newService(sessionGuard, new PredictionState(), executors, uploads);
    }

    private static PredictionTileService newService(
        final SessionGuard sessionGuard,
        final PredictionState state,
        final MapExecutors executors,
        final TileService uploads
    ) {
        return new PredictionTileService(sessionGuard, state, executors, uploads);
    }

    @Test
    void composedPredictionExposesItsBiomeForCursorReadout(@TempDir final Path tempDir) throws InterruptedException {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionState state = new PredictionState();
        state.set(146008555L, McVersions.toCubiomes("1.17").orElseThrow());
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        final CorrectionStore corrections = new CorrectionStore(tempDir);
        predictionTiles.bindCorrectionStore(corrections);
        sessionGuard.begin(WORLD, DIM);

        try {
            predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 4, 0, 0));
            awaitIdle(predictionTiles, 10_000L);

            assertEquals(35, predictionTiles.predictedBiomeAt(DIM, 4, 0, 0).orElse(-1));

            final PatchCodec.Sample correction = new PatchCodec.Sample(
                0, 4, 80, SurfaceKind.LAND.ordinal(), Proto.MAP_COLOR_NONE, 0
            );
            assertTrue(predictionTiles.applyCorrection(
                new CorrectionStore.Key(DIM.toString(), 4, 0, 0),
                1L,
                new byte[Proto.PATCH_PRESENCE_BYTES],
                new PatchCodec.Patch(List.of(correction))
            ));
            awaitIdle(predictionTiles, 10_000L);
            assertEquals(4, predictionTiles.predictedBiomeAt(DIM, 4, 0, 0).orElse(-1));

            predictionTiles.setViewMode(PredictionViewMode.VISITED_ONLY);
            assertTrue(predictionTiles.predictedBiomeAt(DIM, 4, 0, 0).isEmpty());

            predictionTiles.setViewMode(PredictionViewMode.EVERYWHERE);
            predictionTiles.clearViewport();
            assertTrue(
                predictionTiles.predictedBiomeAt(DIM, 4, 0, 0).isEmpty(),
                "a cleared metadata entry should be requeued instead of returning stale data"
            );
            awaitIdle(predictionTiles, 10_000L);
            assertEquals(4, predictionTiles.predictedBiomeAt(DIM, 4, 0, 0).orElse(-1));
        } finally {
            executors.shutdown(2000);
        }
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

    @Test
    void reloadRequeuesInFlightTilesWithoutSchedulingDuplicates() throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionTileService predictionTiles = newService(sessionGuard, executors, uploads);
        sessionGuard.begin(WORLD, DIM);

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

        final TileKey key = new TileKey(WORLD, DIM, "surface!pred", 2, 3, 4);
        try {
            predictionTiles.requestTile(key);
            predictionTiles.reloadAll();
            assertTrue(predictionTiles.pendingKeysForTest().contains(key), "reload must requeue an in-flight tile");
        } finally {
            release.countDown();
            executors.shutdown(2000);
        }
    }

    private static void awaitIdle(final PredictionTileService service) throws InterruptedException {
        awaitIdle(service, 2000L);
    }

    private static void awaitIdle(final PredictionTileService service, final long timeoutMillis) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!service.isIdleForTest() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(service.isIdleForTest(), "prediction tile service never drained its queue in time");
    }
}
