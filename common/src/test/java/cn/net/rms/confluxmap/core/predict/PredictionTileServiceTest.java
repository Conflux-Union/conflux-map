package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.BiomeColorPalette;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.LightTint;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
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
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
    void composedPredictionExposesItsBiomeForCursorReadout(@TempDir final Path tempDir) throws InterruptedException {
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
            predictionTiles.requestTile(new TileKey(WORLD, DIM, "surface!pred", 2, 0, 0));
            awaitIdle(predictionTiles, 10_000L);

            assertEquals(35, predictionTiles.predictedBiomeAt(DIM, 2, 0, 0).orElse(-1));
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
