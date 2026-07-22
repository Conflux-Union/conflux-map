package cn.net.rms.confluxmap;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.core.cache.RegionCacheService;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.CorrectionStore;
import cn.net.rms.confluxmap.core.radar.RadarViewRange;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.mc.McGameBridge;
import cn.net.rms.confluxmap.mc.color.BiomeTintResolver;
import cn.net.rms.confluxmap.mc.color.ColorReloadListener;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import cn.net.rms.confluxmap.mc.input.Keybinds;
import cn.net.rms.confluxmap.mc.net.ClientNetworking;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.net.MapSyncClient;
import cn.net.rms.confluxmap.mc.predict.PredictionBootstrap;
import cn.net.rms.confluxmap.mc.predict.PredictionPaletteBuilder;
import cn.net.rms.confluxmap.mc.predict.StructureMarkerService;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.EntityIconReloadListener;
import cn.net.rms.confluxmap.mc.radar.EntityRadarScanner;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureService;
import cn.net.rms.confluxmap.mc.ui.hud.MinimapHudRenderer;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapViewState;
import cn.net.rms.confluxmap.mc.ui.world.WaypointWorldRenderer;
import cn.net.rms.confluxmap.mc.world.DeathWatcher;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import cn.net.rms.confluxmap.mc.world.McDaylightTracker;
import cn.net.rms.confluxmap.mc.world.WorldSessionTracker;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceType;

/** Composition root: builds and wires every client-side service. */
public final class ConfluxMapClient implements ClientModInitializer {
    private static ConfluxMapClient instance;

    private ConfluxConfig config;
    private ConfigIo configIo;
    private MapExecutors executors;
    private SessionGuard sessionGuard;
    private WorldSessionTracker sessionTracker;
    private GameBridge gameBridge;
    private MapWorldService mapWorlds;
    private TileService tileService;
    private RegionCacheService regionCache;
    private SpriteColorSampler spriteColorSampler;
    private BiomeTintResolver biomeTintResolver;
    private TileTextureManager tileTextureManager;
    private ChunkCaptureService chunkCapture;
    private RadarViewRange radarViewRange;
    private EntityRadarScanner radarScanner;
    private EntityIconManager entityIconManager;
    private MinimapHudRenderer minimapHudRenderer;
    private FullscreenMapViewState fullscreenMapViewState;
    private LayerSelector layerSelector;
    private WaypointService waypointService;
    private DeathWatcher deathWatcher;
    private WaypointWorldRenderer waypointWorldRenderer;
    private DaylightModel daylightModel;
    private McDaylightTracker daylightTracker;
    private PredictionState predictionState;
    private PredictionTileService predictionTileService;
    private PredictionBootstrap predictionBootstrap;
    private PredictionPaletteBuilder predictionPaletteBuilder;
    private StructureMarkerService structureMarkerService;
    private CompanionSession companionSession;
    private ClientNetworking clientNetworking;
    private CorrectionStore correctionStore;
    private MapSyncClient mapSyncClient;

    public static ConfluxMapClient get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        final MinecraftClient client = MinecraftClient.getInstance();

        configIo = new ConfigIo(
            FabricLoader.getInstance().getConfigDir().resolve(ConfluxMapMod.ID).resolve("config.json"),
            ConfluxMapMod.LOGGER
        );
        config = configIo.load();
        executors = new MapExecutors();
        sessionGuard = new SessionGuard();
        gameBridge = new McGameBridge(client, sessionGuard);
        companionSession = new CompanionSession();
        sessionTracker = new WorldSessionTracker(sessionGuard, companionSession);
        mapWorlds = new MapWorldService();
        daylightModel = new DaylightModel();
        tileService = new TileService(mapWorlds, executors, config, daylightModel);
        regionCache = new RegionCacheService(
            FabricLoader.getInstance().getGameDir().resolve(ConfluxMapMod.ID).resolve("cache"),
            mapWorlds, executors, tileService, ConfluxMapMod.LOGGER
        );
        tileService.bindRegionCache(regionCache);

        // Beside (not inside) the cache/waypoints directories above, same confluxmap/ root; a
        // failed load just leaves NativeLib.available() false and prediction permanently disabled.
        NativeLib.init(FabricLoader.getInstance().getGameDir().resolve(ConfluxMapMod.ID));
        predictionState = new PredictionState();
        structureMarkerService = new StructureMarkerService(
            FabricLoader.getInstance().getGameDir().resolve(ConfluxMapMod.ID).resolve("cache"),
            predictionState
        );
        predictionTileService = new PredictionTileService(sessionGuard, predictionState, executors, tileService);
        predictionTileService.setViewMode(config.predictionViewMode);
        correctionStore = new CorrectionStore(
            FabricLoader.getInstance().getGameDir().resolve(ConfluxMapMod.ID).resolve("cache").resolve("prediction")
        );
        predictionTileService.bindCorrectionStore(correctionStore);
        predictionBootstrap = new PredictionBootstrap(client, predictionState, companionSession);
        predictionPaletteBuilder = new PredictionPaletteBuilder(client, predictionState);
        clientNetworking = new ClientNetworking(companionSession);
        mapSyncClient = new MapSyncClient(companionSession, clientNetworking, correctionStore, predictionTileService, config);
        clientNetworking.bindMapSync(mapSyncClient);
        clientNetworking.register();

