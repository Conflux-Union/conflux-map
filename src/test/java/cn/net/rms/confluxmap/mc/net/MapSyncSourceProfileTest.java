package cn.net.rms.confluxmap.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapCompatibilityS2C;
import cn.net.rms.confluxmap.core.net.MapSyncCompatibility;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.PatchCodec;
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

class MapSyncSourceProfileTest {
    @Test
    void absoluteSessionDoesNotReuseAResidualCacheRevision(@TempDir final Path tempDir) {
        final DimensionId dimension = DimensionId.OVERWORLD;
        final SessionGuard sessions = new SessionGuard();
        sessions.begin(WorldIdentity.singleplayer("source-profile"), dimension);
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
            corrections.apply(
                new CorrectionStore.Key(dimension.toString(), 0, 0, 0),
                42L,
                new byte[Proto.PATCH_PRESENCE_BYTES],
                new PatchCodec.Patch(List.of()),
                Proto.PATCH_MODE_RESIDUAL,
                MapSyncCompatibility.STABLE_PREDICTOR,
                1_000L
            );

            final CompanionSession companion = new CompanionSession();
            companion.onHelloSent();
            companion.onSelection(new MapCompatibilityS2C(
                MapSyncCompatibility.NEGOTIATION_VERSION,
                "future-server",
                Proto.PROTO_MAJOR,
                Proto.PROTO_MINOR,
                PatchCodec.FORMAT_VERSION,
                ChunkPatchCodec.FORMAT_VERSION,
                "cb:future|shim:10|base:15",
                MapCompatibilityS2C.MODE_ABSOLUTE,
                MapCompatibilityS2C.REASON_BASELINE_MISMATCH
            ));
            companion.onPolicy(new HelloPolicyS2C(
                new HelloPolicyS2C.Flags(false, true, false, false),
                "source-profile-world",
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

            client.reportViewport(dimension, 0, 0, 0, 0, 0);
            now[0] += 150L;
            client.reportViewport(dimension, 0, 0, 0, 0, 0);

            final MapViewReqC2S request = assertInstanceOf(MapViewReqC2S.class, sent.get(0));
            assertEquals(Long.MIN_VALUE, request.tiles().get(0).sinceRevision());
        } finally {
            executors.shutdown(2_000L);
        }
    }
}
