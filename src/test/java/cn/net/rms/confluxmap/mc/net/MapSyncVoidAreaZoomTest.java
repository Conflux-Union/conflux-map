package cn.net.rms.confluxmap.mc.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.BaselineGrid;
import cn.net.rms.confluxmap.core.predict.CorrectionStore;
import cn.net.rms.confluxmap.core.predict.CorrectionTile;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.server.PatchBuilder;
import cn.net.rms.confluxmap.server.PatchDispatcher;
import cn.net.rms.confluxmap.server.PlayerBudget;
import cn.net.rms.confluxmap.server.ServerConfig;
import cn.net.rms.confluxmap.server.SummaryTile;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end simulation of the field report "a 1400x1400 emptied (bedrock-floor) area syncs
 * completely at zoom level 2 but leaks predicted terrain at zoom level 1": the real
 * {@link MapSyncClient} browses the same world area at LOD 2 first and then at LOD 1, against a
 * server built from the real {@link PatchBuilder}/{@link PatchDispatcher} over a synthetic world
 * whose baseline prediction matches the terrain everywhere except the void rectangle.
 *
 * <p>After each phase every void-covered pixel of every viewport tile at that LOD must hold a
 * correction sample; a missing sample is exactly the user-visible symptom (the predicted
 * underlay shows normal terrain where the world has only a bedrock floor).
 */
class MapSyncVoidAreaZoomTest {
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("map-sync-void-area-zoom-test");
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final long FRAME_MS = 50L;
    private static final long SIM_START_MS = 1_000_000L;

    private static final int VOID_MIN_BLOCK = 0;
    private static final int VOID_SIZE_BLOCKS = 1400;
    private static final int VOID_MAX_BLOCK = VOID_MIN_BLOCK + VOID_SIZE_BLOCKS;
    private static final int CENTER_BLOCK = VOID_MIN_BLOCK + VOID_SIZE_BLOCKS / 2;
    private static final int SCREEN_W = 1920;
    private static final int SCREEN_H = 1080;

    private static final int BIOME_PLAINS = 1;
    private static final int TERRAIN_Y = 68;

    @Test
    void voidAreaStaysCorrectedWhenZoomingFromLod2ToLod1(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        try {
            fixture.browse(2, 60_000L);
            assertEquals(
                0, fixture.missingVoidPixels(2),
                "premise broken: zoom level 2 must sync the void completely"
            );
            fixture.browse(1, 120_000L);
            assertEquals(
                0, fixture.missingVoidPixels(1),
                "zoom level 1 leaked predicted terrain inside the void"
            );
        } finally {
            fixture.shutdown();
        }
    }

    @Test
    void voidAreaSyncsAtLod1FromScratch(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = new Fixture(tempDir);
        try {
            fixture.browse(1, 120_000L);
            assertEquals(
                0, fixture.missingVoidPixels(1),
                "zoom level 1 alone leaked predicted terrain inside the void"
            );
        } finally {
            fixture.shutdown();
        }
    }

    /** World model: flat plains terrain everywhere; a bedrock-noise floor inside the void rect. */
    private static boolean inVoid(final long blockX, final long blockZ) {
        return blockX >= VOID_MIN_BLOCK && blockX < VOID_MAX_BLOCK && blockZ >= VOID_MIN_BLOCK && blockZ < VOID_MAX_BLOCK;
    }

    private static int bedrockY(final long blockX, final long blockZ) {
        return 1 + (int) (mix(blockX * 341_873_128_712L + blockZ * 132_897_987_541L) & 3L);
    }

