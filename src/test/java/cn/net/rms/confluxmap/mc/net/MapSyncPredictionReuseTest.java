package cn.net.rms.confluxmap.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.predict.CorrectionStore;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapSyncPredictionReuseTest {
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("map-sync-prediction-reuse-test");

    @Test
    void freshLodZeroCoverageIsRecheckedOnlyAfterAnExpiredViewportReentry(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        final AtomicLong nowMillis = new AtomicLong(10_000L);
        final SessionGuard sessions = new SessionGuard();
        sessions.begin(WORLD, DIM);
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictions = new PredictionTileService(
            sessions, state, executors, uploads, nowMillis::get
        );
        final CorrectionStore corrections = new CorrectionStore(tempDir);
        corrections.onSessionChanged(sessions.current());
        predictions.bindCorrectionStore(corrections);

        final CompanionSession companion = new CompanionSession();
        MapSyncTestCompanion.activate(companion, new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, true, false),
            "11111111-2222-3333-4444-555555555555",
            "1.17",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, Proto.DEFAULT_MAX_PATCH_LOD),
            List.of(new HelloPolicyS2C.DimDescriptor(
                DIM.toString(), "overworld", true, false, 0L, WorldPreset.FLAT
            ))
        ));
        final List<MapViewReqC2S> sent = new ArrayList<>();
        final MapSyncClient client = new MapSyncClient(
            companion,
            message -> {
                sent.add((MapViewReqC2S) message);
                return 32;
            },
            corrections,
            predictions,
            new ConfluxConfig(),
            nowMillis::get
        );

        try {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    assertTrue(predictions.applyCorrection(
                        new CorrectionStore.Key(DIM.toString(), 0, x, z),
                        1L,
                        new byte[Proto.PATCH_PRESENCE_BYTES],
                        new PatchCodec.Patch(List.of()),
                        nowMillis.get()
                    ));
                }
            }
            awaitLowerCoverage(predictions, nowMillis.get(), PredictionTileService.LowerCoverageState.READY);

            client.reportViewport(DIM, 1, 0, 0, 0, 0);
            nowMillis.addAndGet(400L);
            client.reportViewport(DIM, 1, 0, 0, 0, 0);
            assertTrue(sent.isEmpty(), "fresh lower-LOD coverage must avoid a server request");

            nowMillis.addAndGet(5_001L);
            client.clearViewport();
            client.reportViewport(DIM, 1, 0, 0, 0, 0);
            awaitLowerCoverage(predictions, nowMillis.get(), PredictionTileService.LowerCoverageState.READY);
            nowMillis.addAndGet(400L);
            client.reportViewport(DIM, 1, 0, 0, 0, 0);
            assertTrue(sent.isEmpty(), "a short revisit must keep using the completed cache");

            nowMillis.addAndGet(PredictionTileService.CORRECTION_REUSE_TTL_MS);
            client.reportViewport(DIM, 1, 0, 0, 0, 0);
            assertTrue(sent.isEmpty(), "expiry must not start polling an unchanged viewport");

            client.clearViewport();
            client.reportViewport(DIM, 1, 0, 0, 0, 0);
            awaitLowerCoverage(
                predictions, nowMillis.get(), PredictionTileService.LowerCoverageState.MISSING_OR_STALE
            );
            nowMillis.addAndGet(400L);
            awaitRequest(client, sent);
            assertEquals(1, sent.size(), "expired lower-LOD validation must make the parent plannable again");
            assertEquals(1, sent.get(0).lod());
            assertEquals(
                List.of(new MapViewReqC2S.TileReq(0, 0, Long.MIN_VALUE)),
                sent.get(0).tiles()
            );
        } finally {
            executors.shutdown(2000);
        }
    }

    /**
     * Waits for the exact lower-LOD coverage state used by the next viewport poll. Queue idleness
     * alone is only an implementation detail; this test must synchronize on the observable
     * READY/MISSING decision that MapSyncClient consumes.
     */
    private static void awaitLowerCoverage(
        final PredictionTileService service,
        final long nowMillis,
        final PredictionTileService.LowerCoverageState expected
    ) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5_000L;
        PredictionTileService.LowerCoverageState actual;
        do {
            actual = service.prepareFreshLowerCoverage(DIM, 1, 0, 0, nowMillis);
            if (actual == expected) {
                return;
            }
            Thread.sleep(10L);
        } while (System.currentTimeMillis() < deadline);
        assertEquals(expected, actual, "lower-LOD coverage did not reach the expected terminal state");
    }

    private static void awaitRequest(
        final MapSyncClient client,
        final List<MapViewReqC2S> sent
    ) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (sent.isEmpty() && System.currentTimeMillis() < deadline) {
            client.reportViewport(DIM, 1, 0, 0, 0, 0);
            Thread.sleep(10L);
        }
    }
}
