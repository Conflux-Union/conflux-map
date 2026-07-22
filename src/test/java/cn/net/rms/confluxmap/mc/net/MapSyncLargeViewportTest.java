package cn.net.rms.confluxmap.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.predict.CorrectionStore;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.server.PatchDispatcher;
import cn.net.rms.confluxmap.server.PlayerBudget;
import cn.net.rms.confluxmap.server.ServerConfig;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic end-to-end simulation of the correction sync loop: the real {@link MapSyncClient}
 * (simulated clock, in-memory wire) against a server model that mirrors
 * {@code RegionSummaryService.request}'s budget control flow with the real {@link PlayerBudget}.
 *
 * <p>The large-viewport scenario reproduces the field report "large-area sync misses many
 * chunks": with default budgets and realistically sized absolute patches, every tile the server
 * drops (rate-limit or bandwidth break) must still be requested again and served eventually.
 */
class MapSyncLargeViewportTest {
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("map-sync-large-viewport-test");
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final int LOD = 0;
    private static final int VIEW_MIN = 0;
    private static final int VIEW_MAX = 7;
    private static final int VIEW_TILES = (VIEW_MAX - VIEW_MIN + 1) * (VIEW_MAX - VIEW_MIN + 1);
    private static final long FRAME_MS = 50L;
    private static final long SIM_START_MS = 1_000_000L;

    @Test
    void largeViewportEventuallySyncsEveryTileUnderDefaultBudgets(@TempDir final Path tempDir) throws Exception {
        // 90 simulated seconds is ~3x the bandwidth floor for 64 tiles of ~25 KiB at 64 KiB/s.
        final Fixture fixture = new Fixture(tempDir, 6_000);
        try {
            fixture.run(90_000L);
            assertTrue(
                fixture.server.minAbsolutePatchBytes >= 15_000,
                "scenario premise broken: absolute patches compressed below 15 KiB ("
                    + fixture.server.minAbsolutePatchBytes + ")"
            );
            assertEquals(
                VIEW_TILES, fixture.server.served.size(),
                "tiles never served: " + missing(fixture.server.served)
                    + " (requests=" + fixture.server.requestCount
                    + " errors=" + fixture.server.errorCount + ")"
            );
            assertEquals(
                0, fixture.server.errorCount,
                "queued delivery must absorb budget pressure without rate-limit errors"
            );
        } finally {
            fixture.shutdown();
        }
    }

    @Test
    void smallPatchesSyncTheWholeViewportQuickly(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = new Fixture(tempDir, 16);
        try {
            fixture.run(10_000L);
            assertEquals(
                VIEW_TILES, fixture.server.served.size(),
                "harness sanity: tiny patches must sync the full viewport, missing "
                    + missing(fixture.server.served)
            );
        } finally {
            fixture.shutdown();
        }
    }

    private static String missing(final Set<Long> served) {
        final List<String> result = new ArrayList<>();
        for (int z = VIEW_MIN; z <= VIEW_MAX; z++) {
            for (int x = VIEW_MIN; x <= VIEW_MAX; x++) {
                if (!served.contains(tileKey(x, z))) {
                    result.add(x + "," + z);
                }
            }
        }
        return result.size() + " of " + VIEW_TILES + " " + result;
    }

