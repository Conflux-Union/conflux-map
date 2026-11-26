package cn.net.rms.confluxmap.gametest;

//#if MC>=12104
//$$ import cn.net.rms.confluxmap.ConfluxMapClient;
//$$ import cn.net.rms.confluxmap.ConfluxMapMod;
//$$ import cn.net.rms.confluxmap.compat.Keys;
//$$ import cn.net.rms.confluxmap.compat.MinecraftAccess;
//$$ import cn.net.rms.confluxmap.core.config.ConfluxConfig;
//$$ import cn.net.rms.confluxmap.core.model.TileKey;
//$$ import cn.net.rms.confluxmap.core.predict.PredictedTileKeys;
//$$ import cn.net.rms.confluxmap.core.predict.PredictionTileService;
//$$ import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
//$$ import cn.net.rms.confluxmap.mc.render.TileTextureManager;
//$$ import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
//$$ import java.util.HashSet;
//$$ import java.util.Set;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//$$ import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
//#endif

/**
 * Regression test for the fullscreen-map tile blink: {@link TileTextureManager}'s over-limit
 * eviction must never drop a tile the map is currently drawing. Phase A draws fewer tiles than
 * the default cache limit (the ordinary view - nothing may be evicted). Phase B sets the limit
 * below the drawn set, phase C keeps the default limit but grows the drawn set past it (GUI
 * scale 1 on the 854x480 test window); in both, every drawn tile has to stay resident instead
 * of thrashing through recompose-upload-evict cycles.
 */