        spriteColorSampler = new SpriteColorSampler(client);
        biomeTintResolver = new BiomeTintResolver(client);
        tileTextureManager = new TileTextureManager(config, tileService, predictionTileService);
        layerSelector = new LayerSelector(client, config);

        chunkCapture = new ChunkCaptureService(
            client, config, mapWorlds, executors, tileService, regionCache, spriteColorSampler, biomeTintResolver, layerSelector
        );
        radarViewRange = new RadarViewRange();
        radarScanner = new EntityRadarScanner(client, config, radarViewRange);
        entityIconManager = new EntityIconManager();
        waypointService = new WaypointService(
            FabricLoader.getInstance().getGameDir().resolve(ConfluxMapMod.ID).resolve("waypoints"),
            executors, ConfluxMapMod.LOGGER
        );
        deathWatcher = new DeathWatcher(gameBridge, config, waypointService);
        minimapHudRenderer = new MinimapHudRenderer(
            client, config, gameBridge, tileService, tileTextureManager, radarScanner, entityIconManager, layerSelector, waypointService,
            radarViewRange
        );
        waypointWorldRenderer = new WaypointWorldRenderer(client, config, gameBridge, waypointService);
        fullscreenMapViewState = new FullscreenMapViewState();
        daylightTracker = new McDaylightTracker(client, config, daylightModel, mapWorlds, tileService);

        // RegionCacheService owns the map-world rotation and final-flush boundary as one transition.
        sessionTracker.addListener(regionCache::onSessionChanged);
        sessionTracker.addListener(chunkCapture::onSessionChanged);
        sessionTracker.addListener(tileService::onSessionChanged);
        sessionTracker.addListener(radarScanner::onSessionChanged);
        sessionTracker.addListener(fullscreenMapViewState::onSessionChanged);
        sessionTracker.addListener(waypointService::onSessionChanged);
        sessionTracker.addListener(correctionStore::onSessionChanged);
        sessionTracker.addListener(session -> mapSyncClient.reset());
        sessionTracker.addListener(predictionBootstrap::onSessionChanged);
        sessionTracker.addListener(predictionPaletteBuilder::onSessionChanged);
        sessionTracker.addListener(predictionTileService::onSessionChanged);
        sessionTracker.addListener(structureMarkerService::onSessionChanged);
        sessionTracker.addListener(session -> gameBridge.runOnRenderThread(tileTextureManager::releaseAll));
        sessionTracker.register();

        chunkCapture.register();
        radarScanner.register();
        minimapHudRenderer.register();
        waypointWorldRenderer.register();
        deathWatcher.register();
        daylightTracker.register();
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
            new ColorReloadListener(spriteColorSampler)
        );
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
            new EntityIconReloadListener(entityIconManager)
        );

        new Keybinds(config, configIo, layerSelector);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client2 -> shutdown());
        ConfluxMapMod.LOGGER.info("Conflux Map client services started ({} workers)", executors.workerCount());
    }

    private void shutdown() {
        // Quitting mid-world fires no session tick, so close the complete session lifecycle here.
        sessionTracker.endSession();
        correctionStore.flush();
        configIo.save(config);
        executors.shutdown(5000L);
    }

    public ConfluxConfig config() {
        return config;
    }

    public ConfigIo configIo() {
        return configIo;
    }

    public MapExecutors executors() {
        return executors;
    }

    public SessionGuard sessionGuard() {
        return sessionGuard;
    }

    public WorldSessionTracker sessionTracker() {
        return sessionTracker;
    }

    public GameBridge gameBridge() {
        return gameBridge;
    }

    public MapWorldService mapWorlds() {
        return mapWorlds;
    }

    public TileService tileService() {
        return tileService;
    }

    public DaylightModel daylightModel() {
        return daylightModel;
    }

    public RegionCacheService regionCache() {
        return regionCache;
    }

    public ChunkCaptureService chunkCapture() {
        return chunkCapture;
    }

    public EntityRadarScanner radarScanner() {
        return radarScanner;
    }

    public RadarViewRange radarViewRange() {
        return radarViewRange;
    }

    public EntityIconManager entityIconManager() {
        return entityIconManager;
    }

    public TileTextureManager tileTextureManager() {
        return tileTextureManager;
    }

    /** Forces a prediction-only cache/queue reload; captured map textures remain resident. */
    public void reloadPredictionTiles() {
        predictionTileService.reloadAll();
        gameBridge.runOnRenderThread(tileTextureManager::releasePredicted);
        ConfluxMapMod.LOGGER.info("Prediction tiles force-reloaded");
    }

    public FullscreenMapViewState fullscreenMapViewState() {
        return fullscreenMapViewState;
    }

    public LayerSelector layerSelector() {
        return layerSelector;
    }

    public WaypointService waypointService() {
        return waypointService;
    }

    public PredictionState predictionState() {
        return predictionState;
    }

    public PredictionTileService predictionTileService() {
        return predictionTileService;
    }

    public StructureMarkerService structureMarkerService() {
        return structureMarkerService;
    }

    public CompanionSession companionSession() {
        return companionSession;
    }

    public ClientNetworking clientNetworking() {
        return clientNetworking;
    }

    public MapSyncClient mapSyncClient() {
        return mapSyncClient;
    }
}
