package cn.net.rms.confluxmap.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapRegionPatchS2C;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapSyncProgress;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.predict.CorrectionStore;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapSyncChunkViewportTest {
    @Test
    void chunkCapableCompanionRequestsOnlyIntersectingRegionSlices(
        @TempDir final Path tempDir
    ) {
        final DimensionId dimension = DimensionId.OVERWORLD;
        final SessionGuard sessions = new SessionGuard();
        sessions.begin(WorldIdentity.singleplayer("chunk-viewport"), dimension);
        final MapExecutors executors = new MapExecutors();
        try {
            final ConfluxConfig config = new ConfluxConfig();
            config.predictionDebounceMs = 100;
            final PredictionTileService predictions = new PredictionTileService(
                sessions,
                new PredictionState(),
                executors,
                new TileService(new MapWorldService(), executors, config, new DaylightModel())
            );
            final CorrectionStore corrections = new CorrectionStore(tempDir);
            corrections.onSessionChanged(sessions.current());
            predictions.bindCorrectionStore(corrections);
            final CompanionSession companion = new CompanionSession();
            MapSyncTestCompanion.activate(companion, new HelloPolicyS2C(
                new HelloPolicyS2C.Flags(false, true, false, false, false, true, true),
                "chunk-viewport-world",
                "1.17",
                new HelloPolicyS2C.Budgets(256 * 1024, 8, 0, 4),
                List.of(new HelloPolicyS2C.DimDescriptor(
                    dimension.toString(), "overworld", true, false, 0L, WorldPreset.DEFAULT
                ))
            ));
            final List<Message> sent = new ArrayList<>();
            final long[] now = {1_000L};
            final MapSyncClient client = new MapSyncClient(
                companion,
                message -> {
                    sent.add(message);
                    return 1;
                },
                corrections,
                predictions,
                config,
                () -> now[0]
            );
            final ChunkViewport chunks = new ChunkViewport(15, 16, 3, 4);

            client.reportViewport(dimension, 4, 0, 0, 0, 0, chunks);
            now[0] += 150L;
            client.reportViewport(dimension, 4, 0, 0, 0, 0, chunks);

            final MapRegionSyncSubscribeC2S subscription = assertInstanceOf(
                MapRegionSyncSubscribeC2S.class, sent.get(0)
            );
            assertEquals(15, subscription.minChunkX());
            assertEquals(16, subscription.maxChunkX());
            final MapRegionViewReqC2S request = assertInstanceOf(
                MapRegionViewReqC2S.class, sent.get(1)
            );
            assertEquals(2, request.regions().size());
            assertEquals(15, request.regions().get(0).minLocalChunkX());
            assertEquals(15, request.regions().get(0).maxLocalChunkX());
            assertEquals(0, request.regions().get(1).minLocalChunkX());
            assertEquals(0, request.regions().get(1).maxLocalChunkX());
            assertEquals(MapSyncProgress.State.SYNCING, client.status().state());
            assertEquals(2, client.status().totalTiles());

            for (int i = 0; i < request.regions().size(); i++) {
                final MapRegionViewReqC2S.RegionReq region = request.regions().get(i);
                final byte[] body;
                final long revision;
                final int mode;
                if (i == 0) {
                    final byte[] generated = {3};
                    final byte[] evaluated = {3};
                    final ChunkPatchCodec.Patch page = new ChunkPatchCodec.Patch(
                        1, 2, 1, generated, evaluated,
                        List.of(new PatchCodec.Sample(0, 1, 88, 1, 1, 0))
                    );
                    body = ChunkPatchCodec.encode(page);
                    revision = ChunkPatchCodec.regionRevision(
                        request.lod(), region.slice(), page,
                        companion.mapSyncCorrectionProfile()
                    );
                    mode = Proto.PATCH_MODE_ABSOLUTE;
                } else {
                    body = new byte[0];
                    revision = 0L;
                    mode = Proto.PATCH_MODE_UNAVAILABLE;
                }
                client.onRegionPatch(new MapRegionPatchS2C(
                    request.reqId(), request.dimIndex(), request.lod(),
                    region.regionX(), region.regionZ(),
                    region.minLocalChunkX(), region.minLocalChunkZ(),
                    region.maxLocalChunkX(), region.maxLocalChunkZ(),
                    mode, revision, body
                ), 5);
            }
            assertEquals(MapSyncProgress.State.COMPLETED, client.status().state());
            assertEquals(2, client.status().completedTiles());
            assertEquals(88, corrections.get(new CorrectionStore.Key(
                dimension.toString(), 4, 0, 0
            )).sampleAt(3 * 256 + 15).surfaceY());
        } finally {
            executors.shutdown(2_000L);
        }
    }


    @Test
    void chunkCapableCompanionDoesNotRequestPlayerViewDistance(
        @TempDir final Path tempDir
    ) {
        final DimensionId dimension = DimensionId.OVERWORLD;
        final SessionGuard sessions = new SessionGuard();
        sessions.begin(WorldIdentity.singleplayer("excluded-player-view"), dimension);
        final MapExecutors executors = new MapExecutors();
        try {
            final ConfluxConfig config = new ConfluxConfig();
            config.predictionDebounceMs = 100;
            final PredictionTileService predictions = new PredictionTileService(
                sessions,
                new PredictionState(),
                executors,
                new TileService(new MapWorldService(), executors, config, new DaylightModel())
            );
            final CorrectionStore corrections = new CorrectionStore(tempDir);
            corrections.onSessionChanged(sessions.current());
            predictions.bindCorrectionStore(corrections);
            final CompanionSession companion = new CompanionSession();
            MapSyncTestCompanion.activate(companion, new HelloPolicyS2C(
                new HelloPolicyS2C.Flags(false, true, false, false, false, true, true),
                "excluded-player-view-world",
                "1.17",
                new HelloPolicyS2C.Budgets(256 * 1024, 8, 0, 4),
                List.of(new HelloPolicyS2C.DimDescriptor(
                    dimension.toString(), "overworld", true, false, 0L, WorldPreset.DEFAULT
                ))
            ));
            final List<Message> sent = new ArrayList<>();
            final long[] now = {1_000L};
            final MapSyncClient client = new MapSyncClient(
                companion,
                message -> {
                    sent.add(message);
                    return 1;
                },
                corrections,
                predictions,
                config,
                () -> now[0]
            );
            final ChunkViewport chunks = new ChunkViewport(14, 18, 2, 6);
            final ChunkViewport playerView = new ChunkViewport(15, 17, 3, 5);

            client.reportViewport(dimension, 0, 0, 1, 0, 0, chunks, playerView);
            now[0] += 150L;
            client.reportViewport(dimension, 0, 0, 1, 0, 0, chunks, playerView);

            final MapRegionViewReqC2S request = assertInstanceOf(
                MapRegionViewReqC2S.class, sent.get(1)
            );
            assertEquals(
                16L,
                request.regions().stream().mapToLong(region -> region.slice().chunkCount()).sum()
            );
        } finally {
            executors.shutdown(2_000L);
        }
    }
}
