package cn.net.rms.confluxmap.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.CorrectionStore;
import cn.net.rms.confluxmap.core.predict.CorrectionTile;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.server.PatchDispatcher;
import cn.net.rms.confluxmap.server.PlayerBudget;
import cn.net.rms.confluxmap.server.ServerConfig;
import cn.net.rms.confluxmap.server.TilePresence;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A progressive coarse scan must expose its growing presence bitmap without advancing the
 * committed correction watermark, and the client must keep retrying at the normal request pace.
 *
 * <p>The client here is the real {@link MapSyncClient} browsing at LOD 3, served by a server that
 * mirrors {@code RegionSummaryService}'s admission control and progressive answer over the real
 * {@link PatchDispatcher} and {@link TilePresence}.
 */
class MapSyncCoarseLodPresenceTest {
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("map-sync-coarse-lod-presence-test");
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final long FRAME_MS = 50L;
    private static final long SIM_START_MS = 1_000_000L;

    /** First LOD served by the progressive path. */
    private static final int COARSE_LOD = 3;
    /** Regions 0..3 on both axes are generated, i.e. blocks 0..1023 of tile 0,0. */
    private static final int GENERATED_REGIONS = 4;

    @Test
    void coarseTilesStillReceiveTheGeneratedChunkBitmap() throws Exception {
        final Fixture fixture = new Fixture(new ServerConfig());
        try {
            fixture.browse(COARSE_LOD, 30_000L);

            final CorrectionTile tile = fixture.corrections.get(DIM, COARSE_LOD, 0, 0);
            assertEquals(0, fixture.server.errorCount, "a presence-capable server must not reject the coarse LOD");
            // A LOD-3 cell spans 8 chunks (128 blocks), so 4 generated regions fill cells 0..7.
            for (int cellZ = 0; cellZ < 16; cellZ++) {
                for (int cellX = 0; cellX < 16; cellX++) {
                    final boolean expected = cellX < 8 && cellZ < 8;
                    assertEquals(
                        expected, tile.hasGeneratedChunk(cellX, cellZ),
                        "cell " + cellX + "," + cellZ + " presence"
                    );
                }
            }
        } finally {
            fixture.shutdown();
        }
    }

    @Test
    void generatedOnlyModeShowsTheExploredHalfAndHidesTheRest() throws Exception {
        final Fixture fixture = new Fixture(new ServerConfig());
        try {
            fixture.browse(COARSE_LOD, 30_000L);
            final CorrectionTile tile = fixture.corrections.get(DIM, COARSE_LOD, 0, 0);

            // Cells 0..7 cover output pixels 0..127 on each axis.
            assertTrue(
                PredictionViewMode.GENERATED_ONLY.showsPredictedPixels(tile, pixel(0, 0), COARSE_LOD),
                "a pixel over generated chunks must render"
            );
            assertTrue(
                PredictionViewMode.GENERATED_ONLY.showsPredictedPixels(tile, pixel(127, 127), COARSE_LOD),
                "the last generated pixel must render"
            );
            assertFalse(
                PredictionViewMode.GENERATED_ONLY.showsPredictedPixels(tile, pixel(128, 128), COARSE_LOD),
                "a pixel past the generated area must stay hidden"
            );
            assertFalse(
                PredictionViewMode.GENERATED_ONLY.showsPredictedPixels(tile, pixel(255, 255), COARSE_LOD),
                "the far corner must stay hidden"
            );
        } finally {
            fixture.shutdown();
        }
    }

    /**
     * A presence answer must not claim a correction watermark the client never received, or a
     * later full request for the same tile would be answered as "unchanged" and never deliver
     * the columns.
     */
    @Test
    void presenceAnswersLeaveTheCorrectionWatermarkAtZero() throws Exception {
        final Fixture fixture = new Fixture(new ServerConfig());
        try {
            fixture.browse(COARSE_LOD, 30_000L);

            assertFalse(fixture.server.sinceRevisions.isEmpty(), "the coarse LOD must actually have been requested");
            assertTrue(fixture.server.sinceRevisions.size() > 3, "partial patches must stay immediately retryable");
            assertEquals(0L, fixture.corrections.get(DIM, COARSE_LOD, 0, 0).revision());
            assertTrue(fixture.server.sinceRevisions.stream().allMatch(r -> r == 0L),
                "the client must keep asking from revision 0: " + fixture.server.sinceRevisions);
        } finally {
            fixture.shutdown();
        }
    }

    @Test
    void coarseLodIsAcceptedWithoutAnOperatorCeiling() throws Exception {
        final ServerConfig config = new ServerConfig();
        final Fixture fixture = new Fixture(config);
        try {
            fixture.browse(COARSE_LOD, 10_000L);

            assertEquals(0, fixture.server.errorCount);
            assertTrue(setCells(fixture.corrections.get(DIM, COARSE_LOD, 0, 0).presence()) > 0);
        } finally {
            fixture.shutdown();
        }
    }

