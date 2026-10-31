package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.BiomeColorPalette;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.LightTint;
import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.color.XaeroMapStyle;
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
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.BiomeTileKeys;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.tile.TileUpdate;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PredictionTileService}'s queue discipline, independent of native availability (see
 * {@link PredictionTileService#requestTile}'s javadoc for why the availability gate lives in
 * {@code composeTile} instead of here). The viewport-pruning test pins every worker thread with
 * a blocking task before queuing real requests, so the synchronous drain inside {@code pump()}
 * (which tiles land in {@code dirty} vs. {@code inFlight}) can be asserted on deterministically
 * instead of racing a real composition to finish.
 */
class PredictionTileServiceTest {
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("prediction-tile-service-test");

    private static PredictionTileService newService(final SessionGuard sessionGuard, final MapExecutors executors, final TileService uploads) {
        return newService(sessionGuard, new PredictionState(), executors, uploads);
    }

    private static PredictionTileService newService(
        final SessionGuard sessionGuard,
        final PredictionState state,
        final MapExecutors executors,
        final TileService uploads
    ) {
        return new PredictionTileService(sessionGuard, state, executors, uploads);
    }

    @Test
    void localAuthorityBoundaryKeepsPredictionWhileLocalChunkIsPending(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final SessionGuard.Session session = sessionGuard.begin(WORLD, DIM);
        final MapWorldService worlds = new MapWorldService();
        worlds.switchSession(session);
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            worlds, executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(
            sessionGuard, state, executors, uploads
        );
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        final TileKey key = new TileKey(WORLD, DIM, "surface!pred", 0, 0, 0);

        try {
            predictionTiles.setViewport(DIM, 0, 0, 0, 0, 0);
            predictionTiles.requestTile(key);
            awaitIdle(predictionTiles);
            assertTrue(latestUpdate(uploads, key).argbPixels()[8 * 256 + 8] != Argb.TRANSPARENT);

            uploads.setLocalAuthorityViewport(new ChunkViewport(0, 0, 0, 0));
            predictionTiles.refreshLiveCoverage();
            awaitIdle(predictionTiles);
            assertTrue(
                latestUpdate(uploads, key).argbPixels()[8 * 256 + 8] != Argb.TRANSPARENT,
                "the player-view chunk must keep prediction until its local snapshot exists"
            );

            uploads.setLocalAuthorityViewport(new ChunkViewport(1, 1, 0, 0));
            predictionTiles.refreshLiveCoverage();
            awaitIdle(predictionTiles);
            assertTrue(
                latestUpdate(uploads, key).argbPixels()[8 * 256 + 8] != Argb.TRANSPARENT,
                "leaving player view must preserve the predicted outer source"
            );
        } finally {
            executors.shutdown(2000L);
        }
    }

    @Test
    void biomePredictionExposesItsBiomeForCursorReadout(@TempDir final Path tempDir) throws InterruptedException {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.DEFAULT, WorldPreset.DEFAULT);
        state.setSeed(146008555L, McVersions.toCubiomes("1.17").orElseThrow());
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        final CorrectionStore corrections = new CorrectionStore(tempDir);
        predictionTiles.bindCorrectionStore(corrections);
        sessionGuard.begin(WORLD, DIM);

