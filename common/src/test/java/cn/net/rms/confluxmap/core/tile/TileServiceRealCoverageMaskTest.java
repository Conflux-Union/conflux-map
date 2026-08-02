package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.color.DaylightModel;
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
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TileServiceRealCoverageMaskTest {
    private static final int PREDICTED = 0xFFCC8844;

    @Test
    void masksTheExactKnownChunkFootprintAtEveryLodIncludingNegativeCoordinates() {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService worlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, WorldIdentity.singleplayer("real-coverage-mask"), DimensionId.END
            );
            worlds.switchSession(session);
            worlds.current().put(
                MapLayer.END_SURFACE, voidSnapshot(session.token()), SampleSource.REAL_LIVE
            );
            final TileService tiles = new TileService(
                worlds, executors, new ConfluxConfig(), new DaylightModel()
            );

            for (int lod = 0; lod <= TileMath.MAX_LOD; lod++) {
                final int[] pixels = new int[TileMath.TILE_SIZE * TileMath.TILE_SIZE];
                Arrays.fill(pixels, PREDICTED);
                final TileKey key = new TileKey(
                    session.world(), session.dimension(), MapLayer.END_SURFACE.cacheId(),
                    lod, -1, -1
                );

                tiles.maskKnownRealPixels(key, pixels);

                final int pixelsPerChunk = TileMath.CHUNKS_PER_TILE >> lod;
                final int maskedX = TileMath.TILE_SIZE - pixelsPerChunk;
                final int maskedZ = TileMath.TILE_SIZE - pixelsPerChunk;
                assertEquals(
                    Argb.TRANSPARENT,
                    pixels[maskedZ * TileMath.TILE_SIZE + maskedX],
                    "LOD" + lod + " must clear the captured negative-coordinate chunk"
                );
                assertEquals(
                    PREDICTED,
                    pixels[maskedZ * TileMath.TILE_SIZE + maskedX - 1],
                    "LOD" + lod + " must preserve the adjacent uncaptured chunk"
                );
            }
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static ChunkSnapshot voidSnapshot(final long token) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 65);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.VOID.ordinal());
        return new ChunkSnapshot(
            -1, -1, token,
            surfaceY,
            new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            kind,
            new byte[ChunkSnapshot.COLUMNS]
        );
    }
}
