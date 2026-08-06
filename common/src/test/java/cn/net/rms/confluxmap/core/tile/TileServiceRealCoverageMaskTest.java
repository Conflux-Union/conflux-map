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
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.predict.CorrectionTile;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.Arrays;
import java.util.List;
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

    @Test
    void enhancedSyncRemainsVisibleOnlyWhenItsChunkRevisionIsNewer() {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService worlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, WorldIdentity.singleplayer("source-selection"), DimensionId.OVERWORLD
            );
            worlds.switchSession(session);
            worlds.current().put(
                MapLayer.SURFACE, snapshot(session.token(), 10L), SampleSource.REAL_LIVE
            );
            final TileService tiles = new TileService(
                worlds, executors, new ConfluxConfig(), new DaylightModel()
            );
            final TileKey key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 0, 0
            );

            assertEquals(PREDICTED, maskedPixel(tiles, key, correction(11L)));
            assertEquals(Argb.TRANSPARENT, maskedPixel(tiles, key, correction(10L)));
            assertEquals(Argb.TRANSPARENT, maskedPixel(tiles, key, correction(9L)));
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void serverViewChunkAlwaysUsesLocalAuthorityEvenWhenSyncIsNewer() {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService worlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, WorldIdentity.singleplayer("loaded-source-selection"), DimensionId.OVERWORLD
            );
            worlds.switchSession(session);
            worlds.current().put(
                MapLayer.SURFACE, snapshot(session.token(), 10L), SampleSource.REAL_LIVE
            );
            final TileService tiles = new TileService(
                worlds, executors, new ConfluxConfig(), new DaylightModel()
            );
            tiles.setLocalAuthorityViewport(new ChunkViewport(0, 0, 0, 0));
            final TileKey key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 0, 0
            );

            assertEquals(Argb.TRANSPARENT, maskedPixel(tiles, key, correction(11L)));
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static int maskedPixel(
        final TileService tiles,
        final TileKey key,
        final CorrectionTile correction
    ) {
        final int[] pixels = new int[TileMath.TILE_SIZE * TileMath.TILE_SIZE];
        Arrays.fill(pixels, PREDICTED);
        tiles.maskPredictedPixels(key, pixels, correction, true);
        return pixels[0];
    }

    private static CorrectionTile correction(final long sourceRevision) {
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        PatchCodec.setEvaluated(evaluated, 0);
        final long[] revisions = new long[PatchCodec.PIXELS];
        Arrays.fill(revisions, Long.MIN_VALUE);
        revisions[0] = sourceRevision;
        final CorrectionTile tile = new CorrectionTile(0);
        tile.applyPatch(
            1L, new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(
                evaluated, List.of(), revisions, new byte[PatchCodec.PIXELS]
            )
        );
        return tile;
    }

    private static ChunkSnapshot snapshot(final long token, final long revision) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 65);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.LAND.ordinal());
        return new ChunkSnapshot(
            0, 0, token, revision,
            surfaceY, new String[ChunkSnapshot.COLUMNS], new byte[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS], new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS], kind, new byte[ChunkSnapshot.COLUMNS]
        );
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
