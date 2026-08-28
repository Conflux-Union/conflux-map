package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.predict.PredictedTileKeys;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * The upload queue is bounded, so a burst of compositions can outrun the render thread. What it
 * must never do is forget one: composition takes its tile out of the dirty map, and the renderer
 * only re-requests a tile whose texture is missing entirely, so a dropped update leaves an
 * on-screen tile frozen at whatever it last uploaded - captured chunks silently never appear.
 */
class TileServiceUploadQueueTest {
    /** Comfortably past {@code TileService.UPLOAD_QUEUE_CAPACITY}, whatever the render thread does. */
    private static final int REGIONS = 96;

    @Test
    void visibleViewportUsesEveryCompositionWorker() {
        final Fixture fixture = new Fixture();
        final CountDownLatch release = new CountDownLatch(1);
        try {
            for (int worker = 0; worker < fixture.executors.workerCount(); worker++) {
                fixture.executors.workers().execute(() -> {
                    try {
                        release.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            fixture.tiles.setViewport(
                MapLayer.SURFACE, 0, 0, fixture.executors.workerCount() - 1, 0, 0
            );
            for (int tileX = 0; tileX < fixture.executors.workerCount(); tileX++) {
                fixture.tiles.requestTile(fixture.key(0, tileX, 0));
            }

            assertEquals(
                fixture.executors.workerCount(),
                fixture.tiles.inFlightCountForTest(),
                "publishing a viewport must retain worker-pool parallelism"
            );
        } finally {
            release.countDown();
            fixture.close();
        }
    }

    @Test
    void everyInvalidatedTileReachesTheRenderThreadWhenCompositionsOutrunUploads() throws InterruptedException {
        final Fixture fixture = new Fixture();
        try {
            final Set<TileKey> expected = new HashSet<>();
            // The renderer is showing this whole row of tiles, so every one of them has a texture
            // waiting on the capture that follows.
            fixture.tiles.setViewport(MapLayer.SURFACE, 0, 0, REGIONS - 1, 0, 0);
            for (int region = 0; region < REGIONS; region++) {
                expected.add(fixture.key(0, region, 0));
                fixture.tiles.requestTile(fixture.key(0, region, 0));
            }
            fixture.settle();
            for (int region = 0; region < REGIONS; region++) {
                // One chunk per region, away from the tile edges so no relief neighbour is pulled in.
                fixture.tiles.markChunkStored(
                    fixture.session.token(), DimensionId.OVERWORLD, MapLayer.SURFACE, region * 16 + 8, 8
                );
            }
            // Let the composers fill the queue before the first drain, the way a capture burst does
            // between two rendered frames.
            Thread.sleep(200L);

            final Set<TileKey> uploaded = fixture.drainUntilIdle(expected);

            assertTrue(
                uploaded.containsAll(expected),
                () -> "never uploaded: " + missing(expected, uploaded)
            );
        } finally {
            fixture.close();
        }
    }

    @Test
    void anEvictedPredictedUploadGoesBackToThePredictionPlane() {
        final Fixture fixture = new Fixture();
        try {
            final List<TileKey> reloaded = new ArrayList<>();
            fixture.tiles.bindPredictedUploadReloader(reloaded::add);
            // Oldest in the queue, so it is the one the capacity limit evicts.
            final TileKey predicted = PredictedTileKeys.toPredicted(fixture.key(0, 0, 0));
            fixture.tiles.submitUpload(TileUpdate.fullTile(predicted, blankTile()));

            // Stops at the first eviction, so the assertion holds whatever the capacity is.
            for (int region = 1; region <= REGIONS && reloaded.isEmpty(); region++) {
                fixture.tiles.submitUpload(TileUpdate.fullTile(fixture.key(0, region, 0), blankTile()));
            }

            assertEquals(List.of(predicted), reloaded);
        } finally {
            fixture.close();
        }
    }

    @Test
    void lightingReloadDropsQueuedCompositions() {
        final Fixture fixture = new Fixture();
        try {
            fixture.tiles.submitUpload(TileUpdate.fullTile(fixture.key(0, 0, 0), blankTile()));

            fixture.tiles.reloadLighting();

            assertTrue(fixture.tiles.drainUploads(1).isEmpty());
        } finally {
            fixture.close();
        }
    }

    @Test
    void partialUploadsForOneTileAreMergedBeforeRenderDrain() {
        final Fixture fixture = new Fixture();
        try {
            final TileKey key = fixture.key(1, 0, 0);
            final int[] northWest = blankTile();
            northWest[0] = 0xFF112233;
            final int[] southEast = blankTile();
            southEast[RegionColumns.SIZE * RegionColumns.SIZE - 1] = 0xFF445566;
            final byte[] firstLight = new byte[RegionColumns.SIZE * RegionColumns.SIZE];
            firstLight[0] = 7;
            final byte[] secondLight = new byte[RegionColumns.SIZE * RegionColumns.SIZE];
            secondLight[RegionColumns.SIZE * RegionColumns.SIZE - 1] = 11;
            final TileUpdate.Rect first = new TileUpdate.Rect(0, 0, 1, 1);
            final TileUpdate.Rect second = new TileUpdate.Rect(255, 255, 1, 1);

            fixture.tiles.submitUpload(new TileUpdate(
                key, northWest, List.of(first),
                new TileUpdate.Relight(1f, 0f, firstLight, MapColorStyle.CONFLUX)
            ));
            fixture.tiles.submitUpload(new TileUpdate(
                key, southEast, List.of(second),
                new TileUpdate.Relight(1f, 0f, secondLight, MapColorStyle.CONFLUX)
            ));

            final TileUpdate merged = fixture.tiles.drainUploads(1).get(0);
            assertEquals(Set.of(first, second), Set.copyOf(merged.changed()));
            assertEquals(0xFF112233, merged.argbPixels()[0]);
            assertEquals(
                0xFF445566,
                merged.argbPixels()[RegionColumns.SIZE * RegionColumns.SIZE - 1]
            );
            assertEquals(7, merged.relight().lightLevels()[0]);
            assertEquals(
                11,
                merged.relight().lightLevels()[RegionColumns.SIZE * RegionColumns.SIZE - 1]
            );
        } finally {
            fixture.close();
        }
    }

    private static int[] blankTile() {
        return new int[RegionColumns.SIZE * RegionColumns.SIZE];
    }

    private static Set<TileKey> missing(final Set<TileKey> expected, final Set<TileKey> uploaded) {
        final Set<TileKey> gap = new HashSet<>(expected);
        gap.removeAll(uploaded);
        return gap;
    }

    private static final class Fixture {
        private final SessionGuard.Session session = new SessionGuard.Session(
            1L, new WorldIdentity("local", "upload-queue"), DimensionId.OVERWORLD
        );
        private final MapExecutors executors = new MapExecutors();
        private final TileService tiles;

        private Fixture() {
            final MapWorldService worlds = new MapWorldService();
            worlds.switchSession(session);
            tiles = new TileService(worlds, executors, new ConfluxConfig(), new DaylightModel());
        }

        private TileKey key(final int lod, final int tileX, final int tileZ) {
            return new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), lod, tileX, tileZ
            );
        }

        /** Drains until the composer has been quiet for a few passes, so what follows is the new work. */
        private void settle() throws InterruptedException {
            int idle = 0;
            while (idle < 3) {
                Thread.sleep(20L);
                idle = tiles.drainUploads(64).isEmpty() ? idle + 1 : 0;
            }
        }

        /** Drains at the render thread's pace until every expected tile has arrived, or time runs out. */
        private Set<TileKey> drainUntilIdle(final Set<TileKey> expected) throws InterruptedException {
            final Set<TileKey> uploaded = new HashSet<>();
            final long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline && !uploaded.containsAll(expected)) {
                for (final TileUpdate update : tiles.drainUploads(8)) {
                    uploaded.add(update.key());
                }
                Thread.sleep(5L);
            }
            return uploaded;
        }

        private void close() {
            executors.shutdown(1000L);
        }
    }
}