    private static long tileKey(final int x, final int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static final class Fixture {
        final FakeCompanionServer server;
        final MapSyncClient client;
        final MapExecutors executors;
        final Deque<byte[]> wire = new ArrayDeque<>();
        long nowMs = SIM_START_MS;
        final long nanoOrigin = System.nanoTime();

        Fixture(final Path tempDir, final int samplesPerAbsolutePatch) {
            final ServerConfig serverConfig = new ServerConfig();
            server = new FakeCompanionServer(serverConfig, samplesPerAbsolutePatch);

            final SessionGuard sessionGuard = new SessionGuard();
            sessionGuard.begin(WORLD, DIM);
            executors = new MapExecutors();
            final TileService uploads =
                new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
            final PredictionTileService predictionTiles =
                new PredictionTileService(sessionGuard, new PredictionState(), executors, uploads);
            final CorrectionStore corrections = new CorrectionStore(tempDir);
            corrections.onSessionChanged(sessionGuard.current());
            predictionTiles.bindCorrectionStore(corrections);

            final CompanionSession session = new CompanionSession();
            session.onPolicy(new HelloPolicyS2C(
                new HelloPolicyS2C.Flags(false, true, false),
                "11111111-2222-3333-4444-555555555555",
                "1.17",
                new HelloPolicyS2C.Budgets(
                    serverConfig.maxBytesPerSecondPerPlayer,
                    serverConfig.maxTilesPerRequest,
                    serverConfig.minRequestIntervalMs,
                    serverConfig.maxPatchLod
                ),
                List.of(new HelloPolicyS2C.DimDescriptor(DIM.toString(), "overworld", true, false, 0L, WorldPreset.DEFAULT))
            ));

            client = new MapSyncClient(
                session,
                message -> {
                    try {
                        final byte[] payload = MsgCodec.encode(message);
                        wire.add(payload);
                        return payload.length;
                    } catch (final ProtoException e) {
                        throw new AssertionError("client produced an unencodable message", e);
                    }
                },
                corrections,
                predictionTiles,
                new ConfluxConfig(),
                () -> nowMs
            );
        }

        void run(final long durationMs) throws ProtoException {
            for (long t = 0; t <= durationMs; t += FRAME_MS) {
                nowMs = SIM_START_MS + t;
                server.tickDrain(nanos(), client);
                client.reportViewport(DIM, LOD, VIEW_MIN, VIEW_MAX, VIEW_MIN, VIEW_MAX);
                while (!wire.isEmpty()) {
                    final MapViewReqC2S request = (MapViewReqC2S) MsgCodec.decode(wire.poll());
                    server.handle(request, nanos(), client);
                }
            }
        }

        long nanos() {
            return nanoOrigin + (nowMs - SIM_START_MS) * 1_000_000L;
        }

        void shutdown() {
            executors.shutdown(2000);
        }
    }

    /**
     * Mirrors {@code RegionSummaryService.request}'s admission control while delegating queueing
     * and paced delivery to the real {@link PatchDispatcher}; only patch building is synthetic.
     */
    private static final class FakeCompanionServer {
        final ServerConfig config;
        final PatchDispatcher dispatcher;
        final int samplesPerAbsolutePatch;
        final Set<Long> served = new HashSet<>();
        int requestCount;
        int errorCount;
        int minAbsolutePatchBytes = Integer.MAX_VALUE;

        FakeCompanionServer(final ServerConfig config, final int samplesPerAbsolutePatch) {
            this.config = config;
            this.dispatcher = new PatchDispatcher(
                new PlayerBudget(config.maxBytesPerSecondPerPlayer, config.minRequestIntervalMs),
                config.maxPendingTilesPerPlayer
            );
            this.samplesPerAbsolutePatch = samplesPerAbsolutePatch;
        }

        void handle(final MapViewReqC2S request, final long nowNanos, final MapSyncClient client) throws ProtoException {
            requestCount++;
            if (request.lod() > config.maxPatchLod || request.tiles().size() > config.maxTilesPerRequest
                || request.dimIndex() < 0 || !dispatcher.budget().beginRequest(nowNanos)) {
                deliverError(client);
                return;
            }
            final List<PatchDispatcher.TileJob> jobs = new ArrayList<>(request.tiles().size());
            for (final MapViewReqC2S.TileReq tile : request.tiles()) {
                jobs.add(new PatchDispatcher.TileJob(
                    request.reqId(), request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(), tile.sinceRevision()
                ));
            }
            if (dispatcher.submit(jobs) > 0) {
                deliverError(client);
            }
            tickDrain(nowNanos, client);
        }

        void tickDrain(final long nowNanos, final MapSyncClient client) {
            dispatcher.drain(nowNanos, this::buildPatch, message -> deliver((MapPatchS2C) message, client));
        }

        private void deliver(final MapPatchS2C patch, final MapSyncClient client) {
            try {
                final byte[] encoded = MsgCodec.encode(patch);
                if (patch.mode() == Proto.PATCH_MODE_ABSOLUTE) {
                    minAbsolutePatchBytes = Math.min(minAbsolutePatchBytes, encoded.length);
                }
                served.add(tileKey(patch.tileX(), patch.tileZ()));
                client.onPatch((MapPatchS2C) MsgCodec.decode(encoded), encoded.length);
            } catch (final ProtoException e) {
                throw new AssertionError("server produced an unencodable patch", e);
            }
        }

        private void deliverError(final MapSyncClient client) throws ProtoException {
            errorCount++;
            final byte[] payload = MsgCodec.encode(new ErrorS2C(ErrorS2C.ERR_RATE_LIMITED, "rate limited"));
            client.onError(payload.length);
        }

        private MapPatchS2C buildPatch(final PatchDispatcher.TileJob job) {
            final byte[] presence = new byte[Proto.PATCH_PRESENCE_BYTES];
            java.util.Arrays.fill(presence, (byte) 0xFF);
            if (job.sinceRevision() >= 1L) {
                return new MapPatchS2C(
                    job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
                    Proto.PATCH_MODE_UNCHANGED, 1L, presence, new byte[0]
                );
            }
            final List<PatchCodec.Sample> samples = new ArrayList<>(samplesPerAbsolutePatch);
            for (int i = 0; i < samplesPerAbsolutePatch; i++) {
                final long h = mix((((long) job.tileX() << 20) ^ job.tileZ()) * 65_536L + i);
                samples.add(new PatchCodec.Sample(
                    i,
                    (int) (h & 0xFF),
                    40 + (int) ((h >>> 16) & 0x7F),
                    SurfaceKind.LAND.ordinal(),
                    (int) ((h >>> 24) & 0x3F),
                    (int) ((h >>> 32) & 0x0F)
                ));
            }
            return new MapPatchS2C(
                job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
                Proto.PATCH_MODE_ABSOLUTE, 1L, presence, PatchCodec.encode(new PatchCodec.Patch(samples))
            );
        }

        private static long mix(long x) {
            x += 0x9E3779B97F4A7C15L;
            x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
            x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
            return x ^ (x >>> 31);
        }
    }
}
