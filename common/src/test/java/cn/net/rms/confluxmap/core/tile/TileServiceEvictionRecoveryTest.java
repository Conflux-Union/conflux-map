package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class TileServiceEvictionRecoveryTest {
    private static final long TOKEN = 1L;
    private static final WorldIdentity WORLD = new WorldIdentity("local", "eviction-recovery");

    @Test
    void requestTileUpgradesQueuedIncrementalWorkToFullCompositionAfterForget() throws InterruptedException {
        final MapExecutors executors = new MapExecutors(1);
        final CountDownLatch holdWorker = new CountDownLatch(1);
        final MapWorldService worlds = new MapWorldService();
        final SessionGuard.Session session = new SessionGuard.Session(
            TOKEN, WORLD, DimensionId.OVERWORLD
        );
        worlds.switchSession(session);
        final TileService tiles = new TileService(
            worlds, executors, new ConfluxConfig(), new DaylightModel()
        );
        try {
            // LOD 2 tile (0,0) covers regions (0..3, 0..3). Populate two disjoint regions: (0,0) and (1,1).
            putRegionChunk(worlds, 0, 0);
            putRegionChunk(worlds, 16, 16);
            final TileKey key = new TileKey(WORLD, DimensionId.OVERWORLD, MapLayer.SURFACE.cacheId(), 2, 0, 0);
            tiles.setViewport(MapLayer.SURFACE, 2, 0, 0, 0, 0);
            tiles.requestTile(key);
            final TileUpdate initial = awaitNextUpload(tiles, key);
            assertTrue(initial.fullComposition());
            assertTrue(initial.hasVisiblePixels());
            assertEquals(2, initial.changed().size());

            // Hold the single worker so markRegionStored leaves an incremental DirtyWork queued.
            executors.workers().execute(() -> {
                try {
                    holdWorker.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread.sleep(30L);

            tiles.markRegionStored(TOKEN, DimensionId.OVERWORLD, MapLayer.SURFACE, 1, 1);
            // Evict texture and re-request while the incremental update is still queued in dirty.
            tiles.forgetTile(key);
            tiles.requestTile(key);
            holdWorker.countDown();

            final TileUpdate recovered = awaitNextUpload(tiles, key);
            assertTrue(recovered.fullComposition(), "evicted tile recovery must perform a full composition");
            assertEquals(
                2,
                recovered.changed().size(),
                "full composition after eviction must include both (0,0) and (1,1) quadrants, not just (1,1)"
            );
        } finally {
            holdWorker.countDown();
            executors.shutdown(2_000L);
        }
    }

    @Test
    void forgetTileDropsQueuedIncrementalUpdateBeforeWorkerStartsIt() throws InterruptedException {
        final MapExecutors executors = new MapExecutors(1);
        final CountDownLatch holdWorker = new CountDownLatch(1);
        final MapWorldService worlds = new MapWorldService();
        final SessionGuard.Session session = new SessionGuard.Session(
            TOKEN, WORLD, DimensionId.OVERWORLD
        );
        worlds.switchSession(session);
        final TileService tiles = new TileService(
            worlds, executors, new ConfluxConfig(), new DaylightModel()
        );
        try {
            putRegionChunk(worlds, 0, 0);
            final TileKey key = new TileKey(WORLD, DimensionId.OVERWORLD, MapLayer.SURFACE.cacheId(), 2, 0, 0);
            tiles.setViewport(MapLayer.SURFACE, 2, 0, 0, 0, 0);
            tiles.requestTile(key);
            assertNotNull(awaitNextUpload(tiles, key));

            executors.workers().execute(() -> {
                try {
                    holdWorker.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread.sleep(30L);

            // Queue incremental work on the upload queue as well as dirty, then forget the tile.
            tiles.markChunkStored(TOKEN, DimensionId.OVERWORLD, MapLayer.SURFACE, 0, 0);
            tiles.forgetTile(key);
            holdWorker.countDown();

            Thread.sleep(80L);
            assertTrue(
                tiles.drainUploads(16).isEmpty(),
                "forgotten tile must not emit stranded incremental updates"
            );
        } finally {
            holdWorker.countDown();
            executors.shutdown(2_000L);
        }
    }

    @Test
    void emptyAndFullTileUpdateSemantics() {
        final TileKey key = new TileKey(WORLD, DimensionId.OVERWORLD, MapLayer.SURFACE.cacheId(), 1, 0, 0);
        final int[] blank = new int[256 * 256];
        final TileUpdate emptyChanged = TileUpdate.composed(key, blank, List.of(), null, true);
        assertFalse(emptyChanged.hasVisiblePixels());
        assertTrue(emptyChanged.fullComposition());

        final TileUpdate transparentRect = TileUpdate.composed(
            key, blank, List.of(new TileUpdate.Rect(0, 0, 128, 128)), null, true
        );
        assertFalse(transparentRect.hasVisiblePixels());

        final int[] visible = new int[256 * 256];
        visible[10 * 256 + 10] = 0xFF336699;
        final TileUpdate withPixels = TileUpdate.composed(
            key, visible, List.of(new TileUpdate.Rect(0, 0, 128, 128)), null, true
        );
        assertTrue(withPixels.hasVisiblePixels());

        final TileUpdate patch = TileUpdate.patch(
            key, new int[] {0xFF112233}, new TileUpdate.Rect(128, 128, 1, 1),
            new TileUpdate.Relight(1f, 0f, new byte[] {4}, MapColorStyle.CONFLUX)
        );
        assertFalse(patch.fullComposition());
        assertTrue(patch.hasVisiblePixels());

        final TileUpdate mergedOverFull = TileUpdate.merge(withPixels, patch);
        assertTrue(mergedOverFull.fullComposition());
        assertEquals(0xFF336699, mergedOverFull.pixelAt(10, 10));
        assertEquals(0xFF112233, mergedOverFull.pixelAt(128, 128));

        final TileUpdate mergedPatchOnly = TileUpdate.merge(patch, TileUpdate.patch(
            key, new int[] {0xFF445566}, new TileUpdate.Rect(129, 129, 1, 1),
            new TileUpdate.Relight(1f, 0f, new byte[] {5}, MapColorStyle.CONFLUX)
        ));
        assertFalse(mergedPatchOnly.fullComposition());
    }

    private static void putRegionChunk(final MapWorldService worlds, final int chunkX, final int chunkZ) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 64);
        final String[] biomeId = new String[ChunkSnapshot.COLUMNS];
        Arrays.fill(biomeId, "minecraft:plains");
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(baseArgb, 0xFF448844);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.LAND.ordinal());
        worlds.current().put(
            MapLayer.SURFACE,
            new ChunkSnapshot(
                chunkX, chunkZ, TOKEN, 10L,
                surfaceY, biomeId, new byte[ChunkSnapshot.COLUMNS],
                baseArgb, baseArgb, new int[ChunkSnapshot.COLUMNS],
                kind, new byte[ChunkSnapshot.COLUMNS]
            ),
            SampleSource.REAL_LIVE
        );
    }

    private static TileUpdate awaitNextUpload(final TileService tiles, final TileKey target)
        throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            for (final TileUpdate update : tiles.drainUploads(16)) {
                if (update.key().equals(target)) {
                    return update;
                }
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for upload of " + target);
    }
}