        try {
            predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!biome!pred", 2, 0, 0));
            awaitIdle(predictionTiles, 10_000L);

            // The biome tile owns its metadata slot: it answers the cursor biome readout on its
            // own, while the surface readout waits for the terrain twin instead of trusting the
            // biome view's terrain-free flat land plane.
            assertEquals(35, predictionTiles.predictedBiomeAt(DIM, 2, 0, 0).orElse(-1));
            assertTrue(
                predictionTiles.predictedSurfaceYAt(DIM, 2, 0, 0).isEmpty(),
                "a terrain-free biome tile must not answer teleport heights"
            );
            predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 2, 0, 0));
            awaitIdle(predictionTiles, 10_000L);
            assertTrue(predictionTiles.predictedSurfaceYAt(DIM, 2, 0, 0).isPresent());

            final PatchCodec.Sample correction = new PatchCodec.Sample(
                0, 4, 80, SurfaceKind.LAND.ordinal(), Proto.MAP_COLOR_NONE, 0
            );
            assertTrue(predictionTiles.applyCorrection(
                new CorrectionStore.Key(DIM.toString(), 2, 0, 0),
                1L,
                new byte[Proto.PATCH_PRESENCE_BYTES],
                new PatchCodec.Patch(List.of(correction)),
                Proto.PATCH_MODE_RESIDUAL,
                PredictorVersion.full(),
                System.currentTimeMillis()
            ));
            awaitIdle(predictionTiles, 10_000L);
            assertEquals(4, predictionTiles.predictedBiomeAt(DIM, 2, 0, 0).orElse(-1));
            assertEquals(80, predictionTiles.predictedSurfaceYAt(DIM, 2, 0, 0).orElseThrow());

            predictionTiles.setViewMode(PredictionViewMode.VISITED_ONLY);
            assertTrue(predictionTiles.predictedBiomeAt(DIM, 2, 0, 0).isEmpty());
            assertTrue(predictionTiles.predictedSurfaceYAt(DIM, 2, 0, 0).isEmpty());

            predictionTiles.setViewMode(PredictionViewMode.EVERYWHERE);
            predictionTiles.clearViewport();
            assertTrue(
                predictionTiles.predictedBiomeAt(DIM, 2, 0, 0).isEmpty(),
                "a cleared metadata entry should be requeued instead of returning stale data"
            );
            assertTrue(predictionTiles.predictedSurfaceYAt(DIM, 2, 0, 0).isEmpty());
            awaitIdle(predictionTiles, 10_000L);
            assertEquals(4, predictionTiles.predictedBiomeAt(DIM, 2, 0, 0).orElse(-1));
            assertEquals(80, predictionTiles.predictedSurfaceYAt(DIM, 2, 0, 0).orElseThrow());
        } finally {
            executors.shutdown(2000);
        }
    }

    @Test
    void cursorBiomeRemainsAvailableWhileRealCoverageRefreshes(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final SessionGuard.Session session = sessionGuard.begin(WORLD, DIM);
        final MapWorldService worlds = new MapWorldService();
        worlds.switchSession(session);
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            worlds, executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(
            sessionGuard, state, executors, uploads
        );
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        final TileKey key = new TileKey(WORLD, DIM, "surface!pred", 0, 0, 0);
        final CountDownLatch workersReady = new CountDownLatch(executors.workerCount());
        final CountDownLatch releaseWorkers = new CountDownLatch(1);

        try {
            predictionTiles.setViewport(DIM, 0, 0, 0, 0, 0);
            predictionTiles.requestTile(key);
            awaitIdle(predictionTiles);
            assertTrue(predictionTiles.predictedBiomeAt(DIM, 0, 0, 0).isPresent());

            for (int i = 0; i < executors.workerCount(); i++) {
                executors.workers().execute(() -> {
                    workersReady.countDown();
                    try {
                        releaseWorkers.await();
                    } catch (final InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(workersReady.await(2, TimeUnit.SECONDS));

            predictionTiles.refreshLiveCoverage();

            assertTrue(
                predictionTiles.predictedBiomeAt(DIM, 0, 0, 0).isPresent(),
                "cursor biome must not disappear while its predicted tile refreshes"
            );
        } finally {
            releaseWorkers.countDown();
            executors.shutdown(2000L);
        }
    }

    @Test
    void biomeViewMetadataStaysInItsOwnSlotWithoutNativePrediction(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final SessionGuard.Session session = sessionGuard.begin(WORLD, DIM);
        final MapWorldService worlds = new MapWorldService();
        worlds.switchSession(session);
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            worlds, executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(
            sessionGuard, state, executors, uploads
        );
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));

        try {
            predictionTiles.setViewport(DIM, 0, 0, 0, 0, 0);
            predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!biome!pred", 0, 0, 0));
            awaitIdle(predictionTiles);

            assertTrue(
                predictionTiles.predictedBiomeAt(DIM, 0, 0, 0).isPresent(),
                "the biome tile's own slot must answer the cursor readout"
            );
            assertTrue(
                predictionTiles.predictedSurfaceYAt(DIM, 0, 0, 0).isEmpty(),
                "the terrain slot must stay empty until a terrain tile composes"
            );

            predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 0, 0, 0));
            awaitIdle(predictionTiles);
            assertEquals(
                63, predictionTiles.predictedSurfaceYAt(DIM, 0, 0, 0).orElseThrow(),
                "the terrain twin must still serve teleport heights from its own slot"
            );
        } finally {
            executors.shutdown(2000L);
        }
    }

    @Test
    void netherCorrectionsComposeAndRefreshTheRoofLayer(@TempDir final Path tempDir) throws InterruptedException {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final SessionGuard sessionGuard = new SessionGuard();
        final SessionGuard.Session session = sessionGuard.begin(WORLD, DimensionId.NETHER);
        final MapWorldService worlds = new MapWorldService();
        worlds.switchSession(session);
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            worlds, executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.DEFAULT, WorldPreset.DEFAULT);
        state.setSeed(146008555L, McVersions.toCubiomes("1.17").orElseThrow());
        final PredictionTileService predictionTiles = newService(
            sessionGuard, state, executors, uploads
        );
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        final TileKey roof = new TileKey(
            WORLD, DimensionId.NETHER,
            MapLayer.NETHER_CEILING.cacheId() + PredictedTileKeys.SUFFIX,
            2, 0, 0
        );

        try {
            predictionTiles.requestTile(roof);
            awaitIdle(predictionTiles, 10_000L);

            final TileUpdate initialRoof = uploads.drainUploads(8).stream()
                .filter(update -> update.key().equals(roof))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "the Nether prediction must upload to the bedrock-roof layer"
                ));
            assertTrue(
                Arrays.stream(initialRoof.argbPixels()).allMatch(
                    color -> color == Argb.multiply(
                        MapColorTable.argb(PredictionDimensions.NETHER_ROOF_MAP_COLOR_ID),
                        LightTint.multiplier(0, 0, true)
                    )
                ),
                "terrain mode must render uniform bedrock with the captured map's Nether ambient light"
            );
            assertEquals(
                PredictionDimensions.NETHER_ROOF_Y,
                predictionTiles.predictedSurfaceYAt(DimensionId.NETHER, 2, 0, 0).orElseThrow()
            );

            final TileKey biomeRoof = BiomeTileKeys.toBiome(roof);
            predictionTiles.requestTile(biomeRoof);
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate initialBiomeRoof = uploads.drainUploads(8).stream()
                .filter(update -> update.key().equals(biomeRoof))
                .findFirst()
                .orElseThrow();
            final int predictedBiome = predictionTiles.predictedBiomeAt(
                DimensionId.NETHER, 2, 0, 0
            ).orElseThrow();
            assertEquals(
                BiomeColorPalette.colorForCubiomes(predictedBiome),
                initialBiomeRoof.argbPixels()[0],
                "biome mode must keep rendering the predicted Nether biome identity"
            );
            assertNotEquals(
                initialRoof.argbPixels()[0], initialBiomeRoof.argbPixels()[0],
                "terrain and biome modes must not share the fixed bedrock color"
            );

            assertTrue(predictionTiles.applyCorrection(
                new CorrectionStore.Key(DimensionId.NETHER.toString(), 2, 0, 0),
                1L,
                new byte[Proto.PATCH_PRESENCE_BYTES],
                new PatchCodec.Patch(List.of(new PatchCodec.Sample(
                    0, 172, 140, SurfaceKind.LAND.ordinal(), 11, 0
                ))),
                Proto.PATCH_MODE_RESIDUAL,
                PredictorVersion.full(),
                System.currentTimeMillis()
            ));
            awaitIdle(predictionTiles, 10_000L);

            assertTrue(
                uploads.drainUploads(8).stream().anyMatch(update -> update.key().equals(roof)),
                "a Nether correction must refresh the roof tile, not the surface layer"
            );
            assertEquals(
                172,
                predictionTiles.predictedBiomeAt(DimensionId.NETHER, 2, 0, 0).orElseThrow()
            );
            assertEquals(
                140,
                predictionTiles.predictedSurfaceYAt(DimensionId.NETHER, 2, 0, 0).orElseThrow()
            );

            predictionTiles.setViewport(DimensionId.NETHER, 2, 0, 0, 0, 0);
            assertTrue(worlds.current().put(
                MapLayer.NETHER_CEILING, voidSnapshot(session.token()), SampleSource.REAL_LIVE
            ));
            uploads.markChunkStored(
                session.token(), DimensionId.NETHER, MapLayer.NETHER_CEILING, 0, 0
            );
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate covered = uploads.drainUploads(64).stream()
                .filter(update -> update.key().equals(roof))
                .reduce((first, second) -> second)
                .orElseThrow();
            assertEquals(
                Argb.TRANSPARENT,
                covered.argbPixels()[0],
                "captured roof coverage must replace the predicted Nether pixel"
            );
        } finally {
            executors.shutdown(2000);
        }
    }

    @Test
    void lowerNetherBiomeLayerComposesAtTheSelectedHeight(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final SessionGuard sessionGuard = new SessionGuard();
        final SessionGuard.Session session = sessionGuard.begin(WORLD, DimensionId.NETHER);
        final MapWorldService worlds = new MapWorldService();
        worlds.switchSession(session);
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            worlds, executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.DEFAULT, WorldPreset.DEFAULT);
        state.setSeed(146008555L, McVersions.toCubiomes("1.21.1").orElseThrow());
        final PredictionTileService predictionTiles = newService(
            sessionGuard, state, executors, uploads
        );
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        predictionTiles.setNetherBiomeY(37);
        final TileKey key = new TileKey(
            WORLD, DimensionId.NETHER, "nether!biome!pred", 2, 0, 0
        );

        try {
            predictionTiles.requestTile(key);
            awaitIdle(predictionTiles, 10_000L);

            final TileUpdate update = uploads.drainUploads(8).stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow();
            assertTrue(Arrays.stream(update.argbPixels()).anyMatch(color -> color != Argb.TRANSPARENT));
            assertTrue(
                predictionTiles.predictedBiomeAt(
                    DimensionId.NETHER, MapLayer.NETHER_CURRENT, 2, 0, 0
                ).isPresent()
            );

            predictionTiles.setNetherBiomeY(93);
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate higher = uploads.drainUploads(8).stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow();
            assertFalse(
                Arrays.equals(update.argbPixels(), higher.argbPixels()),
                "changing the selected Nether height must refresh its biome plane"
            );

            predictionTiles.setViewport(DimensionId.NETHER, 2, 0, 0, 0, 0);
            assertTrue(worlds.current().put(
                MapLayer.NETHER_CURRENT, voidSnapshot(session.token()), SampleSource.REAL_LIVE
            ));
            uploads.markChunkStored(
                session.token(), DimensionId.NETHER, MapLayer.NETHER_CURRENT, 0, 0
            );
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate covered = uploads.drainUploads(64).stream()
                .filter(candidate -> candidate.key().equals(key))
                .reduce((first, second) -> second)
                .orElseThrow();
            assertEquals(
                Argb.TRANSPARENT, covered.argbPixels()[0],
                "captured current-layer coverage must replace the predicted biome pixel"
            );
        } finally {
            executors.shutdown(2000L);
        }
    }

    @Test
    void capturedEndVoidClearsAnAlreadyUploadedPrediction() throws InterruptedException {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final SessionGuard sessionGuard = new SessionGuard();
        final SessionGuard.Session session = sessionGuard.begin(WORLD, DimensionId.END);
        final MapWorldService worlds = new MapWorldService();
        worlds.switchSession(session);
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            worlds, executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.DEFAULT, WorldPreset.DEFAULT);
        state.setSeed(146008555L, McVersions.toCubiomes("1.17").orElseThrow());
        final PredictionTileService predictionTiles = newService(
            sessionGuard, state, executors, uploads
        );
        final TileKey key = new TileKey(
            WORLD, DimensionId.END, "end!pred", 0, 0, 0
        );

        try {
            predictionTiles.setViewport(DimensionId.END, 0, 0, 0, 0, 0);
            predictionTiles.requestTile(key);
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate initial = uploads.drainUploads(8).stream()
                .filter(update -> update.key().equals(key))
                .findFirst()
                .orElseThrow();
            assertTrue(
                initial.argbPixels()[8 * 256 + 8] != Argb.TRANSPARENT,
                "the central End island must provide a visible prediction before capture"
            );

            assertTrue(worlds.current().put(
                MapLayer.END_SURFACE, voidSnapshot(session.token()), SampleSource.REAL_LIVE
            ));
            uploads.markChunkStored(
                session.token(), DimensionId.END, MapLayer.END_SURFACE, 0, 0
            );
            awaitIdle(predictionTiles, 10_000L);

            final TileUpdate refreshed = uploads.drainUploads(64).stream()
                .filter(update -> update.key().equals(key))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError(
                    "captured End coverage must refresh the already-uploaded prediction"
                ));
            assertEquals(
                Argb.TRANSPARENT,
                refreshed.argbPixels()[8 * 256 + 8],
                "authoritative void must erase the false predicted island below the real map"
            );
            assertTrue(
                refreshed.argbPixels()[8 * 256 + 24] != Argb.TRANSPARENT,
                "the adjacent uncaptured chunk must keep its prediction"
            );

            predictionTiles.setViewport(DimensionId.END, 0, 1, 1, 0, 0);
            assertTrue(worlds.current().put(
                MapLayer.END_SURFACE, voidSnapshot(1, 0, session.token()), SampleSource.REAL_LIVE
            ));
            uploads.markChunkStored(
                session.token(), DimensionId.END, MapLayer.END_SURFACE, 1, 0
            );
            awaitIdle(predictionTiles, 10_000L);
            assertTrue(
                uploads.drainUploads(64).stream().noneMatch(update -> update.key().equals(key)),
                "off-screen real coverage should wait until the prediction re-enters the viewport"
            );

            predictionTiles.setViewport(DimensionId.END, 0, 0, 0, 0, 0);
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate reentered = uploads.drainUploads(64).stream()
                .filter(update -> update.key().equals(key))
                .reduce((first, second) -> second)
                .orElseThrow();
            assertEquals(
                Argb.TRANSPARENT,
                reentered.argbPixels()[8 * 256 + 24],
                "deferred real coverage must clear the prediction when the tile re-enters"
            );
        } finally {
            executors.shutdown(2000);
        }
    }

    private static ChunkSnapshot voidSnapshot(final long token) {
        return voidSnapshot(0, 0, token);
    }

    private static ChunkSnapshot voidSnapshot(
        final int chunkX,
        final int chunkZ,
        final long token
    ) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 65);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.VOID.ordinal());
        return new ChunkSnapshot(
            chunkX, chunkZ, token,
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

    /**
     * The superflat underlay is seedless and native-free: a FLAT preset plus its uniform surface
     * must compose a full tile (here a stone-topped flat world, map color 11) with no
     * NativeLib assumption anywhere in this test.
     */
    @Test
    void superflatComposesAUniformTileWithoutSeedOrNative(@TempDir final Path tempDir) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 3, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        sessionGuard.begin(WORLD, DIM);

        try {
            assertTrue(state.predictable(DIM), "flat baseline alone must make the overworld predictable");
            predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 2, 0, 0));
            awaitIdle(predictionTiles, 10_000L);

            final List<TileUpdate> updates = uploads.drainUploads(10);
            assertEquals(1, updates.size(), "the flat compose must reach the upload queue");
            final int[] pixels = updates.get(0).argbPixels();
            final int expected = ShadingPipeline.applyBrightnessMultiplier(
                ShadingPipeline.applyShade(
                    MapColorTable.argb(11),
                    ShadingPipeline.detailedHeightShade(3, ShadingPipeline.REFERENCE_HEIGHT)
                ),
                1.0
            );
            for (final int pixel : pixels) {
                assertEquals(expected, pixel, "every flat baseline pixel must be the top block's shaded map color");
            }
            assertEquals(1, predictionTiles.predictedBiomeAt(DIM, 2, 0, 0).orElse(-1));
            assertEquals(3, predictionTiles.predictedSurfaceYAt(DIM, 2, 0, 0).orElseThrow());
        } finally {
            executors.shutdown(2000);
        }
    }

    @Test
    void manualSeedPreviewIgnoresPersistedServerCorrections(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        state.setManualSeed(42L, McVersions.toCubiomes("1.17").orElseThrow());
        final CorrectionStore corrections = new CorrectionStore(tempDir);
        corrections.apply(
            new CorrectionStore.Key(DIM.toString(), 2, 0, 0),
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(List.of(new PatchCodec.Sample(
                0, 4, 80, SurfaceKind.LAND.ordinal(), Proto.MAP_COLOR_NONE, 0
            ))),
            Proto.PATCH_MODE_RESIDUAL,
            PredictorVersion.full(),
            System.currentTimeMillis()
        );
        final PredictionTileService predictionTiles = newService(
            sessionGuard, state, executors, uploads
        );
        predictionTiles.bindCorrectionStore(corrections);
        sessionGuard.begin(WORLD, DIM);

        try {
            predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 2, 0, 0));
            awaitIdle(predictionTiles, 10_000L);

            assertEquals(1, predictionTiles.predictedBiomeAt(DIM, 2, 0, 0).orElse(-1));
            assertEquals(63, predictionTiles.predictedSurfaceYAt(DIM, 2, 0, 0).orElseThrow());
        } finally {
            executors.shutdown(2000);
        }
    }

    @Test
    void synchronizedSurfaceUsesPerPixelBlockLightAtNight(@TempDir final Path tempDir) {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final DaylightModel daylight = new DaylightModel();
        daylight.update(0f);
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), daylight
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(
            sessionGuard, state, executors, uploads
        );
        predictionTiles.bindDaylightModel(daylight);
        final CorrectionStore corrections = new CorrectionStore(tempDir);
        predictionTiles.bindCorrectionStore(corrections);
        sessionGuard.begin(WORLD, DIM);
        corrections.onSessionChanged(sessionGuard.current());

        try {
            final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
            PatchCodec.setEvaluated(evaluated, 0);
            PatchCodec.setEvaluated(evaluated, 1);
            final long[] revisions = new long[PatchCodec.PIXELS];
            Arrays.fill(revisions, Long.MIN_VALUE);
            revisions[0] = 100L;
            revisions[1] = 100L;
            final byte[] light = new byte[PatchCodec.PIXELS];
            light[0] = 15;
            assertTrue(predictionTiles.applyCorrection(
                new CorrectionStore.Key(DIM.toString(), 0, 0, 0),
                1L,
                new byte[Proto.PATCH_PRESENCE_BYTES],
                new PatchCodec.Patch(evaluated, List.of(), revisions, light),
                Proto.PATCH_MODE_RESIDUAL,
                PredictorVersion.full(),
                System.currentTimeMillis()
            ));

            final int[] pixels = predictionTiles.snapshotTile(
                new TileKey(WORLD, DIM, "surface!pred", 0, 0, 0),
                PredictionViewMode.EVERYWHERE
            ).join();

            assertTrue(Argb.red(pixels[0]) > Argb.red(pixels[1]));
            assertTrue(Argb.green(pixels[0]) > Argb.green(pixels[1]));
        } finally {
            executors.shutdown(2000);
        }
    }

    @Test
    void synchronizedNetherRoofUsesPerPixelBlockLight(@TempDir final Path tempDir) {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.DEFAULT, WorldPreset.DEFAULT);
        state.setSeed(146008555L, McVersions.toCubiomes("1.17").orElseThrow());
        final PredictionTileService predictionTiles = newService(
            sessionGuard, state, executors, uploads
        );
        final CorrectionStore corrections = new CorrectionStore(tempDir);
        predictionTiles.bindCorrectionStore(corrections);
        sessionGuard.begin(WORLD, DimensionId.NETHER);
        corrections.onSessionChanged(sessionGuard.current());

        try {
            final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
            PatchCodec.setEvaluated(evaluated, 0);
            PatchCodec.setEvaluated(evaluated, 1);
            final long[] revisions = new long[PatchCodec.PIXELS];
            Arrays.fill(revisions, Long.MIN_VALUE);
            revisions[0] = 100L;
            revisions[1] = 100L;
            final byte[] light = new byte[PatchCodec.PIXELS];
            light[0] = 15;
            assertTrue(predictionTiles.applyCorrection(
                new CorrectionStore.Key(DimensionId.NETHER.toString(), 0, 0, 0),
                1L,
                new byte[Proto.PATCH_PRESENCE_BYTES],
                new PatchCodec.Patch(evaluated, List.of(), revisions, light),
                Proto.PATCH_MODE_RESIDUAL,
                PredictorVersion.full(),
                System.currentTimeMillis()
            ));

            final int[] pixels = predictionTiles.snapshotTile(
                new TileKey(
                    WORLD, DimensionId.NETHER,
                    MapLayer.NETHER_CEILING.cacheId() + PredictedTileKeys.SUFFIX,
                    0, 0, 0
                ),
                PredictionViewMode.EVERYWHERE
            ).join();

            assertTrue(Argb.red(pixels[0]) > Argb.red(pixels[1]));
            assertTrue(Argb.green(pixels[0]) > Argb.green(pixels[1]));
        } finally {
            executors.shutdown(2000);
        }
    }

    @Test
    void xaeroNetherRoofPredictionKeepsTheCapturedRoofRepresentationAtHighGamma(@TempDir final Path tempDir) {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final DaylightModel daylight = new DaylightModel();
        daylight.update(0f, 1f);
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), daylight
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.DEFAULT, WorldPreset.DEFAULT);
        state.setSeed(146008555L, McVersions.toCubiomes("1.17").orElseThrow());
        final PredictionTileService predictionTiles = newService(
            sessionGuard, state, executors, uploads
        );
        predictionTiles.setMapColorStyle(MapColorStyle.XAERO);
        predictionTiles.bindDaylightModel(daylight);
        final CorrectionStore corrections = new CorrectionStore(tempDir);
        predictionTiles.bindCorrectionStore(corrections);
        sessionGuard.begin(WORLD, DimensionId.NETHER);
        corrections.onSessionChanged(sessionGuard.current());

        try {
            final int[] pixels = predictionTiles.snapshotTile(
                new TileKey(
                    WORLD, DimensionId.NETHER,
                    MapLayer.NETHER_CEILING.cacheId() + PredictedTileKeys.SUFFIX,
                    0, 0, 0
                ),
                PredictionViewMode.EVERYWHERE
            ).join();

            // Captured Xaero roof pixels keep their zero-light Nether ambient bake through
            // composition (TileService applies no light replacement to NETHER_CEILING in the
            // Xaero branch), so the prediction must render identically instead of being
            // gamma-rebrightened into a bright pink plate.
            assertEquals(
                XaeroMapStyle.applyTerrain(
                    Argb.multiply(
                        MapColorTable.argb(PredictionDimensions.NETHER_ROOF_MAP_COLOR_ID),
                        LightTint.multiplier(0, 0, true)
                    ),
                    PredictionDimensions.NETHER_ROOF_Y, PredictionDimensions.NETHER_ROOF_Y,
                    PredictionDimensions.NETHER_ROOF_Y, 1, true,
                    XaeroMapStyle.Shadow.NETHER
                ),
                pixels[10 * 256 + 10]
            );
        } finally {
            executors.shutdown(2000);
        }
    }

    @Test
    void changingViewModeRefreshesCurrentAndReenteredTiles(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        sessionGuard.begin(WORLD, DIM);

        final TileKey firstTile = new TileKey(WORLD, DIM, "surface!pred", 2, 0, 0);
        final TileKey secondTile = new TileKey(WORLD, DIM, "surface!pred", 2, 1, 0);
        try {
            predictionTiles.setViewport(DIM, 2, 0, 0, 0, 0);
            predictionTiles.requestTile(firstTile);
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate everywhere = uploads.drainUploads(4).stream()
                .filter(update -> update.key().equals(firstTile))
                .findFirst()
                .orElseThrow();
            assertTrue(
                java.util.Arrays.stream(everywhere.argbPixels()).anyMatch(pixel -> pixel != 0),
                "the everywhere mode should draw the seed preview"
            );

            predictionTiles.setViewport(DIM, 2, 1, 1, 0, 0);
            predictionTiles.requestTile(secondTile);
            awaitIdle(predictionTiles, 10_000L);
            uploads.drainUploads(4);

            predictionTiles.setViewMode(PredictionViewMode.GENERATED_ONLY);
            awaitIdle(predictionTiles, 10_000L);

            final TileUpdate generatedOnly = uploads.drainUploads(4).stream()
                .filter(update -> update.key().equals(secondTile))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "changing the view mode must upload a replacement for every visible tile"
                ));
            assertTrue(
                java.util.Arrays.stream(generatedOnly.argbPixels()).allMatch(pixel -> pixel == 0),
                "generated-only mode should clear an ungenerated tile without waiting for a pan"
            );

            predictionTiles.setViewport(DIM, 2, 0, 0, 0, 0);
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate reentered = uploads.drainUploads(4).stream()
                .filter(update -> update.key().equals(firstTile))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "a tile cached under the old view mode must refresh when it re-enters the viewport"
                ));
            assertTrue(
                java.util.Arrays.stream(reentered.argbPixels()).allMatch(pixel -> pixel == 0),
                "re-entered tiles must use the current generated-only mode"
            );
        } finally {
            executors.shutdown(2000);
        }
    }

    @Test
    void lodOneReusesFourLodZeroTilesIncludingCommittedCorrections(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        sessionGuard.begin(WORLD, DIM);

        try {
            final long validatedAt = System.currentTimeMillis();
            for (int childZ = 0; childZ < 2; childZ++) {
                for (int childX = 0; childX < 2; childX++) {
                    final int quadrant = childZ * 2 + childX;
                    final List<PatchCodec.Sample> samples = new java.util.ArrayList<>();
                    for (final int pixel : new int[] {0, 1, 256, 257}) {
                        samples.add(new PatchCodec.Sample(
                            pixel,
                            4 + quadrant,
                            80 + quadrant,
                            SurfaceKind.LAND.ordinal(),
                            12 + quadrant,
                            0
                        ));
                    }
                    assertTrue(predictionTiles.applyCorrection(
                        new CorrectionStore.Key(DIM.toString(), 0, childX, childZ),
                        1L,
                        new byte[Proto.PATCH_PRESENCE_BYTES],
                        new PatchCodec.Patch(samples),
                        Proto.PATCH_MODE_RESIDUAL,
                        PredictorVersion.full(),
                        validatedAt
                    ));
                }
            }
            awaitIdle(predictionTiles, 10_000L);
            final Map<TileKey, int[]> childPixels = new HashMap<>();
            for (final TileUpdate update : uploads.drainUploads(16)) {
                childPixels.put(update.key(), update.argbPixels());
            }
            assertEquals(4, childPixels.size());

            final TileKey parent = new TileKey(WORLD, DIM, "surface!pred", 1, 0, 0);
            predictionTiles.requestTile(parent);
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate parentUpdate = uploads.drainUploads(4).stream()
                .filter(update -> update.key().equals(parent))
                .findFirst()
                .orElseThrow();
            assertTrue(predictionTiles.hasFreshLowerCoverage(DIM, 1, 0, 0, validatedAt));

            for (int childZ = 0; childZ < 2; childZ++) {
                for (int childX = 0; childX < 2; childX++) {
                    final TileKey child = new TileKey(WORLD, DIM, "surface!pred", 0, childX, childZ);
                    final int[] pixels = childPixels.get(child);
                    final int parentX = childX * 128;
                    final int parentZ = childZ * 128;
                    assertEquals(
                        Argb.average4Weighted(pixels[0], pixels[1], pixels[256], pixels[257]),
                        parentUpdate.argbPixels()[parentZ * 256 + parentX]
                    );
                    assertEquals(
                        4 + childZ * 2 + childX,
                        predictionTiles.predictedBiomeAt(DIM, 1, parentX * 2, parentZ * 2).orElse(-1)
                    );
                    assertEquals(
                        80 + childZ * 2 + childX,
                        predictionTiles.predictedSurfaceYAt(DIM, 1, parentX * 2, parentZ * 2).orElseThrow()
                    );
                }
            }

            final int oldParentPixel = parentUpdate.argbPixels()[0];
            assertTrue(predictionTiles.applyCorrection(
                new CorrectionStore.Key(DIM.toString(), 0, 0, 0),
                2L,
                new byte[Proto.PATCH_PRESENCE_BYTES],
                new PatchCodec.Patch(List.of(new PatchCodec.Sample(
                    0, 8, 95, SurfaceKind.LAND.ordinal(), 18, 0
                ))),
                Proto.PATCH_MODE_RESIDUAL,
                PredictorVersion.full(),
                validatedAt + 1L
            ));
            awaitIdle(predictionTiles, 10_000L);
            assertTrue(
                uploads.drainUploads(16).stream().noneMatch(update -> update.key().equals(parent)),
                "an off-viewport parent should wait until the user returns"
            );

            predictionTiles.setViewport(DIM, 1, 0, 0, 0, 0);
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate refreshedParent = uploads.drainUploads(16).stream()
                .filter(update -> update.key().equals(parent))
                .reduce((first, second) -> second)
                .orElseThrow();
            assertTrue(
                refreshedParent.argbPixels()[0] != oldParentPixel,
                "a child correction must invalidate and rebuild the visible parent"
            );

            assertTrue(predictionTiles.applyPartialCorrection(
                new CorrectionStore.Key(DIM.toString(), 0, 0, 0),
                new byte[Proto.PATCH_PRESENCE_BYTES],
                new PatchCodec.Patch(List.of())
            ));
            awaitIdle(predictionTiles, 10_000L);
            assertTrue(
                !predictionTiles.hasFreshLowerCoverage(DIM, 1, 0, 0, validatedAt + 2L),
                "an in-progress child scan must make the parent requestable"
            );
        } finally {
            executors.shutdown(2000);
        }
    }

    @Test
    void lodFourRebuildsFromMoreThanTheMemoryLimitOfPersistentLodZeroTiles(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        final long nowMillis = 20_000L;
        final SessionGuard sessionGuard = new SessionGuard();
        sessionGuard.begin(WORLD, DIM);
        final CorrectionStore writer = new CorrectionStore(tempDir);
        writer.onSessionChanged(sessionGuard.current());
        for (int tileZ = 0; tileZ < 16; tileZ++) {
            for (int tileX = 0; tileX < 16; tileX++) {
                assertTrue(writer.apply(
                    new CorrectionStore.Key(DIM.toString(), 0, tileX, tileZ),
                    1L,
                    new byte[Proto.PATCH_PRESENCE_BYTES],
                    new PatchCodec.Patch(List.of()),
                    Proto.PATCH_MODE_RESIDUAL,
                    PredictorVersion.full(),
                    nowMillis
                ));
            }
        }
        writer.flush();

        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = new PredictionTileService(
            sessionGuard, state, executors, uploads, () -> nowMillis
        );
        final CorrectionStore reopened = new CorrectionStore(tempDir);
        reopened.onSessionChanged(sessionGuard.current());
        predictionTiles.bindCorrectionStore(reopened);

        try {
            assertEquals(
                PredictionTileService.LowerCoverageState.PENDING,
                predictionTiles.prepareFreshLowerCoverage(DIM, 4, 0, 0, nowMillis)
            );
            awaitIdle(predictionTiles, 20_000L);
            assertEquals(
                PredictionTileService.LowerCoverageState.READY,
                predictionTiles.prepareFreshLowerCoverage(DIM, 4, 0, 0, nowMillis)
            );
            assertTrue(
                uploads.drainUploads(512).stream().anyMatch(update -> update.key().lod() == 4),
                "the locally reduced LOD4 tile must reach the normal upload seam"
            );
        } finally {
            executors.shutdown(2000);
        }
    }

    @Test
    void staleSessionTokenResultsAreDropped() throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionTileService predictionTiles = newService(sessionGuard, executors, uploads);

        sessionGuard.begin(WORLD, DIM);
        predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 2, 0, 0));

        // Rotate the session before the async compose can possibly run - simulates a world/dimension
        // change landing mid-flight. The queued token no longer matches sessionGuard.current().
        sessionGuard.begin(WORLD, DIM);

        awaitIdle(predictionTiles);
        assertTrue(uploads.drainUploads(10).isEmpty(), "a stale-token compose must never reach the upload queue");
        executors.shutdown(1000);
    }

    @Test
    void viewportPruningDropsOutOfRectTiles() throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionTileService predictionTiles = newService(sessionGuard, executors, uploads);
        sessionGuard.begin(WORLD, DIM);

        // Occupy every worker thread with a blocker before queuing anything real, so pump()'s
        // synchronous dirty->inFlight drain can be inspected without racing a real composition.
        final int cap = executors.workerCount();
        final CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < cap; i++) {
            executors.workers().execute(() -> {
                try {
                    release.await();
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread.sleep(50);

        try {
            final int requested = cap + 3;
            for (int i = 0; i < requested; i++) {
                predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 2, i, 0));
            }

            final Set<TileKey> pendingBefore = predictionTiles.pendingKeysForTest();
            assertTrue(
                pendingBefore.size() == 3,
                "expected exactly (requested - cap) tiles still queued, got " + pendingBefore.size() + " (cap=" + cap + ")"
            );

            // +1-padded viewport keeping only tileX in [-1, 1] - excludes every remaining queued tile
            // (tileX in [cap, cap+2], and cap >= 1) unless cap == 1, in which case tileX=1 survives.
            predictionTiles.setViewport(DIM, 2, 0, 0, 0, 0);
            final Set<TileKey> pendingAfter = predictionTiles.pendingKeysForTest();
            for (final TileKey key : pendingAfter) {
                assertTrue(key.tileX() >= -1 && key.tileX() <= 1, "pruning should have dropped tile " + key);
            }
            assertTrue(pendingAfter.size() < pendingBefore.size(), "pruning should have removed at least one out-of-rect tile");
        } finally {
            release.countDown();
            executors.shutdown(2000);
        }
    }

    @Test
    void mapQualityUpdatesPredictionCacheLimit() {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionTileService predictionTiles = newService(sessionGuard, executors, uploads);

        assertEquals(64, predictionTiles.cacheLimitForTest());
        predictionTiles.setMapQuality(ConfluxConfig.MapQuality.BALANCED);
        assertEquals(256, predictionTiles.cacheLimitForTest());
        predictionTiles.setMapQuality(ConfluxConfig.MapQuality.ULTRA_QUALITY);
        assertEquals(512, predictionTiles.cacheLimitForTest());
    }

    @Test
    void viewportChangePreloadsPaddedRingAroundViewport() throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        sessionGuard.begin(WORLD, DIM);

        final int cap = executors.workerCount();
        final CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < cap; i++) {
            executors.workers().execute(() -> {
                try {
                    release.await();
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread.sleep(50);

        try {
            predictionTiles.setViewport(DIM, 0, 5, 5, 5, 5);
            predictionTiles.preloadViewportPaddedRing();
            final Set<TileKey> pending = predictionTiles.queuedOrInFlightKeysForTest();
            // A 1x1 viewport (5, 5) has a 1-padded 3x3 ring with 8 outer ring tiles:
            // (4..6, 4..6) excluding (5, 5)
            for (int z = 4; z <= 6; z++) {
                for (int x = 4; x <= 6; x++) {
                    if (x == 5 && z == 5) {
                        continue;
                    }
                    final TileKey ringKey = new TileKey(WORLD, DIM, "surface!pred", 0, x, z);
                    assertTrue(pending.contains(ringKey), "outer ring tile " + ringKey + " should be queued or in-flight");
                }
            }
        } finally {
            release.countDown();
            executors.shutdown(2000);
        }
    }

    @Test
    void visibleViewportTilesComposeAheadOfPaddedRingTiles(@TempDir final Path tempDir) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        sessionGuard.begin(WORLD, DIM);

        final int cap = executors.workerCount();
        final CountDownLatch[] releaseWorkers = new CountDownLatch[cap];
        for (int i = 0; i < cap; i++) {
            releaseWorkers[i] = new CountDownLatch(1);
            final CountDownLatch mine = releaseWorkers[i];
            executors.workers().execute(() -> {
                try {
                    mine.await();
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread.sleep(50);

        try {
            for (int i = 0; i < cap; i++) {
                predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 0, 100 + i, 100));
            }
            // 1x1 viewport at (5, 5). Ring tile is at (6, 5).
            predictionTiles.setViewport(DIM, 0, 5, 5, 5, 5);
            predictionTiles.preloadViewportPaddedRing();
            final TileKey visible = new TileKey(WORLD, DIM, "surface!pred", 0, 5, 5);
            final TileKey ring = new TileKey(WORLD, DIM, "surface!pred", 0, 6, 5);
            predictionTiles.requestTile(visible);

            releaseWorkers[0].countDown();
            awaitIdle(predictionTiles, 10_000L);
            final List<TileKey> order = uploads.drainUploads(64).stream()
                .map(TileUpdate::key)
                .toList();
            final int visibleAt = order.indexOf(visible);
            final int ringAt = order.indexOf(ring);
            assertTrue(visibleAt >= 0 && ringAt >= 0, "both visible and ring tiles must compose");
            assertTrue(visibleAt < ringAt, "visible viewport tile must compose before padded ring tile");
        } finally {
            for (final CountDownLatch releaseWorker : releaseWorkers) {
                releaseWorker.countDown();
            }
            executors.shutdown(2000);
        }
    }

    @Test
    void reloadRequeuesInFlightTilesWithoutSchedulingDuplicates() throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionTileService predictionTiles = newService(sessionGuard, executors, uploads);
        sessionGuard.begin(WORLD, DIM);

        final int cap = executors.workerCount();
        final CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < cap; i++) {
            executors.workers().execute(() -> {
                try {
                    release.await();
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread.sleep(50);

        final TileKey key = new TileKey(WORLD, DIM, "surface!pred", 2, 3, 4);
        try {
            predictionTiles.requestTile(key);
            predictionTiles.reloadAll();
            assertTrue(predictionTiles.pendingKeysForTest().contains(key), "reload must requeue an in-flight tile");
        } finally {
            release.countDown();
            executors.shutdown(2000);
        }
    }

    @Test
    void menuTargetTilesComposeAheadOfBackgroundRequests(@TempDir final Path tempDir) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        sessionGuard.begin(WORLD, DIM);

        // One latch per worker, and only the first is ever released during the observation:
        // every remaining composition then serializes through that single freed thread, so
        // the upload order pins the dispatcher's real choices (not a parallel-compose race).
        final int cap = executors.workerCount();
        final CountDownLatch[] releaseWorkers = new CountDownLatch[cap];
        for (int i = 0; i < cap; i++) {
            releaseWorkers[i] = new CountDownLatch(1);
            final CountDownLatch mine = releaseWorkers[i];
            executors.workers().execute(() -> {
                try {
                    mine.await();
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread.sleep(50);

        final TileKey urgent = new TileKey(WORLD, DIM, "surface!pred", 0, 9, 5);
        final TileKey near = new TileKey(WORLD, DIM, "surface!pred", 0, 1, 0);
        final TileKey far = new TileKey(WORLD, DIM, "surface!pred", 0, 2, 0);
        try {
            // Fill every composition slot first, so every later request stays queued and the
            // dispatch order is decided by nearestDirty alone.
            for (int i = 0; i < cap; i++) {
                predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 0, 100 + i, 100));
            }
            predictionTiles.requestTile(near);
            predictionTiles.requestTile(far);
            predictionTiles.requestTileUrgently(urgent);
            final Set<TileKey> pending = predictionTiles.pendingKeysForTest();
            assertTrue(pending.contains(urgent) && pending.contains(near) && pending.contains(far),
                "the filler tiles must occupy every slot before the observed requests");

            releaseWorkers[0].countDown();
            awaitIdle(predictionTiles, 10_000L);
            final List<TileKey> order = uploads.drainUploads(64).stream()
                .map(TileUpdate::key)
                .toList();
            final int urgentAt = order.indexOf(urgent);
            final int nearAt = order.indexOf(near);
            final int farAt = order.indexOf(far);
            assertTrue(urgentAt >= 0 && nearAt >= 0 && farAt >= 0, "every requested tile must compose");
            assertTrue(
                urgentAt < nearAt && nearAt < farAt,
                "the urgent menu target must compose before background tiles nearer the viewpoint"
            );
        } finally {
            for (final CountDownLatch release : releaseWorkers) {
                release.countDown();
            }
            executors.shutdown(2000);
        }
    }

    @Test
    void viewportTilesComposeCenterOutFromViewportCenter(@TempDir final Path tempDir) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        sessionGuard.begin(WORLD, DIM);

        // Same pinning discipline as menuTargetTilesComposeAheadOfBackgroundRequests: one latch
        // per worker, only the first released, so uploads serialize the dispatcher's choices.
        final int cap = executors.workerCount();
        final CountDownLatch[] releaseWorkers = new CountDownLatch[cap];
        for (int i = 0; i < cap; i++) {
            releaseWorkers[i] = new CountDownLatch(1);
            final CountDownLatch mine = releaseWorkers[i];
            executors.workers().execute(() -> {
                try {
                    mine.await();
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread.sleep(50);

        try {
            for (int i = 0; i < cap; i++) {
                predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 0, 100 + i, 100));
            }
            // 5x5 viewport whose center is tile (2, 2); the observed tiles sit at squared
            // center distances 0, 1, 4, 8, 8, so row-major dispatch (corner first) fails.
            predictionTiles.setViewport(DIM, 0, 0, 4, 0, 4);
            final TileKey center = new TileKey(WORLD, DIM, "surface!pred", 0, 2, 2);
            final TileKey edgeA = new TileKey(WORLD, DIM, "surface!pred", 0, 2, 1);
            final TileKey edgeB = new TileKey(WORLD, DIM, "surface!pred", 0, 0, 2);
            final TileKey cornerA = new TileKey(WORLD, DIM, "surface!pred", 0, 0, 0);
            final TileKey cornerB = new TileKey(WORLD, DIM, "surface!pred", 0, 4, 4);
            final List<TileKey> observed = List.of(cornerA, cornerB, edgeA, edgeB, center);
            for (final TileKey key : observed) {
                predictionTiles.requestTile(key);
            }

            releaseWorkers[0].countDown();
            awaitIdle(predictionTiles, 10_000L);
            final List<TileKey> order = uploads.drainUploads(64).stream()
                .map(TileUpdate::key)
                .toList();
            final Map<TileKey, Long> firstUploadAt = new HashMap<>();
            for (final TileKey key : observed) {
                final int at = order.indexOf(key);
                assertTrue(at >= 0, "every observed tile must compose, got " + order);
                firstUploadAt.put(key, (long) at);
            }
            final List<TileKey> byUpload = observed.stream()
                .sorted(Comparator.comparing(firstUploadAt::get))
                .toList();
            assertEquals(center, byUpload.get(0), "the viewport-center tile must compose first, got " + byUpload);
            long previous = centerDistanceSq(byUpload.get(0));
            for (int i = 1; i < byUpload.size(); i++) {
                final long next = centerDistanceSq(byUpload.get(i));
                assertTrue(
                    previous <= next,
                    "viewport tiles must compose outward from the viewport center, got " + byUpload
                );
                previous = next;
            }
        } finally {
            for (final CountDownLatch release : releaseWorkers) {
                release.countDown();
            }
            executors.shutdown(2000);
        }
    }

    /** Squared tile distance from the 5x5 viewport rect (0..4) x (0..4) center, tile (2, 2). */
    private static long centerDistanceSq(final TileKey key) {
        final long dx = key.tileX() - 2;
        final long dz = key.tileZ() - 2;
        return dx * dx + dz * dz;
    }

    @Test
    void menuTargetTilesComposeAheadOfViewportTiles(@TempDir final Path tempDir) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        sessionGuard.begin(WORLD, DIM);

        final int cap = executors.workerCount();
        final CountDownLatch[] releaseWorkers = new CountDownLatch[cap];
        for (int i = 0; i < cap; i++) {
            releaseWorkers[i] = new CountDownLatch(1);
            final CountDownLatch mine = releaseWorkers[i];
            executors.workers().execute(() -> {
                try {
                    mine.await();
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread.sleep(50);

        try {
            for (int i = 0; i < cap; i++) {
                predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 0, 100 + i, 100));
            }
            // The urgent menu target sits at the viewport's far corner, so center-out ordering
            // alone would compose it last; the queue-jump tier must still dispatch it first.
            predictionTiles.setViewport(DIM, 0, 0, 4, 0, 4);
            final TileKey center = new TileKey(WORLD, DIM, "surface!pred", 0, 2, 2);
            final TileKey urgent = new TileKey(WORLD, DIM, "surface!pred", 0, 4, 4);
            predictionTiles.requestTile(center);
            predictionTiles.requestTileUrgently(urgent);

            releaseWorkers[0].countDown();
            awaitIdle(predictionTiles, 10_000L);
            final List<TileKey> order = uploads.drainUploads(64).stream()
                .map(TileUpdate::key)
                .toList();
            final int urgentAt = order.indexOf(urgent);
            final int centerAt = order.indexOf(center);
            assertTrue(urgentAt >= 0 && centerAt >= 0, "every requested tile must compose");
            assertTrue(
                urgentAt < centerAt,
                "the urgent menu target must compose before viewport tiles nearer the viewport center"
            );
        } finally {
            for (final CountDownLatch release : releaseWorkers) {
                release.countDown();
            }
            executors.shutdown(2000);
        }
    }

    @Test
    void surfaceLookupDistinguishesUncomposedTilesAndQueuesThemUrgently(@TempDir final Path tempDir) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        sessionGuard.begin(WORLD, DIM);
        final TileKey key = new TileKey(WORLD, DIM, "surface!pred", 0, 0, 0);

        final CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < executors.workerCount(); i++) {
            executors.workers().execute(() -> {
                try {
                    release.await();
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        Thread.sleep(50);

        try {
            // Occupy every composition slot so the observed request stays queued in `dirty`.
            for (int i = 0; i < executors.workerCount(); i++) {
                predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 0, 100 + i, 100));
            }
            final ColumnStore.SurfaceLookup before = predictionTiles.predictedSurfaceAt(DIM, 0, 5, 5);
            assertFalse(before.known(), "an uncomposed tile must read as unknown, not as void");
            assertTrue(
                predictionTiles.pendingKeysForTest().contains(key),
                "the missing menu-target tile must be queued for composition"
            );

            release.countDown();
            awaitIdle(predictionTiles, 10_000L);
            final ColumnStore.SurfaceLookup after = predictionTiles.predictedSurfaceAt(DIM, 0, 5, 5);
            assertTrue(after.known());
            assertEquals(63, after.surfaceY().orElseThrow());
        } finally {
            release.countDown();
            executors.shutdown(2000);
        }
    }

    /**
     * A synchronized void correction must surface as "known, no surface" for teleport staging.
     * The wire surfaceY of a void sample is the short-domain NO_SURFACE sentinel, which the
     * metadata lookup compares against BaselineGrid.NO_SURFACE - unmapped, the column would
     * masquerade as a teleport target at a fabricated height.
     */
    @Test
    void synchronizedVoidSurfacesAsKnownNoSurface(@TempDir final Path tempDir) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel());
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        final CorrectionStore corrections = new CorrectionStore(tempDir);
        final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
        Arrays.fill(evaluated, (byte) 0xFF);
        corrections.apply(
            new CorrectionStore.Key(DIM.toString(), 0, 0, 0),
            1L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            new PatchCodec.Patch(evaluated, List.of(
                new PatchCodec.Sample(
                    0, 1, ChunkSnapshot.NO_SURFACE, SurfaceKind.VOID.ordinal(), Proto.MAP_COLOR_NONE, 0
                )
            )),
            Proto.PATCH_MODE_RESIDUAL,
            PredictorVersion.full(),
            1_000L
        );
        predictionTiles.bindCorrectionStore(corrections);
        sessionGuard.begin(WORLD, DIM);

        try {
            predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 0, 0, 0));
            awaitIdle(predictionTiles, 10_000L);

            final ColumnStore.SurfaceLookup lookup = predictionTiles.predictedSurfaceAt(DIM, 0, 0, 0);
            assertTrue(lookup.known(), "a synchronized void column is known terrain state");
            assertFalse(lookup.surfaceY().isPresent(), "it must not offer a fabricated teleport Y");

            final ColumnStore.SurfaceLookup neighbor = predictionTiles.predictedSurfaceAt(DIM, 0, 1, 0);
            assertTrue(neighbor.surfaceY().isPresent(), "uncorrected columns keep the baseline height");
            assertEquals(63, neighbor.surfaceY().orElseThrow());
        } finally {
            executors.shutdown(2000);
        }
    }

    /**
     * A tile with no server differential must not allocate the 512 KiB revision plane, and a
     * parent aggregated purely from such children must still compose and upload. This is the
     * end-to-end guard for the null-revision form: an over-strict tile invariant would drop the
     * parent tile silently inside the worker.
     */
    @Test
    void purePredictionLodOneParentUploadsFromUncorrectedChildren(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        sessionGuard.begin(WORLD, DIM);

        try {
            for (int childZ = 0; childZ < 2; childZ++) {
                for (int childX = 0; childX < 2; childX++) {
                    predictionTiles.requestTile(
                        new TileKey(WORLD, DIM, "surface!pred", 0, childX, childZ)
                    );
                }
            }
            awaitIdle(predictionTiles, 10_000L);
            uploads.drainUploads(16);

            final TileKey parent = new TileKey(WORLD, DIM, "surface!pred", 1, 0, 0);
            predictionTiles.requestTile(parent);
            awaitIdle(predictionTiles, 10_000L);

            assertTrue(
                uploads.drainUploads(16).stream().anyMatch(update -> update.key().equals(parent)),
                "a parent built from uncorrected children must still upload"
            );
            assertEquals(63, predictionTiles.predictedSurfaceYAt(DIM, 1, 0, 0).orElseThrow());
        } finally {
            executors.shutdown(2000);
        }
    }

    /**
     * The same void sentinel contract as {@link #synchronizedVoidSurfacesAsKnownNoSurface}, but
     * reached through the mip aggregate: four void children must aggregate to "known, no
     * surface", not to a fabricated height (a narrowing cast of the int sentinel lands on Y=0,
     * which is a plausible sea-level target).
     */
    @Test
    void voidSurfacesSurviveMipAggregationAsKnownNoSurface(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 63, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        final CorrectionStore corrections = new CorrectionStore(tempDir);
        final long validatedAt = System.currentTimeMillis();
        for (int childZ = 0; childZ < 2; childZ++) {
            for (int childX = 0; childX < 2; childX++) {
                final byte[] evaluated = new byte[PatchCodec.MASK_BYTES];
                final List<PatchCodec.Sample> samples = new java.util.ArrayList<>();
                for (final int pixel : new int[] {0, 1, 256, 257}) {
                    evaluated[pixel >>> 3] |= (byte) (1 << (pixel & 7));
                    samples.add(new PatchCodec.Sample(
                        pixel, 1, ChunkSnapshot.NO_SURFACE,
                        SurfaceKind.VOID.ordinal(), Proto.MAP_COLOR_NONE, 0
                    ));
                }
                corrections.apply(
                    new CorrectionStore.Key(DIM.toString(), 0, childX, childZ),
                    1L,
                    new byte[Proto.PATCH_PRESENCE_BYTES],
                    new PatchCodec.Patch(evaluated, samples),
                    Proto.PATCH_MODE_RESIDUAL,
                    PredictorVersion.full(),
                    validatedAt
                );
            }
        }
        predictionTiles.bindCorrectionStore(corrections);
        sessionGuard.begin(WORLD, DIM);

        try {
            for (int childZ = 0; childZ < 2; childZ++) {
                for (int childX = 0; childX < 2; childX++) {
                    predictionTiles.requestTile(
                        new TileKey(WORLD, DIM, "surface!pred", 0, childX, childZ)
                    );
                }
            }
            awaitIdle(predictionTiles, 10_000L);
            uploads.drainUploads(16);

            final TileKey parent = new TileKey(WORLD, DIM, "surface!pred", 1, 0, 0);
            predictionTiles.requestTile(parent);
            awaitIdle(predictionTiles, 10_000L);

            final ColumnStore.SurfaceLookup voidColumn = predictionTiles.predictedSurfaceAt(DIM, 1, 0, 0);
            assertTrue(voidColumn.known(), "the aggregated void column is known terrain state");
            assertFalse(
                voidColumn.surfaceY().isPresent(),
                "aggregation must not turn a void sentinel into a fabricated teleport Y"
            );

            final ColumnStore.SurfaceLookup landColumn = predictionTiles.predictedSurfaceAt(DIM, 1, 2, 0);
            assertEquals(63, landColumn.surfaceY().orElseThrow(),
                "unsynchronized neighbours keep the baseline height");
        } finally {
            executors.shutdown(2000);
        }
    }

    /**
     * A tile the renderer asks for again (its texture was evicted, the LOD was left and re-entered,
     * or the view mode came back) must be served from its own previous composition: the pixels are
     * already identical to what sampling would produce, so paying for the native pass again is pure
     * pan latency. Pixel-plane identity is the observable proof that no second composition ran.
     */
    @Test
    void reRequestingAComposedTileReusesItsOwnCachedComposition(
        @TempDir final Path tempDir
    ) throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        final MapExecutors executors = new MapExecutors();
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.FLAT, WorldPreset.DEFAULT);
        state.setFlatBaseline(new FlatBaseline(1, 7, SurfaceKind.LAND.ordinal(), 11, 0));
        final PredictionTileService predictionTiles = newService(sessionGuard, state, executors, uploads);
        predictionTiles.bindCorrectionStore(new CorrectionStore(tempDir));
        sessionGuard.begin(WORLD, DIM);

        final TileKey key = new TileKey(WORLD, DIM, "surface!pred", 0, 3, 4);
        try {
            predictionTiles.requestTile(key);
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate first = latestUpdate(uploads, key);

            predictionTiles.requestTile(key);
            awaitIdle(predictionTiles, 10_000L);
            final TileUpdate second = latestUpdate(uploads, key);

            assertSame(
                first.argbPixels(),
                second.argbPixels(),
                "an exact cache hit must hand back the composed plane instead of sampling again"
            );
            assertEquals(
                first.argbPixels()[0],
                second.argbPixels()[0],
                "the reused plane must be the pixels that were already on screen"
            );
            assertEquals(
                7,
                predictionTiles.predictedSurfaceYAt(DIM, 0, 3 * 256 + 8, 4 * 256 + 8).orElseThrow(),
                "the reused composition must still publish its surface metadata"
            );
            assertEquals(
                1,
                predictionTiles.predictedBiomeAt(DIM, 0, 3 * 256 + 8, 4 * 256 + 8).orElse(-1),
                "the reused composition must still publish its biome metadata"
            );
        } finally {
            executors.shutdown(2000);
        }
    }

    private static TileUpdate latestUpdate(final TileService uploads, final TileKey key) {
        return uploads.drainUploads(64).stream()
            .filter(update -> update.key().equals(key))
            .reduce((first, second) -> second)
            .orElseThrow();
    }

    private static void awaitIdle(final PredictionTileService service) throws InterruptedException {
        awaitIdle(service, 2000L);
    }

    private static void awaitIdle(final PredictionTileService service, final long timeoutMillis) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!service.isIdleForTest() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(service.isIdleForTest(), "prediction tile service never drained its queue in time");
    }
}