    private static long mix(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    private static final class Viewport {
        final int lod;
        final int minX;
        final int maxX;
        final int minZ;
        final int maxZ;

        Viewport(final int lod) {
            this.lod = lod;
            final double scale = 1 << lod;
            this.minX = TileMath.blockToTile((int) Math.floor(CENTER_BLOCK - SCREEN_W / 2.0 * scale), lod);
            this.maxX = TileMath.blockToTile((int) Math.ceil(CENTER_BLOCK + SCREEN_W / 2.0 * scale), lod);
            this.minZ = TileMath.blockToTile((int) Math.floor(CENTER_BLOCK - SCREEN_H / 2.0 * scale), lod);
            this.maxZ = TileMath.blockToTile((int) Math.ceil(CENTER_BLOCK + SCREEN_H / 2.0 * scale), lod);
        }
    }

    private static final class Fixture {
        final FakeVoidWorldServer server;
        final MapSyncClient client;
        final CorrectionStore corrections;
        final MapExecutors executors;
        final Deque<byte[]> wire = new ArrayDeque<>();
        long nowMs = SIM_START_MS;
        final long nanoOrigin = System.nanoTime();

        Fixture(final Path tempDir) {
            server = new FakeVoidWorldServer(new ServerConfig());

            final SessionGuard sessionGuard = new SessionGuard();
            sessionGuard.begin(WORLD, DIM);
            executors = new MapExecutors();
            final TileService uploads =
                new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
            final PredictionTileService predictionTiles =
                new PredictionTileService(sessionGuard, new PredictionState(), executors, uploads);
            corrections = new CorrectionStore(tempDir);
            corrections.onSessionChanged(sessionGuard.current());
            predictionTiles.bindCorrectionStore(corrections);

            final CompanionSession session = new CompanionSession();
            session.onPolicy(new HelloPolicyS2C(
                new HelloPolicyS2C.Flags(false, true, false),
                "11111111-2222-3333-4444-555555555555",
                "1.17",
                new HelloPolicyS2C.Budgets(
                    server.config.maxBytesPerSecondPerPlayer,
                    server.config.maxTilesPerRequest,
                    server.config.minRequestIntervalMs,
                    server.config.maxPatchLod
                ),
                List.of(new HelloPolicyS2C.DimDescriptor(DIM.toString(), "overworld", true, false, 0L))
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

        void browse(final int lod, final long durationMs) throws ProtoException {
            final Viewport view = new Viewport(lod);
            final long start = nowMs - SIM_START_MS;
            for (long t = start; t <= start + durationMs; t += FRAME_MS) {
                nowMs = SIM_START_MS + t;
                server.tickDrain(nanos(), client);
                client.reportViewport(DIM, lod, view.minX, view.maxX, view.minZ, view.maxZ);
                while (!wire.isEmpty()) {
                    final MapViewReqC2S request = (MapViewReqC2S) MsgCodec.decode(wire.poll());
                    server.handle(request, nanos(), client);
                }
            }
        }

        /**
         * Counts viewport pixels whose block center lies strictly inside the void rectangle but
         * that hold no correction sample - each one renders as (wrong) predicted terrain.
         */
        int missingVoidPixels(final int lod) {
            final Viewport view = new Viewport(lod);
            final int scale = 1 << lod;
            int missing = 0;
            final List<String> holes = new ArrayList<>();
            for (int tileZ = view.minZ; tileZ <= view.maxZ; tileZ++) {
                for (int tileX = view.minX; tileX <= view.maxX; tileX++) {
                    final CorrectionTile tile = corrections.get(DIM, lod, tileX, tileZ);
                    int tileMissing = 0;
                    for (int pz = 0; pz < 256; pz++) {
                        for (int px = 0; px < 256; px++) {
                            final long blockX = (long) tileX * 256 * scale + (long) px * scale + (scale >>> 1);
                            final long blockZ = (long) tileZ * 256 * scale + (long) pz * scale + (scale >>> 1);
                            // One-pixel interior margin keeps edge sampling out of the verdict.
                            if (blockX < VOID_MIN_BLOCK + scale || blockX >= VOID_MAX_BLOCK - scale
                                || blockZ < VOID_MIN_BLOCK + scale || blockZ >= VOID_MAX_BLOCK - scale) {
                                continue;
                            }
                            if (tile.sampleAt(pz * 256 + px) == null) {
                                tileMissing++;
                            }
                        }
                    }
                    if (tileMissing > 0) {
                        holes.add("tile " + tileX + "," + tileZ + " missing " + tileMissing);
                        missing += tileMissing;
                    }
                }
            }
            if (missing > 0) {
                System.out.println("[void-sync] lod " + lod + " holes: " + holes
                    + " (requests=" + server.requestCount + " errors=" + server.errorCount + ")");
            }
            return missing;
        }

        long nanos() {
            return nanoOrigin + (nowMs - SIM_START_MS) * 1_000_000L;
        }

        void shutdown() {
            executors.shutdown(2000);
        }
    }

    /**
     * Mirrors {@code RegionSummaryService.request}'s admission control and job building with the
     * real {@link PatchDispatcher} and real {@link PatchBuilder} residual path; only the summary
     * regions and the baseline grid are synthetic, and they agree everywhere outside the void.
     */
    private static final class FakeVoidWorldServer {
        final ServerConfig config;
        final PatchDispatcher dispatcher;
        final PatchBuilder patchBuilder = new PatchBuilder();
        int requestCount;
        int errorCount;

        FakeVoidWorldServer(final ServerConfig config) {
            this.config = config;
            this.dispatcher = new PatchDispatcher(
                new PlayerBudget(config.maxBytesPerSecondPerPlayer, config.minRequestIntervalMs),
                config.maxPendingTilesPerPlayer
            );
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
            final SummaryTile summary = readTile(job.lod(), job.tileX(), job.tileZ());
            final PatchBuilder.Result result =
                patchBuilder.build(summary, job.sinceRevision(), baseline(job.lod(), job.tileX(), job.tileZ()), false);
            return new MapPatchS2C(job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
                result.mode(), result.revision(), result.presence(), result.body());
        }

        private SummaryTile readTile(final int lod, final int tileX, final int tileZ) {
            final int regionsPerSide = 1 << lod;
            final List<SummaryCodec.Region> regions = new ArrayList<>(regionsPerSide * regionsPerSide);
            for (int dz = 0; dz < regionsPerSide; dz++) {
                for (int dx = 0; dx < regionsPerSide; dx++) {
                    regions.add(region(tileX * regionsPerSide + dx, tileZ * regionsPerSide + dz));
                }
            }
            return new SummaryTile(lod, tileX, tileZ, regions);
        }

        private SummaryCodec.Region region(final int regionX, final int regionZ) {
            final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
            for (int cz = 0; cz < 16; cz++) {
                for (int cx = 0; cx < 16; cx++) {
                    final SummaryCodec.Column[] columns = new SummaryCodec.Column[SummaryCodec.COLUMNS];
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            final long blockX = (long) regionX * 256 + cx * 16L + x;
                            final long blockZ = (long) regionZ * 256 + cz * 16L + z;
                            columns[z * 16 + x] = inVoid(blockX, blockZ)
                                ? new SummaryCodec.Column(
                                    BIOME_PLAINS, bedrockY(blockX, blockZ), SurfaceKind.LAND.ordinal(), 7, 0)
                                : new SummaryCodec.Column(
                                    BIOME_PLAINS, TERRAIN_Y, SurfaceKind.LAND.ordinal(), Proto.MAP_COLOR_NONE, 0);
                        }
                    }
                    chunks[cz * 16 + cx] = new SummaryCodec.Chunk(true, 100L, columns);
                }
            }
            return new SummaryCodec.Region(regionX, regionZ, 1L, chunks);
        }

        /** The deterministic client baseline: plains terrain everywhere, unaware of the void. */
        private BaselineGrid baseline(final int lod, final int tileX, final int tileZ) {
            final BaselineGrid grid = new BaselineGrid();
            for (int z = -BaselineGrid.MARGIN; z < BaselineGrid.PIXELS + BaselineGrid.MARGIN; z++) {
                for (int x = -BaselineGrid.MARGIN; x < BaselineGrid.PIXELS + BaselineGrid.MARGIN; x++) {
                    final int index = BaselineGrid.index(x, z);
                    grid.biomeId[index] = BIOME_PLAINS;
                    grid.terrainY[index] = TERRAIN_Y;
                }
            }
            return grid;
        }
    }
}
