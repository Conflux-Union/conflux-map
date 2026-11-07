package cn.net.rms.confluxmap.gametest;

//#if MC>=12104
//$$ import cn.net.rms.confluxmap.ConfluxMapClient;
//$$ import cn.net.rms.confluxmap.ConfluxMapMod;
//$$ import cn.net.rms.confluxmap.compat.Keys;
//$$ import cn.net.rms.confluxmap.compat.MinecraftAccess;
//$$ import cn.net.rms.confluxmap.core.config.ConfluxConfig;
//$$ import cn.net.rms.confluxmap.core.model.TileKey;
//$$ import cn.net.rms.confluxmap.core.predict.PredictionTileService;
//$$ import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
//$$ import cn.net.rms.confluxmap.mc.render.TileTextureManager;
//$$ import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
//$$ import cn.net.rms.confluxmap.nativepredict.GpuBackend;
//$$ import cn.net.rms.confluxmap.nativepredict.NativeLib;
//$$ import cn.net.rms.confluxmap.nativepredict.PredictionBackend;
//$$ import java.util.Set;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
//#endif

/**
 * Drives the prediction user path end to end on a real client: open the fullscreen map over a
 * fresh singleplayer world, wait until every visible predicted tile is composed and uploaded to
 * a resident GPU texture, and assert exactly that, plus that the viewport load window closed
 * complete (the {@code [predict-e2e]} aggregate's own done condition). Stage timings are
 * emitted to the client log through the debug-timing gate ({@code [predict-e2e]} /
 * {@code [predict-timing]} / {@code [predict-backend]} lines) but never asserted - CI machines
 * are too noisy for performance claims (see the lavapipe flake history); only a generous
 * wall-clock smoke bound guards against hangs. The native library must be loadable; without
 * it the test reports and returns (the native assumptions precedent). Runs on the CPU
 * fallback where OpenCL is missing; GPU-vs-CPU comparison belongs on a GPU machine reading
 * the logged backend line.
 */
