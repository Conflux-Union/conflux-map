package cn.net.rms.confluxmap.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapInvalidateS2C;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapSyncProgress;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.predict.CorrectionStore;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapSyncInvalidationProgressTest {
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("map-sync-invalidation-progress-test");

    @Test
    void viewportInvalidationDoesNotRestartVisibleBatch(@TempDir final Path tempDir) {
        final long[] nowMillis = {10_000L};
        final SessionGuard sessions = new SessionGuard();
        sessions.begin(WORLD, DIM);
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionTileService predictions = new PredictionTileService(
            sessions, new PredictionState(), executors, uploads
        );
        final CorrectionStore corrections = new CorrectionStore(tempDir);
        corrections.onSessionChanged(sessions.current());
        predictions.bindCorrectionStore(corrections);

        final CompanionSession companion = new CompanionSession();
        companion.onPolicy(new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, true, false, false, false, true),
            "11111111-2222-3333-4444-555555555555",
            "1.17",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, Proto.DEFAULT_MAX_PATCH_LOD),
            List.of(new HelloPolicyS2C.DimDescriptor(
                DIM.toString(), "overworld", true, false, 0L, WorldPreset.DEFAULT
            ))
        ));
        final List<Message> sent = new ArrayList<>();
        final MapSyncClient client = new MapSyncClient(
            companion,
            message -> {
                sent.add(message);
                return 32;
            },
            corrections,
            predictions,
            new ConfluxConfig(),
            () -> nowMillis[0]
        );

        try {
            client.reportViewport(DIM, 0, 0, 3, 0, 2);
            nowMillis[0] += 400L;
            client.reportViewport(DIM, 0, 0, 3, 0, 2);

            final MapViewReqC2S firstRequest = requests(sent).get(0);
            assertProgress(client, 0, 12);

            final MapViewReqC2S.TileReq firstTile = firstRequest.tiles().get(0);
            client.onPatch(new MapPatchS2C(
                firstRequest.reqId(), 0, 0, firstTile.tileX(), firstTile.tileZ(),
                Proto.PATCH_MODE_UNAVAILABLE, 0L,
                new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0]
            ), 40);
            assertProgress(client, 1, 12);

            client.onInvalidation(new MapInvalidateS2C(
                0, 0, List.of(new MapInvalidateS2C.Tile(firstTile.tileX(), firstTile.tileZ()))
            ));
            nowMillis[0] += 400L;
            client.reportViewport(DIM, 0, 0, 3, 0, 2);

            assertProgress(client, 1, 12);
            final List<MapViewReqC2S> requests = requests(sent);
            assertEquals(2, requests.size(), "the invalidated tile must trigger a refresh request");
            assertTrue(
                requests.get(1).tiles().stream().anyMatch(
                    tile -> tile.tileX() == firstTile.tileX() && tile.tileZ() == firstTile.tileZ()
                ),
                "the refresh request must include the invalidated tile"
            );
        } finally {
            executors.shutdown(2000);
        }
    }

    private static List<MapViewReqC2S> requests(final List<Message> messages) {
        return messages.stream()
            .filter(MapViewReqC2S.class::isInstance)
            .map(MapViewReqC2S.class::cast)
            .toList();
    }

    private static void assertProgress(
        final MapSyncClient client, final int completedTiles, final int totalTiles
    ) {
        final MapSyncProgress.Snapshot status = client.status();
        assertEquals(MapSyncProgress.State.SYNCING, status.state());
        assertEquals(completedTiles, status.completedTiles());
        assertEquals(totalTiles, status.totalTiles());
    }
}