//#if MC>=12104
//$$ @SuppressWarnings("UnstableApiUsage")
//$$ public final class TileTextureEvictionGuardGameTest implements FabricClientGameTest {
//$$     private static final int WORLD_TICKS = 600;
//$$     private static final int LOAD_TICKS = 1200;
//$$     private static final int SAMPLES = 60;
//$$     /** Zoom (blocks per GUI pixel) pinned in every phase; ULTRA_QUALITY keeps LOD 1 on screen. */
//$$     private static final double MAP_SCALE = 12.4;
//$$
//$$     /** Point-in-time view of GPU-resident textures and real tiles tracked as empty without textures. */
//$$     private record Snapshot(Set<TileKey> resident, Set<TileKey> emptyTracked) {
//$$         boolean covers(final TileKey key) {
//$$             return resident.contains(key) || emptyTracked.contains(key);
//$$         }
//$$
//$$         boolean coversAll(final Set<TileKey> visible) {
//$$             for (final TileKey key : visible) {
//$$                 if (!covers(key)) {
//$$                     return false;
//$$                 }
//$$             }
//$$             return true;
//$$         }
//$$
//$$         int countResident(final Set<TileKey> visible) {
//$$             int count = 0;
//$$             for (final TileKey key : visible) {
//$$                 if (resident.contains(key)) {
//$$                     count++;
//$$                 }
//$$             }
//$$             return count;
//$$         }
//$$
//$$         int countEmptyTracked(final Set<TileKey> visible) {
//$$             int count = 0;
//$$             for (final TileKey key : visible) {
//$$                 if (emptyTracked.contains(key)) {
//$$                     count++;
//$$                 }
//$$             }
//$$             return count;
//$$         }
//$$     }
//$$
//$$     /** One phase's residency observation: GPU evictions, unexpected empty-tracking losses, and bounds. */
//$$     private record Sample(
//$$         int samples,
//$$         int visibleEvicted,
//$$         int emptyLost,
//$$         int samplesWithVisibleLoss,
//$$         int minVisibleResident,
//$$         int maxVisibleResident,
//$$         int visibleResidentAtEnd,
//$$         int visibleEmptyAtEnd,
//$$         int cacheResidentAtEnd,
//$$         Snapshot endSnapshot
//$$     ) {
//$$     }
//$$
//$$     @Override
//$$     public void runTest(final ClientGameTestContext context) {
//$$         try (
//$$             TestSingleplayerContext world = context.worldBuilder()
//$$                 .setUseConsistentSettings(false)
//$$                 .adjustSettings(creator -> {
//$$                     creator.setSeed("146008555");
//$$                     creator.setWorldType(creator.getNormalWorldTypes().get(0));
//$$                 })
//$$                 .create()
//$$         ) {
//$$             runTest(context, world);
//$$         }
//$$     }
//$$
//$$     private static void runTest(
//$$         final ClientGameTestContext context, final TestSingleplayerContext world
//$$     ) {
//$$         final ConfluxMapClient client = ConfluxMapClient.get();
//$$         client.config().predictionEnabled = true;
//$$         client.config().predictionViewMode = PredictionViewMode.EVERYWHERE;
//$$         client.config().minimapEnabled = false;
//$$         // The smallest tiles a user can reach: ULTRA_QUALITY at this zoom keeps LOD 1 on
//$$         // screen at ~41 GUI pixels per tile, so even the small test window shows many tiles.
//$$         client.config().mapQuality = ConfluxConfig.MapQuality.ULTRA_QUALITY;
//$$         final PredictionTileService prediction = client.predictionTileService();
//$$         final TileTextureManager textures = client.tileTextureManager();
//$$         context.waitFor(waitClient -> waitClient.world != null, WORLD_TICKS);
//$$         context.waitFor(waitClient -> MinecraftAccess.screen(waitClient) == null, WORLD_TICKS);
//$$         context.waitTicks(20);
//$$         // Freeze random ticks so real-tile recompositions are never mistaken for churn.
//$$         world.getServer().runCommand("gamerule randomTickSpeed 0");
//$$
//$$         openMap(context);
//$$         final Set<TileKey> visible = visibleKeys(prediction);
//$$         closeMap(context);
//$$         final int defaultLimit = client.config().gpuTileCacheLimit;
//$$
//$$         // A: the ordinary case - drawn set fits under the default limit.
//$$         resetCache(context, client, textures, defaultLimit);
//$$         openMap(context);
//$$         final boolean aLoaded = awaitVisibleCovered(context, visible, textures);
//$$         final Sample a = sample(context, visible, textures);
//$$         assertPhase("A default limit", a, visible, aLoaded, defaultLimit);
//$$         closeMap(context);
//$$
//$$         // Half the actual visible GPU-resident set from phase A, never below the smallest config limit (16).
//$$         final int smallLimit = Math.max(16, a.visibleResidentAtEnd() / 2);
//$$         ConfluxMapMod.LOGGER.info(
//$$             "[texture-eviction-guard] drawn={} visibleResident={} visibleEmpty={} defaultLimit={} smallLimit={}",
//$$             visible.size(), a.visibleResidentAtEnd(), a.visibleEmptyAtEnd(), defaultLimit, smallLimit
//$$         );
//$$
//$$         // B: limit pushed below the visible GPU-resident set from a cold cache.
//$$         resetCache(context, client, textures, smallLimit);
//$$         openMap(context);
//$$         final boolean bFull = awaitVisibleCoveredAndCacheAtLeast(context, visible, textures, smallLimit);
//$$         final Sample b = sample(context, visible, textures);
//$$         assertPhase("B under limit", b, visible, bFull, smallLimit);
//$$         closeMap(context);
//$$
//$$         // C: default limit with a drawn set that exceeds it for real - GUI scale 1 on the
//$$         // 854x480 test window quadruples the GUI-space area.
//$$         context.runOnClient(minecraft -> {
//$$             final var options = minecraft.options;
//$$             options.getGuiScale().setValue(1);
            //#if MC>=260100
//$$             minecraft.resizeGui();
            //#else
//$$             minecraft.onResolutionChanged();
            //#endif