//#if MC>=12104
//$$ @SuppressWarnings("UnstableApiUsage")
//$$ public final class PredictionE2EClientGameTest implements FabricClientGameTest {
//$$     /** Hard stop for the upload wait; the framework fails the test past it. */
//$$     private static final int MAX_UPLOAD_TICKS = 1200;
//$$     /**
//$$      * Matrix rounds on the CPU backend resample the refresh churn at full sampling cost, so
//$$      * their wait gets a much longer leash than the CI smoke round needs.
//$$      */
//$$     private static final int MATRIX_UPLOAD_TICKS = 6000;
//$$     /** Loose smoke bound only: anything past this smells like a hang, not a regression. */
//$$     private static final long SMOKE_WALL_LIMIT_MS = 30_000L;
//$$     private static final String TIMING_FLAG = "confluxmap.debugPredictionTiming";
//$$
//$$     @Override
//$$     public void runTest(final ClientGameTestContext context) {
//$$         if (Boolean.getBoolean("confluxmap.xaero.oracle")) {
//$$             return;
//$$         }
//$$         try (
//$$             TestSingleplayerContext world = context.worldBuilder()
//$$                 // A throwaway save so the NORMAL world type set here never leaks into
//$$                 // the consistent-settings world the other gametests share.
//$$                 .setUseConsistentSettings(false)
//$$                 .adjustSettings(creator -> creator.setWorldType(
//$$                     // The normal tag's first entry is vanilla "normal" (then flat, ...);
//$$                     // WorldType carries no public constant for it on these versions.
//$$                     creator.getNormalWorldTypes().get(0)))
//$$                 .create()
//$$         ) {
//$$             final boolean timingWasOn = Boolean.getBoolean(TIMING_FLAG);
//$$             System.setProperty(TIMING_FLAG, "true");
//$$             try {
//$$                 runViewportLoad(context, world);
//$$             } finally {
//$$                 if (!timingWasOn) {
//$$                     System.clearProperty(TIMING_FLAG);
//$$                 }
//$$             }
//$$         }
//$$     }
//$$
//$$     private static void runViewportLoad(final ClientGameTestContext context, final TestSingleplayerContext world) {
//$$         final ConfluxMapClient client = ConfluxMapClient.get();
//$$         client.config().predictionEnabled = true;
//$$         client.config().predictionViewMode = PredictionViewMode.EVERYWHERE;
//$$         // The minimap otherwise pre-composes every prediction tile around spawn before the
//$$         // fullscreen map opens; disabling it keeps this test's viewport load cold, so the
//$$         // stage breakdown in the [predict-e2e] line shows real native sampling.
//$$         client.config().minimapEnabled = false;
//$$         // Slope 1.0 makes scale 2^lod map to exactly LOD lod, which the matrix rounds pin.
//$$         client.config().mapQuality = ConfluxConfig.MapQuality.BALANCED;
//$$         applyBackendOverride(client);
//$$         final PredictionTileService prediction = client.predictionTileService();
//$$         final TileTextureManager textures = client.tileTextureManager();
//$$         context.waitFor(waitClient -> waitClient.world != null && NativeLib.available(), 600);
//$$         if (!NativeLib.available()) {
//$$             ConfluxMapMod.LOGGER.info(
//$$                 "[predict-e2e-gametest] native predictor unavailable on this platform; skipped"
//$$             );
//$$             return;
//$$         }
//$$         // Let the world join settle: a key pressed while the loading overlay is up is
//$$         // swallowed by the screen and never reaches the open-map keybind.
//$$         context.waitFor(waitClient -> MinecraftAccess.screen(waitClient) == null, 600);
//$$         context.waitTicks(10);
//$$         logBackend("start");
//$$
//$$         context.getInput().pressKey(Keys.M);
//$$         context.waitForScreen(FullscreenMapScreen.class);
//$$
//$$         final int[] lods = matrixLods();
//$$         if (lods.length == 0) {
//$$             // Default: the opening view only (scale <= 2.0), the CI smoke path.
//$$             final PredictionTileService.ViewportLoadResult load =
//$$                 waitForViewportLoad(context, prediction, textures, -1, null, MAX_UPLOAD_TICKS);
//$$             final long wallMs = load.wallNanos() / 1_000_000L;
//$$             logBackend("done tiles=" + load.tiles() + " wallMs=" + wallMs);
//$$             if (wallMs > SMOKE_WALL_LIMIT_MS) {
//$$                 throw new AssertionError(
//$$                     "viewport prediction load took " + wallMs + " ms (smoke limit "
//$$                         + SMOKE_WALL_LIMIT_MS + " ms); stage breakdown is in the"
//$$                         + " [predict-e2e] log line above"
//$$                 );
//$$             }
//$$         } else {
//$$             // Random ticks keep mutating spawn chunks every tick, which re-dirties LOD0
//$$             // prediction tiles faster than the workers drain them and buries the cold load
//$$             // under resampling churn; freeze the world before measuring.
//$$             world.getServer().runCommand("gamerule randomTickSpeed 0");
//$$             // The spawn-chunk flood keeps re-refreshing the opening view's tiles for
//$$             // several seconds after world creation; burn it off on a discard round so the
//$$             // first matrix round measures prediction, not chunk-loading noise.
//$$             waitForViewportLoad(context, prediction, textures, -1, null, MAX_UPLOAD_TICKS);
//$$             for (final int lod : lods) {
//$$                 final PredictionTileService.ViewportLoadResult previous =
//$$                     prediction.lastViewportLoadForTest();
//$$                 context.runOnClient(minecraft -> {
//$$                     if (MinecraftAccess.screen(minecraft) instanceof FullscreenMapScreen map) {
//$$                         map.setScaleForTest(Math.scalb(1.0, lod));
//$$                         prediction.reloadAll();
//$$                     }
//$$                 });
//$$                 final PredictionTileService.ViewportLoadResult load =
//$$                     waitForViewportLoad(context, prediction, textures, lod, previous, MATRIX_UPLOAD_TICKS);
//$$                 ConfluxMapMod.LOGGER.info(
//$$                     "[predict-e2e-matrix] lod={} tiles={} wallMs={}",
//$$                     lod, load.tiles(), load.wallNanos() / 1_000_000L
//$$                 );
//$$                 logBackend("lod" + lod + " done tiles=" + load.tiles());
//$$             }
//$$         }
//$$     }
//$$
//$$     /**
//$$      * Waits for one cold viewport load window: the round's LOD reported complete with every
//$$      * visible predicted tile resident on the GPU. {@code previous} is the round's starting
//$$      * {@code lastViewportLoadForTest} (null on the first round), so a summary left over from
//$$      * an earlier round never satisfies the wait; {@code lod < 0} accepts any LOD (the CI
//$$      * smoke round on the opening view).
//$$      */
//$$     private static PredictionTileService.ViewportLoadResult waitForViewportLoad(
//$$         final ClientGameTestContext context,
//$$         final PredictionTileService prediction,
//$$         final TileTextureManager textures,
//$$         final int lod,
//$$         final PredictionTileService.ViewportLoadResult previous,
//$$         final int timeoutTicks
//$$     ) {
//$$         context.waitFor(ignored -> {
//$$             final PredictionTileService.ViewportLoadResult load = prediction.lastViewportLoadForTest();
//$$             return load != null && load != previous && load.complete()
//$$                 && (lod < 0 || load.lod() == lod)
//$$                 && viewportDisplayed(prediction, textures);
//$$         }, timeoutTicks);
//$$         final PredictionTileService.ViewportLoadResult load = prediction.lastViewportLoadForTest();
//$$         if (!load.complete()) {
//$$             throw new AssertionError("viewport load window closed incomplete (viewport moved mid-load?)");
//$$         }
//$$         final int visibleTiles = prediction.viewportTilesForTest().size();
//$$         if (load.tiles() < visibleTiles) {
//$$             throw new AssertionError(
//$$                 "aggregate counted " + load.tiles() + " uploads but the viewport holds "
//$$                     + visibleTiles + " tiles"
//$$             );
//$$         }
//$$         return load;
//$$     }
//$$
//$$     /** The LOD matrix requested via {@code -Pe2eLods="0,1,2,3,4"}; empty for the CI smoke round. */
//$$     private static int[] matrixLods() {
//$$         final String raw = System.getProperty("confluxmap.e2e.lods");
//$$         if (raw == null || raw.isBlank()) {
//$$             return new int[0];
//$$         }
//$$         return java.util.Arrays.stream(raw.split(","))
//$$             .map(String::trim)
//$$             .filter(part -> !part.isEmpty())
//$$             .mapToInt(Integer::parseInt)
//$$             .toArray();
//$$     }
//$$
//$$     /** Applies {@code -Pe2eBackend=cpu|gpu} (matrix measurement only; default keeps the config value). */
//$$     private static void applyBackendOverride(final ConfluxMapClient client) {
//$$         final String raw = System.getProperty("confluxmap.e2e.backend");
//$$         if (raw == null || raw.isBlank()) {
//$$             return;
//$$         }
//$$         final PredictionBackend backend = "cpu".equalsIgnoreCase(raw.trim())
//$$             ? PredictionBackend.CPU : PredictionBackend.GPU;
//$$         client.config().predictionBackend = backend;
//$$         GpuBackend.configure(backend);
//$$     }
//$$
//$$     /** The functional assertion: every visible predicted tile has a resident GPU texture. */
//$$     private static boolean viewportDisplayed(
//$$         final PredictionTileService prediction, final TileTextureManager textures
//$$     ) {
//$$         final Set<TileKey> expected = prediction.viewportTilesForTest();
//$$         if (expected.isEmpty()) {
//$$             return false;
//$$         }
//$$         return textures.residentTexturesForTest().containsAll(expected);
//$$     }
//$$
//$$     /** The actual engine serving the run - CPU fallback on CI, OpenCL device on GPU machines. */
//$$     private static void logBackend(final String phase) {
//$$         final GpuBackend.GpuStats stats = GpuBackend.stats();
//$$         if (stats == null) {
//$$             ConfluxMapMod.LOGGER.info("[predict-e2e-gametest] {} backend=unavailable", phase);
//$$             return;
//$$         }
//$$         ConfluxMapMod.LOGGER.info(
//$$             "[predict-e2e-gametest] {} backend={} device='{}' dispatches={}",
//$$             phase,
//$$             GpuBackend.engineModeLabel(stats.engineMode()),
//$$             stats.device(),
//$$             stats.dispatches() + stats.heightDispatches()
//$$         );
//$$     }
//$$ }
//#else
public final class PredictionE2EClientGameTest {
    private PredictionE2EClientGameTest() {}
}
//#endif
