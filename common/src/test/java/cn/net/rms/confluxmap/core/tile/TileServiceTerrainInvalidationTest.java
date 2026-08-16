package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TileServiceTerrainInvalidationTest {
    @Test
    void chunkOnNorthWestTileBoundaryInvalidatesEveryReliefConsumerAtEachLod() throws InterruptedException {
        final Fixture fixture = new Fixture();
        try {
            for (int lod = 0; lod <= TileMath.MAX_LOD; lod++) {
                final Set<TileKey> expected = Set.of(
                    fixture.key(lod, 0, 0), fixture.key(lod, -1, 0),
                    fixture.key(lod, 0, -1), fixture.key(lod, -1, -1)
                );
                fixture.showing(lod);

                fixture.tiles.markChunkStored(1L, DimensionId.OVERWORLD, MapLayer.SURFACE, 0, 0);

                assertEquals(expected, fixture.drainKeys(expected.size()), "at LOD" + lod);
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    void loadedRegionInvalidatesAllNineLod0NeighborsAndOnlyTouchedCoarseEdges() throws InterruptedException {
        final Fixture fixture = new Fixture();
        try {
            final Set<TileKey> lod0 = new HashSet<>();
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    lod0.add(fixture.key(0, x, z));
                }
            }
            for (int lod = 0; lod <= TileMath.MAX_LOD; lod++) {
                final Set<TileKey> expected = lod == 0 ? lod0 : Set.of(
                    fixture.key(lod, 0, 0), fixture.key(lod, -1, 0),
                    fixture.key(lod, 0, -1), fixture.key(lod, -1, -1)
                );
                fixture.showing(lod);

                fixture.tiles.markRegionStored(1L, DimensionId.OVERWORLD, MapLayer.SURFACE, 0, 0);

                assertEquals(expected, fixture.drainKeys(expected.size()), "at LOD" + lod);
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    void aTileTheRendererNeverAskedForIsNotComposedOnCapture() throws InterruptedException {
        final Fixture fixture = new Fixture();
        try {
            fixture.tiles.setViewport(MapLayer.SURFACE, 0, -2, 2, -2, 2);

            fixture.tiles.markChunkStored(1L, DimensionId.OVERWORLD, MapLayer.SURFACE, 0, 0);

            final Set<TileKey> keys = new HashSet<>();
            for (int pass = 0; pass < 10; pass++) {
                Thread.sleep(20L);
                fixture.tiles.drainUploads(64).forEach(update -> keys.add(update.key()));
            }
            assertEquals(
                Set.of(), keys, "composing a tile with no texture behind it is work nobody can see"
            );
        } finally {
            fixture.close();
        }
    }

    private static final class Fixture {
        private final SessionGuard.Session session = new SessionGuard.Session(
            1L, new WorldIdentity("local", "invalidation"), DimensionId.OVERWORLD
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

        /**
         * Puts the renderer on {@code lod}: publishes a viewport wide enough for every key the
         * invalidation tests look at and asks for those tiles, since an invalidation only reaches
         * tiles the renderer holds and is showing.
         */
        private void showing(final int lod) throws InterruptedException {
            tiles.setViewport(MapLayer.SURFACE, lod, -2, 2, -2, 2);
            for (int z = -2; z <= 2; z++) {
                for (int x = -2; x <= 2; x++) {
                    tiles.requestTile(key(lod, x, z));
                }
            }
            int idle = 0;
            while (idle < 3) {
                Thread.sleep(20L);
                idle = tiles.drainUploads(64).isEmpty() ? idle + 1 : 0;
            }
        }

        private Set<TileKey> drainKeys(final int count) throws InterruptedException {
            final Set<TileKey> keys = new HashSet<>();
            final long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline && keys.size() < count) {
                for (final TileUpdate update : tiles.drainUploads(64)) {
                    keys.add(update.key());
                }
                Thread.sleep(10L);
            }
            return keys;
        }

        private void close() {
            executors.shutdown(1000L);
        }
    }
}