//$$         });
//$$         resetCache(context, client, textures, defaultLimit);
//$$         openMap(context);
//$$         final Set<TileKey> wideVisible = visibleKeys(prediction);
//$$         // Wait for full visible coverage and over-limit cache population: on slow software-GL
//$$         // machines a premature start blames the eviction guard for tiles not yet uploaded.
//$$         final boolean cFull = awaitVisibleCoveredAndCacheAtLeast(context, wideVisible, textures, defaultLimit);
//$$         final Sample c = sample(context, wideVisible, textures);
//$$         assertPhase("C default limit over", c, wideVisible, cFull, defaultLimit);
//$$         closeMap(context);
//$$     }
//$$
//$$     /** Fails the test unless every drawn key stayed covered (GPU-resident or monotonically empty-tracked). */
//$$     private static void assertPhase(
//$$         final String phase,
//$$         final Sample sample,
//$$         final Set<TileKey> visible,
//$$         final boolean preconditionReached,
//$$         final int limit
//$$     ) {
//$$         if (!preconditionReached) {
//$$             throw new AssertionError(
//$$                 phase + ": precondition not reached (drawn=" + visible.size()
//$$                     + " limit=" + limit + " visibleResidentAtEnd=" + sample.visibleResidentAtEnd()
//$$                     + " visibleEmptyAtEnd=" + sample.visibleEmptyAtEnd()
//$$                     + " cacheResidentAtEnd=" + sample.cacheResidentAtEnd() + ")"
//$$             );
//$$         }
//$$         if (sample.visibleEvicted() > 0) {
//$$             throw new AssertionError(
//$$                 phase + ": " + sample.visibleEvicted() + " drawn GPU tiles left resident cache across "
//$$                     + sample.samplesWithVisibleLoss() + " of " + sample.samples()
//$$                     + " samples (drawn=" + visible.size() + " limit=" + limit
//$$                     + " residentRange=[" + sample.minVisibleResident() + ".."
//$$                     + sample.maxVisibleResident() + "])"
//$$             );
//$$         }
//$$         if (sample.emptyLost() > 0) {
//$$             throw new AssertionError(
//$$                 phase + ": " + sample.emptyLost()
//$$                     + " empty-tracked visible tiles disappeared without promoting to GPU across "
//$$                     + sample.samplesWithVisibleLoss() + " of " + sample.samples()
//$$                     + " samples (drawn=" + visible.size() + " limit=" + limit + ")"
//$$             );
//$$         }
//$$         if (!sample.endSnapshot().coversAll(visible)) {
//$$             throw new AssertionError(
//$$                 phase + ": drawn tiles missing from coverage after the sample window (drawn="
//$$                     + visible.size() + " limit=" + limit
//$$                     + " visibleResidentAtEnd=" + sample.visibleResidentAtEnd()
//$$                     + " visibleEmptyAtEnd=" + sample.visibleEmptyAtEnd() + ")"
//$$             );
//$$         }
//$$     }
//$$
//$$     /** Empties the texture cache the way an eviction does, then applies the phase's cache limit. */
//$$     private static void resetCache(
//$$         final ClientGameTestContext context,
//$$         final ConfluxMapClient client,
//$$         final TileTextureManager textures,
//$$         final int limit
//$$     ) {
//$$         context.runOnClient(minecraft -> {
//$$             textures.releaseAll();
//$$             client.config().gpuTileCacheLimit = limit;
//$$         });
//$$         context.waitTicks(5);
//$$     }
//$$
//$$     private static void openMap(final ClientGameTestContext context) {
//$$         context.getInput().pressKey(Keys.M);
//$$         context.waitForScreen(FullscreenMapScreen.class);
//$$         context.runOnClient(minecraft -> {
//$$             if (MinecraftAccess.screen(minecraft) instanceof FullscreenMapScreen map) {
//$$                 map.setScaleForTest(MAP_SCALE);
//$$             }
//$$         });
//$$         // One frame publishes the zoomed viewport, then let the open transition settle.
//$$         context.waitTicks(20);
//$$     }
//$$
//$$     private static void closeMap(final ClientGameTestContext context) {
//$$         context.runOnClient(minecraft -> MinecraftAccess.setScreen(minecraft, null));
//$$         context.waitFor(waitClient -> MinecraftAccess.screen(waitClient) == null, 200);
//$$         context.waitTicks(10);
//$$     }
//$$
//$$     /** The tiles the fullscreen map binds every frame: each drawn real tile and its predicted twin. */
//$$     private static Set<TileKey> visibleKeys(final PredictionTileService prediction) {
//$$         final Set<TileKey> keys = new HashSet<>();
//$$         for (final TileKey predicted : prediction.viewportTilesForTest()) {
//$$             keys.add(predicted);
//$$             keys.add(new TileKey(
//$$                 predicted.world(), predicted.dimension(),
//$$                 PredictedTileKeys.realLayerId(predicted.layerId()),
//$$                 predicted.lod(), predicted.tileX(), predicted.tileZ()
//$$             ));
//$$         }
//$$         return keys;
//$$     }
//$$
//$$     private static Snapshot captureSnapshot(final TileTextureManager textures) {
//$$         return new Snapshot(textures.residentTexturesForTest(), textures.emptyTrackedTilesForTest());
//$$     }
//$$
//$$     private static boolean awaitVisibleCovered(
//$$         final ClientGameTestContext context,
//$$         final Set<TileKey> visible,
//$$         final TileTextureManager textures
//$$     ) {
//$$         for (int waited = 0; waited < LOAD_TICKS; waited++) {
//$$             if (context.computeOnClient(minecraft -> captureSnapshot(textures).coversAll(visible))) {
//$$                 return true;
//$$             }
//$$             context.waitTick();
//$$         }
//$$         return false;
//$$     }
//$$
//$$     private static boolean awaitVisibleCoveredAndCacheAtLeast(
//$$         final ClientGameTestContext context,
//$$         final Set<TileKey> visible,
//$$         final TileTextureManager textures,
//$$         final int minResidentCache
//$$     ) {
//$$         for (int waited = 0; waited < LOAD_TICKS; waited++) {
//$$             if (context.computeOnClient(minecraft -> {
//$$                 final Snapshot snapshot = captureSnapshot(textures);
//$$                 return snapshot.resident().size() >= minResidentCache && snapshot.coversAll(visible);
//$$             })) {
//$$                 return true;
//$$             }
//$$             context.waitTick();
//$$         }
//$$         return false;
//$$     }
//$$
//$$     /**
//$$      * Once per tick: which drawn GPU textures left resident cache, and whether any empty-tracked
//$$      * real tile disappeared without promoting into resident GPU cache.
//$$      */
//$$     private static Sample sample(
//$$         final ClientGameTestContext context,
//$$         final Set<TileKey> visible,
//$$         final TileTextureManager textures
//$$     ) {
//$$         final Snapshot[] previous = {
//$$             context.computeOnClient(minecraft -> captureSnapshot(textures))
//$$         };
//$$         final int[] taken = {0};
//$$         final int[] visibleEvicted = {0};
//$$         final int[] emptyLost = {0};
//$$         final int[] samplesWithVisibleLoss = {0};
//$$         final int[] minResident = {Integer.MAX_VALUE};
//$$         final int[] maxResident = {Integer.MIN_VALUE};
//$$         context.waitFor(waitClient -> {
//$$             final Snapshot current = captureSnapshot(textures);
//$$             int lostThisTick = 0;
//$$             for (final TileKey key : previous[0].resident()) {
//$$                 if (!current.resident().contains(key) && visible.contains(key)) {
//$$                     visibleEvicted[0]++;
//$$                     lostThisTick++;
//$$                 }
//$$             }
//$$             for (final TileKey key : previous[0].emptyTracked()) {
//$$                 if (visible.contains(key) && !current.covers(key)) {
//$$                     emptyLost[0]++;
//$$                     lostThisTick++;
//$$                 }
//$$             }
//$$             if (lostThisTick > 0) {
//$$                 samplesWithVisibleLoss[0]++;
//$$             }
//$$             final int visibleResident = current.countResident(visible);
//$$             minResident[0] = Math.min(minResident[0], visibleResident);
//$$             maxResident[0] = Math.max(maxResident[0], visibleResident);
//$$             previous[0] = current;
//$$             return ++taken[0] >= SAMPLES;
//$$         }, SAMPLES + 40);
//$$         final Snapshot end = previous[0];
//$$         return new Sample(
//$$             taken[0],
//$$             visibleEvicted[0],
//$$             emptyLost[0],
//$$             samplesWithVisibleLoss[0],
//$$             minResident[0],
//$$             maxResident[0],
//$$             end.countResident(visible),
//$$             end.countEmptyTracked(visible),
//$$             end.resident().size(),
//$$             end
//$$         );
//$$     }
//$$ }
//#else
public final class TileTextureEvictionGuardGameTest {
    private TileTextureEvictionGuardGameTest() {}
}
//#endif
