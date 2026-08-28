package cn.net.rms.confluxmap.core.tile;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measures composition wall-clock through the real {@link TileService} queue: what one tile
 * costs per LOD, and whether publishing a viewport preserves parallel throughput for a full
 * screen's worth of tiles. No performance threshold is asserted; the timeout only
 * prevents a wedged queue from hanging the harness.
 */
@Tag("benchmark")
class TileComposeThroughputBenchmarkTest {
    private static final int REGIONS_PER_SIDE = 4;
    private static final int ROUNDS = 5;

    @Test
    void measureComposeThroughput() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, new WorldIdentity("local", "world"), DimensionId.OVERWORLD
            );
            mapWorlds.switchSession(session);
            fill(mapWorlds.current());
            final TileService tiles = new TileService(
                mapWorlds, executors, new ConfluxConfig(), new DaylightModel()
            );

            System.out.println();
            System.out.println("workers=" + executors.workerCount() + ", regions resident=" + (REGIONS_PER_SIDE * REGIONS_PER_SIDE));

            final List<TileKey> lod0 = new ArrayList<>();
            for (int z = 0; z < REGIONS_PER_SIDE; z++) {
                for (int x = 0; x < REGIONS_PER_SIDE; x++) {
                    lod0.add(key(session, 0, x, z));
                }
            }
            // Warm up the compose path before any measurement.
            for (int i = 0; i < 3; i++) {
                composeAll(tiles, lod0);
            }

            System.out.printf("%-34s %9s %11s%n", "case", "ms", "ms/region");
            single(tiles, session, 0, 1);
            single(tiles, session, 1, 4);
            single(tiles, session, 2, 16);

            final long parallel = best(() -> {
                tiles.clearViewport();
                return composeAll(tiles, lod0);
            });
            System.out.printf(
                "%-34s %9.1f %11.2f%n",
                "16 LOD0 tiles, no viewport", parallel / 1e6, parallel / 1e6 / 16
            );

            final long visible = best(() -> {
                tiles.setViewport(MapLayer.SURFACE, 0, 0, REGIONS_PER_SIDE - 1, 0, REGIONS_PER_SIDE - 1);
                return composeAll(tiles, lod0);
            });
            System.out.printf(
                "%-34s %9.1f %11.2f%n",
                "16 LOD0 tiles, viewport published", visible / 1e6, visible / 1e6 / 16
            );
            System.out.printf("viewport/no-viewport ratio %.2fx%n", (double) visible / parallel);
            tiles.clearViewport();
        } finally {
            executors.shutdown(5000L);
        }
    }

    private static void single(
        final TileService tiles, final SessionGuard.Session session, final int lod, final int regions
    ) throws InterruptedException {
        final List<TileKey> one = List.of(key(session, lod, 0, 0));
        final long elapsed = best(() -> composeAll(tiles, one));
        System.out.printf(
            "%-34s %9.1f %11.2f%n",
            "1 LOD" + lod + " tile (" + regions + " regions)", elapsed / 1e6, elapsed / 1e6 / regions
        );
    }

    private static long best(final Round round) throws InterruptedException {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < ROUNDS; i++) {
            best = Math.min(best, round.run());
        }
        return best;
    }

    /** Requests every key and returns the nanos until the last upload landed. */
    private static long composeAll(final TileService tiles, final List<TileKey> keys)
        throws InterruptedException {
        tiles.drainUploads(256);
        final Set<TileKey> pending = new HashSet<>(keys);
        final long start = System.nanoTime();
        for (final TileKey key : keys) {
            tiles.requestTile(key);
        }
        final long deadline = start + 60_000_000_000L;
        while (!pending.isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("compose timed out, " + pending.size() + " outstanding");
            }
            for (final TileUpdate update : tiles.drainUploads(256)) {
                pending.remove(update.key());
            }
            Thread.onSpinWait();
        }
        return System.nanoTime() - start;
    }

    private static TileKey key(
        final SessionGuard.Session session, final int lod, final int tileX, final int tileZ
    ) {
        return new TileKey(
            session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), lod, tileX, tileZ
        );
    }

    /** Fills every resident region with deterministic non-trivial terrain. */
    private static void fill(final MapWorld world) {
        final int chunksPerRegion = RegionColumns.SIZE / 16;
        for (int regionZ = 0; regionZ < REGIONS_PER_SIDE; regionZ++) {
            for (int regionX = 0; regionX < REGIONS_PER_SIDE; regionX++) {
                for (int cz = 0; cz < chunksPerRegion; cz++) {
                    for (int cx = 0; cx < chunksPerRegion; cx++) {
                        world.put(
                            MapLayer.SURFACE,
                            snapshot(regionX * chunksPerRegion + cx, regionZ * chunksPerRegion + cz),
                            SampleSource.REAL_LIVE
                        );
                    }
                }
            }
        }
    }

    private static ChunkSnapshot snapshot(final int chunkX, final int chunkZ) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final String[] biomeId = new String[ChunkSnapshot.COLUMNS];
        final byte[] fluidDepth = new byte[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] overlayArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        final byte[] light = new byte[ChunkSnapshot.COLUMNS];
        long state = chunkX * 0x9E3779B97F4A7C15L ^ chunkZ * 0xC2B2AE3D27D4EB4FL;
        for (int i = 0; i < ChunkSnapshot.COLUMNS; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            final int noise = (int) (state >>> 32);
            surfaceY[i] = (short) (64 + (noise & 0x3F));
            biomeId[i] = "minecraft:plains";
            baseArgb[i] = 0xFF000000 | (noise & 0x00FFFFFF);
            tintArgb[i] = 0xFF7CBD6B;
            light[i] = (byte) (noise & 0x0F);
            // A column whose kind is UNKNOWN/VOID is written straight to transparent, so the
            // shading pipeline never runs for it - the mix here has to look like real terrain
            // or the benchmark measures nothing but the store copy.
            if ((noise & 0x7) == 0) {
                kind[i] = (byte) SurfaceKind.WATER.ordinal();
                fluidDepth[i] = (byte) (1 + (noise >>> 8 & 0x7));
                overlayArgb[i] = 0xFF3F76E4;
            } else if ((noise & 0x7) == 1) {
                kind[i] = (byte) SurfaceKind.FOLIAGE.ordinal();
                overlayArgb[i] = 0xFF59A63A;
            } else {
                kind[i] = (byte) SurfaceKind.LAND.ordinal();
            }
        }
        return new ChunkSnapshot(
            chunkX, chunkZ, 1L, surfaceY, biomeId, fluidDepth,
            baseArgb, tintArgb, overlayArgb, kind, light
        );
    }

    @FunctionalInterface
    private interface Round {
        long run() throws InterruptedException;
    }
}
