package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
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

    @Test
    void serverViewBoundaryKeepsPredictionUntilLocalChunkArrives() {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService worlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, WorldIdentity.singleplayer("pending-local-authority"), DimensionId.OVERWORLD
            );
            worlds.switchSession(session);
            final TileService tiles = new TileService(
                worlds, executors, new ConfluxConfig(), new DaylightModel()
            );
            tiles.setLocalAuthorityViewport(new ChunkViewport(0, 0, 0, 0));
            for (int lod = 0; lod <= TileMath.MAX_LOD; lod++) {
                final int[] pixels = predictedTile();
                tiles.maskKnownRealPixels(tileKey(session, lod), pixels);
                final int pixelsPerChunk = TileMath.CHUNKS_PER_TILE >> lod;
                for (int z = 0; z < pixelsPerChunk; z++) {
                    for (int x = 0; x < pixelsPerChunk; x++) {
                        assertEquals(
                            PREDICTED,
                            pixels[z * TileMath.TILE_SIZE + x],
                            "a newly entered server-view chunk must keep its predicted underlay at LOD" + lod
                        );
                    }
                }
            }

            assertTrue(worlds.current().put(
                MapLayer.SURFACE, snapshot(session.token(), 1L), SampleSource.REAL_LIVE
            ));
            for (int lod = 0; lod <= TileMath.MAX_LOD; lod++) {
                final int[] pixels = predictedTile();
                tiles.maskKnownRealPixels(tileKey(session, lod), pixels);
                assertEquals(
                    Argb.TRANSPARENT,
                    pixels[0],
                    "the local snapshot must take over at LOD" + lod
                );
            }
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void sparseNegativeRegionsPreservePerPixelSyncAndLocalAuthorityAtEveryCoarseLod() {
        final MapExecutors executors = new MapExecutors(1);
        try {
            for (int lod = 1; lod <= TileMath.MAX_LOD; lod++) {
                final MapWorldService worlds = new MapWorldService();
                final SessionGuard.Session session = new SessionGuard.Session(
                    1L, WorldIdentity.singleplayer("sparse-negative-mask"), DimensionId.OVERWORLD
                );
                worlds.switchSession(session);
                final TileService tiles = new TileService(
                    worlds, executors, new ConfluxConfig(), new DaylightModel()
                );
                final int chunks = 16 << lod;
                final int base = -chunks;
                final int pixelsPerChunk = 16 >> lod;
                // The first four chunks straddle the negative region boundary on both axes;
                // the last observed chunk also checks the far edge next to world zero.
                final int[][] observed = {{15, 15}, {16, 15}, {15, 16}, {16, 16}, {chunks - 1, chunks - 1}};
                for (int i = 0; i < observed.length; i++) {
                    final long revision = i == 2 ? Long.MIN_VALUE : 10L;
                    assertTrue(worlds.current().put(MapLayer.SURFACE,
                        snapshotAt(session.token(), revision, base + observed[i][0], base + observed[i][1]),
                        i == 2 ? SampleSource.IMPORTED : SampleSource.REAL_LIVE));
                }
                assertTrue(worlds.current().put(MapLayer.SURFACE,
                    snapshotAt(session.token(), 10L, base + 17, base + 17), SampleSource.PREDICTED));
                tiles.setLocalAuthorityViewport(new ChunkViewport(base + 16, base + 16, base + 16, base + 16));
                final TileKey key = new TileKey(session.world(), session.dimension(),
                    MapLayer.SURFACE.cacheId(), lod, -1, -1);
                final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
                final long[] revisions = new long[chunks * chunks];
                Arrays.fill(revisions, Long.MIN_VALUE);
                for (int i = 0; i < observed.length; i++) {
                    final int x = observed[i][0];
                    final int z = observed[i][1];
                    PatchCodec.setEvaluated(evaluated, z * pixelsPerChunk * 256 + x * pixelsPerChunk);
                    revisions[z * chunks + x] = i == 1 ? 9L : i == 4 ? 10L : 11L;
                }
                for (final CorrectionProfile profile : CorrectionProfile.values()) {
                    final int[] pixels = predictedTile();
                    final int[] expected = predictedTile();
                    for (int i = 0; i < observed.length; i++) {
                        final int x0 = observed[i][0] * pixelsPerChunk;
                        final int z0 = observed[i][1] * pixelsPerChunk;
                        for (int z = z0; z < z0 + pixelsPerChunk; z++) {
                            Arrays.fill(expected, z * 256 + x0, z * 256 + x0 + pixelsPerChunk,
                                Argb.TRANSPARENT);
                        }
                        // Newer evaluated sync can cover observed terrain outside player view;
                        // unevaluated pixels, older/equal sync, and the player-view chunk stay local.
                        if (profile.carriesSourceMetadata() && (i == 0 || i == 2)) {
                            expected[z0 * 256 + x0] = PREDICTED;
                        }
                    }
                    tiles.maskPredictedPixels(key, pixels, evaluated, revisions, profile);
                    assertArrayEquals(expected, pixels, "LOD" + lod + " profile " + profile);
                }
            }
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static int[] predictedTile() {
        final int[] pixels = new int[TileMath.TILE_SIZE * TileMath.TILE_SIZE];
        Arrays.fill(pixels, PREDICTED);
        return pixels;
    }

    private static TileKey tileKey(final SessionGuard.Session session, final int lod) {
        return new TileKey(
            session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), lod, 0, 0
        );
    }

    private static int maskedPixel(
        final TileService tiles,
        final TileKey key,
        final CorrectionTile correction
    ) {
        final int[] pixels = new int[TileMath.TILE_SIZE * TileMath.TILE_SIZE];
        Arrays.fill(pixels, PREDICTED);
        tiles.maskPredictedPixels(
            key, pixels, correction,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
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
        return snapshotAt(token, revision, 0, 0);
    }

    private static ChunkSnapshot snapshotAt(
        final long token, final long revision, final int chunkX, final int chunkZ
    ) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 65);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.LAND.ordinal());
        return new ChunkSnapshot(
            chunkX, chunkZ, token, revision,
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