    @Test
    void completedCoarseTileIsPolledAgainForVisibleSourceChanges() throws Exception {
        final Fixture fixture = new Fixture(new ServerConfig(), false);
        try {
            fixture.browse(COARSE_LOD, 12_000L);

            assertTrue(
                fixture.server.sinceRevisions.size() >= 2,
                "a completed visible coarse tile must not retain the ordinary ten-minute cooldown"
            );
            assertTrue(
                fixture.server.sinceRevisions.stream().skip(1).anyMatch(revision -> revision == 1L),
                "follow-up request must carry the committed revision"
            );
        } finally {
            fixture.shutdown();
        }
    }

    private static int pixel(final int pixelX, final int pixelZ) {
        return pixelZ * 256 + pixelX;
    }

    private static int setCells(final byte[] bits) {
        int count = 0;
        for (final byte b : bits) {
            count += Integer.bitCount(b & 0xFF);
        }
        return count;
    }

    private static final class Fixture {
        final FakeCompanionServer server;
        final MapSyncClient client;
        final CorrectionStore corrections;
        final MapExecutors executors;
        final Deque<byte[]> wire = new ArrayDeque<>();
        long nowMs = SIM_START_MS;
        final long nanoOrigin = System.nanoTime();

        Fixture(final ServerConfig config) {
            this(config, true);
        }

        Fixture(final ServerConfig config, final boolean progressive) {
            server = new FakeCompanionServer(config, progressive);

            final SessionGuard sessionGuard = new SessionGuard();
            sessionGuard.begin(WORLD, DIM);
            executors = new MapExecutors();
            final TileService uploads =
                new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
            final PredictionTileService predictionTiles =
                new PredictionTileService(sessionGuard, new PredictionState(), executors, uploads);
            corrections = new CorrectionStore(tempRoot());
            corrections.onSessionChanged(sessionGuard.current());
            predictionTiles.bindCorrectionStore(corrections);

            final CompanionSession session = new CompanionSession();
            session.onPolicy(new HelloPolicyS2C(
                new HelloPolicyS2C.Flags(false, true, false),
                "11111111-2222-3333-4444-555555555555",
                "1.17",
                new HelloPolicyS2C.Budgets(
                    config.maxBytesPerSecondPerPlayer,
                    config.maxTilesPerRequest,
                    config.minRequestIntervalMs,
                    Proto.DEFAULT_MAX_PATCH_LOD
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

        /** The store only persists on flush; a per-instance temp dir keeps the cases independent. */
        private static Path tempRoot() {
            try {
                return java.nio.file.Files.createTempDirectory("conflux-coarse-presence");
            } catch (final java.io.IOException e) {
                throw new AssertionError("could not create a temp correction root", e);
            }
        }

        void browse(final int lod, final long durationMs) throws ProtoException {
            final long start = nowMs - SIM_START_MS;
            for (long t = start; t <= start + durationMs; t += FRAME_MS) {
                nowMs = SIM_START_MS + t;
                server.tickDrain(nanos(), client);
                client.reportViewport(DIM, lod, 0, 0, 0, 0);
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
     * Mirrors {@code RegionSummaryService}'s admission control and a revision-0 progressive answer
     * built by the real {@link TilePresence} over the currently scanned region summaries.
     */
    private static final class FakeCompanionServer {
        final ServerConfig config;
        final PatchDispatcher dispatcher;
        final List<Long> sinceRevisions = new ArrayList<>();
        final boolean progressive;
        int errorCount;

        FakeCompanionServer(final ServerConfig config, final boolean progressive) {
            this.config = config;
            this.progressive = progressive;
            this.dispatcher = new PatchDispatcher(
                new PlayerBudget(config.maxBytesPerSecondPerPlayer, config.minRequestIntervalMs),
                config.maxPendingTilesPerPlayer
            );
        }

        void handle(final MapViewReqC2S request, final long nowNanos, final MapSyncClient client) throws ProtoException {
            if (request.lod() > 4
                || request.tiles().size() > config.maxTilesPerRequest
                || request.dimIndex() < 0 || !dispatcher.budget().beginRequest(nowNanos)) {
                deliverError(client);
                return;
            }
            final List<PatchDispatcher.TileJob> jobs = new ArrayList<>(request.tiles().size());
            for (final MapViewReqC2S.TileReq tile : request.tiles()) {
                sinceRevisions.add(tile.sinceRevision());
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
            final byte[] presence = TilePresence.build(
                job.lod(), job.tileX(), job.tileZ(), FakeCompanionServer::cachedRegionFlags
            );
            return new MapPatchS2C(job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
                progressive ? Proto.PATCH_MODE_PARTIAL : Proto.PATCH_MODE_UNCHANGED,
                progressive ? 0L : 1L,
                presence,
                progressive ? PatchCodec.encode(List.of()) : new byte[0]
            );
        }

        /** World model: every chunk of regions 0..3 on both axes is generated; nothing else exists. */
        private static boolean[] cachedRegionFlags(final int regionX, final int regionZ) {
            if (regionX < 0 || regionX >= GENERATED_REGIONS || regionZ < 0 || regionZ >= GENERATED_REGIONS) {
                return null;
            }
            final boolean[] flags = new boolean[SummaryCodec.CHUNKS];
            java.util.Arrays.fill(flags, true);
            return flags;
        }
    }
}
