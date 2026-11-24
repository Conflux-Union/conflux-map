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
//$$     /** One phase's residency observation: what left the cache, and how visible residency moved. */
//$$     private record Sample(
//$$         int samples,
//$$         int visibleEvicted,
//$$         int samplesWithVisibleEviction,
//$$         int minVisibleResident,
//$$         int maxVisibleResident,
//$$         int residentAtEnd
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
//$$         // Half the drawn set, never below the smallest value the config accepts (16).
//$$         final int smallLimit = Math.max(16, visible.size() / 2);
//$$         ConfluxMapMod.LOGGER.info(
//$$             "[texture-eviction-guard] drawn={} defaultLimit={} smallLimit={}",
//$$             visible.size(), defaultLimit, smallLimit
//$$         );
//$$
//$$         // A: the ordinary case - drawn set fits under the default limit.
//$$         resetCache(context, client, textures, defaultLimit);
//$$         openMap(context);
//$$         final boolean aLoaded = awaitFullResidency(context, visible, textures);
//$$         final Sample a = sample(context, visible, textures);
//$$         assertPhase("A default limit", a, visible, textures, aLoaded, defaultLimit);
//$$         closeMap(context);
//$$
//$$         // B: limit pushed below the drawn set from a cold cache.
//$$         resetCache(context, client, textures, smallLimit);
//$$         openMap(context);
//$$         final boolean bFull = awaitCacheFull(context, textures, smallLimit);
//$$         final Sample b = sample(context, visible, textures);
//$$         assertPhase("B under limit", b, visible, textures, bFull, smallLimit);
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
//$$         final boolean cFull = awaitCacheFull(context, textures, defaultLimit);
//$$         final Sample c = sample(context, wideVisible, textures);
//$$         assertPhase("C default limit over", c, wideVisible, textures, cFull, defaultLimit);
//$$         closeMap(context);
//$$     }
//$$
//$$     /** Fails the test unless the phase kept every drawn tile resident for the whole window. */
//$$     private static void assertPhase(
//$$         final String phase,
//$$         final Sample sample,
//$$         final Set<TileKey> visible,
//$$         final TileTextureManager textures,
//$$         final boolean preconditionReached,
//$$         final int limit
//$$     ) {
//$$         if (!preconditionReached) {
//$$             throw new AssertionError(
//$$                 phase + ": precondition not reached (drawn=" + visible.size()
//$$                     + " limit=" + limit + " residentAtEnd=" + sample.residentAtEnd() + ")"
//$$             );
//$$         }
//$$         if (sample.visibleEvicted() > 0) {
//$$             throw new AssertionError(
//$$                 phase + ": " + sample.visibleEvicted() + " drawn tiles left the cache across "
//$$                     + sample.samplesWithVisibleEviction() + " of " + sample.samples()
//$$                     + " samples (drawn=" + visible.size() + " limit=" + limit
//$$                     + " residentRange=[" + sample.minVisibleResident() + ".."
//$$                     + sample.maxVisibleResident() + "])"
//$$             );
//$$         }
//$$         if (!textures.residentTexturesForTest().containsAll(visible)) {
//$$             throw new AssertionError(
//$$                 phase + ": drawn tiles missing from residency after the sample window (drawn="
//$$                     + visible.size() + " limit=" + limit + " residentAtEnd="
//$$                     + sample.residentAtEnd() + ")"
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
//$$     private static boolean awaitFullResidency(
//$$         final ClientGameTestContext context,
//$$         final Set<TileKey> visible,
//$$         final TileTextureManager textures
//$$     ) {
//$$         for (int waited = 0; waited < LOAD_TICKS; waited++) {
//$$             if (context.computeOnClient(minecraft -> textures.residentTexturesForTest().containsAll(visible))) {
//$$                 return true;
//$$             }
//$$             context.waitTick();
//$$         }
//$$         return false;
//$$     }
//$$
//$$     private static boolean awaitCacheFull(
//$$         final ClientGameTestContext context,
//$$         final TileTextureManager textures,
//$$         final int limit
//$$     ) {
//$$         for (int waited = 0; waited < LOAD_TICKS; waited++) {
//$$             if (context.computeOnClient(minecraft -> textures.residentTexturesForTest().size() >= limit)) {
//$$                 return true;
//$$             }
//$$             context.waitTick();
//$$         }
//$$         return false;
//$$     }
//$$
//$$     /** Once per tick: which drawn keys left since the previous sample, and drawn residency bounds. */
//$$     private static Sample sample(
//$$         final ClientGameTestContext context,
//$$         final Set<TileKey> visible,
//$$         final TileTextureManager textures
//$$     ) {
//$$         final Set<TileKey> previous = new HashSet<>(textures.residentTexturesForTest());
//$$         final int[] taken = {0};
//$$         final int[] visibleEvicted = {0};
//$$         final int[] samplesWithVisibleEviction = {0};
//$$         final int[] leftThisTick = {0};
//$$         final int[] minResident = {Integer.MAX_VALUE};
//$$         final int[] maxResident = {Integer.MIN_VALUE};
//$$         context.waitFor(waitClient -> {
//$$             final Set<TileKey> resident = textures.residentTexturesForTest();
//$$             leftThisTick[0] = 0;
//$$             for (final TileKey key : previous) {
//$$                 if (!resident.contains(key) && visible.contains(key)) {
//$$                     visibleEvicted[0]++;
//$$                     leftThisTick[0]++;
//$$                 }
//$$             }
//$$             if (leftThisTick[0] > 0) {
//$$                 samplesWithVisibleEviction[0]++;
//$$             }
//$$             int visibleResident = 0;
//$$             for (final TileKey key : visible) {
//$$                 if (resident.contains(key)) {
//$$                     visibleResident++;
//$$                 }
//$$             }
//$$             minResident[0] = Math.min(minResident[0], visibleResident);
//$$             maxResident[0] = Math.max(maxResident[0], visibleResident);
//$$             previous.clear();
//$$             previous.addAll(resident);
//$$             return ++taken[0] >= SAMPLES;
//$$         }, SAMPLES + 40);
//$$         return new Sample(
//$$             taken[0], visibleEvicted[0], samplesWithVisibleEviction[0],
//$$             minResident[0], maxResident[0], previous.size()
//$$         );
//$$     }
//$$ }
//#else
public final class TileTextureEvictionGuardGameTest {
    private TileTextureEvictionGuardGameTest() {}
}
//#endif
