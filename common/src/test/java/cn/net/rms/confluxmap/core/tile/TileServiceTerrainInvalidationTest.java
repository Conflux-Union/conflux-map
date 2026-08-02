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
            fixture.tiles.markChunkStored(1L, DimensionId.OVERWORLD, MapLayer.SURFACE, 0, 0);

            final Set<TileKey> expected = new HashSet<>();
            for (int lod = 0; lod <= TileMath.MAX_LOD; lod++) {
                expected.add(fixture.key(lod, 0, 0));
                expected.add(fixture.key(lod, -1, 0));
                expected.add(fixture.key(lod, 0, -1));
                expected.add(fixture.key(lod, -1, -1));
            }
            assertEquals(expected, fixture.drainKeys(expected.size()));
        } finally {
            fixture.close();
        }
    }

    @Test
    void loadedRegionInvalidatesAllNineLod0NeighborsAndOnlyTouchedCoarseEdges() throws InterruptedException {
        final Fixture fixture = new Fixture();
        try {
            fixture.tiles.markRegionStored(1L, DimensionId.OVERWORLD, MapLayer.SURFACE, 0, 0);

            final Set<TileKey> expected = new HashSet<>();
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    expected.add(fixture.key(0, x, z));
                }
            }
            for (int lod = 1; lod <= TileMath.MAX_LOD; lod++) {
                expected.add(fixture.key(lod, 0, 0));
                expected.add(fixture.key(lod, -1, 0));
                expected.add(fixture.key(lod, 0, -1));
                expected.add(fixture.key(lod, -1, -1));
            }
            assertEquals(expected, fixture.drainKeys(expected.size()));
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
