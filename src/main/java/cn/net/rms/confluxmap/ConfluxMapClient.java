package cn.net.rms.confluxmap;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.core.annotation.AnnotationService;
import cn.net.rms.confluxmap.core.cache.RegionCacheService;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.export.MapExportService;
import cn.net.rms.confluxmap.core.export.ServiceMapExportTileSource;
import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileIo;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileRegistry;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileResolver;
import cn.net.rms.confluxmap.core.multiworld.ServerAliasIo;
import cn.net.rms.confluxmap.core.multiworld.ServerAliasRegistry;
import cn.net.rms.confluxmap.core.multiworld.ServerAliasResolver;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.CorrectionStore;
import cn.net.rms.confluxmap.core.radar.RadarViewRange;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.NamespaceAdoption;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.trail.PlayerTrail;
import cn.net.rms.confluxmap.core.update.GithubReleaseFetcher;
import cn.net.rms.confluxmap.core.update.UpdateCheckService;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderCatalog;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.mc.McGameBridge;
import cn.net.rms.confluxmap.mc.color.BiomeTintResolver;
import cn.net.rms.confluxmap.mc.color.ColorReloadListener;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import cn.net.rms.confluxmap.mc.color.SyncedMaterialResolver;
import cn.net.rms.confluxmap.mc.input.Keybinds;
import cn.net.rms.confluxmap.mc.net.ClientNetworking;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.net.ChunkLoadStateClient;
import cn.net.rms.confluxmap.mc.net.MapSyncClient;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.predict.PredictionBootstrap;
import cn.net.rms.confluxmap.mc.predict.ManualSeedService;
import cn.net.rms.confluxmap.mc.predict.PredictionPaletteBuilder;
import cn.net.rms.confluxmap.mc.predict.StructureMarkerService;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.EntityIconReloadListener;
import cn.net.rms.confluxmap.mc.radar.EntityRadarScanner;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import cn.net.rms.confluxmap.mc.render.Mesh;
import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureService;
import cn.net.rms.confluxmap.mc.survey.SurveyReminderNotifier;
import cn.net.rms.confluxmap.mc.teleport.ClientGroundTeleportService;
import cn.net.rms.confluxmap.mc.trail.PlayerTrailTracker;
import cn.net.rms.confluxmap.mc.ui.hud.MinimapHudRenderer;
import cn.net.rms.confluxmap.mc.ui.UiResourceReloadListener;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapViewState;
import cn.net.rms.confluxmap.mc.update.UpdateNotifier;
import cn.net.rms.confluxmap.mc.ui.world.WaypointWorldRenderer;
import cn.net.rms.confluxmap.mc.ui.world.WaypointHighlightState;
import cn.net.rms.confluxmap.mc.world.DeathWatcher;
import cn.net.rms.confluxmap.mc.world.ClientMultiworldService;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import cn.net.rms.confluxmap.mc.world.FullscreenMapBrowseService;
import cn.net.rms.confluxmap.mc.world.McDaylightTracker;
import cn.net.rms.confluxmap.mc.world.WorldSessionTracker;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Composition root: builds and wires every client-side service. */
public final class ConfluxMapClient implements ClientModInitializer {
    private static ConfluxMapClient instance;

    private ConfluxConfig config;
    private ConfigIo configIo;
    private MapExecutors executors;
    private SessionGuard sessionGuard;
    private WorldSessionTracker sessionTracker;
    private ClientMultiworldService clientMultiworldService;
    private ServerAliasResolver serverAliasResolver;
    private GameBridge gameBridge;
    private MapWorldService mapWorlds;
    private TileService tileService;
    private RegionCacheService regionCache;
    private SpriteColorSampler spriteColorSampler;
    private BiomeTintResolver biomeTintResolver;
    private SyncedMaterialResolver syncedMaterialResolver;
    private TileTextureManager tileTextureManager;
    private FullscreenMapBrowseService fullscreenMapBrowseService;
    private ChunkCaptureService chunkCapture;
    private RadarViewRange radarViewRange;
    private EntityRadarScanner radarScanner;
    private EntityIconManager entityIconManager;
    private PlayerTrail playerTrail;
    private PlayerTrailTracker playerTrailTracker;
    private MinimapHudRenderer minimapHudRenderer;
    private UiResourceTheme uiResourceTheme;
    private FullscreenMapViewState fullscreenMapViewState;
    private LayerSelector layerSelector;
    private WaypointService waypointService;
    private AnnotationService annotationService;
    private WaypointRenderCatalog waypointRenderCatalog;
    private DeathWatcher deathWatcher;
    private WaypointWorldRenderer waypointWorldRenderer;
    private WaypointHighlightState waypointHighlightState;
    private DaylightModel daylightModel;
    private McDaylightTracker daylightTracker;
    private PredictionState predictionState;
    private PredictionTileService predictionTileService;
    private MapExportService mapExportService;
    private PredictionBootstrap predictionBootstrap;
    private ManualSeedService manualSeedService;
    private PredictionPaletteBuilder predictionPaletteBuilder;
    private StructureMarkerService structureMarkerService;
    private CompanionSession companionSession;
    private ClientNetworking clientNetworking;
    private SharedWaypointClient sharedWaypoints;
    private CorrectionStore correctionStore;
    private MapSyncClient mapSyncClient;
    private ChunkLoadStateClient chunkLoadStateClient;
    private UpdateCheckService updateCheck;
    private UpdateNotifier updateNotifier;
    private SurveyReminderNotifier surveyReminderNotifier;
    private ClientGroundTeleportService groundTeleportService;
    private Keybinds keybinds;

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
        surveyReminderNotifier = new SurveyReminderNotifier(client, config, configIo);
        final Path confluxRoot = FabricLoader.getInstance().getGameDir().resolve(ConfluxMapMod.ID);
        final Path cacheRoot = confluxRoot.resolve("cache");
        executors = new MapExecutors();
        sessionGuard = new SessionGuard();
        gameBridge = new McGameBridge(client, sessionGuard);
        companionSession = new CompanionSession();
        final ClientWorldProfileIo clientWorldProfileIo = new ClientWorldProfileIo(
            FabricLoader.getInstance().getConfigDir().resolve(ConfluxMapMod.ID).resolve("client_worlds.json"),
            ConfluxMapMod.LOGGER
        );
        final ClientWorldProfileRegistry clientWorldProfiles = clientWorldProfileIo.load();
        final ClientWorldProfileResolver clientWorldResolver = new ClientWorldProfileResolver(
            clientWorldProfiles, UUID::randomUUID, () -> clientWorldProfileIo.save(clientWorldProfiles)
        );
        final Path waypointRoot = confluxRoot.resolve("waypoints");
        final Path annotationRoot = confluxRoot.resolve("annotations");
        final ServerAliasIo serverAliasIo = new ServerAliasIo(
            FabricLoader.getInstance().getConfigDir().resolve(ConfluxMapMod.ID).resolve("server_aliases.json"),
            ConfluxMapMod.LOGGER
        );
        final ServerAliasRegistry serverAliases = serverAliasIo.load();
        serverAliasResolver = new ServerAliasResolver(
            serverAliases,
            storageId -> Files.isDirectory(cacheRoot.resolve(storageId))
                || Files.isDirectory(waypointRoot.resolve(storageId)),
            () -> serverAliasIo.save(serverAliases)
        );
        clientMultiworldService = new ClientMultiworldService(
            client, companionSession, clientWorldResolver, cacheRoot, executors.io(), serverAliasResolver
        );
        sessionTracker = new WorldSessionTracker(
            sessionGuard, companionSession, clientMultiworldService, serverAliasResolver
        );
        // A server that starts advertising an instance id changes the key its data is stored
        // under. Carry the old namespace over before the session binds its directories, or the
        // player's map looks erased on the first reconnect after the server upgrades.
        final List<NamespaceAdoption.Store> adoptableStores = List.of(
            new NamespaceAdoption.Store(cacheRoot, ""),
            new NamespaceAdoption.Store(cacheRoot.resolve("prediction"), ""),
            new NamespaceAdoption.Store(waypointRoot, ".json")
        );
        sessionTracker.bindNamespaceAdopter(identity -> NamespaceAdoption.adopt(
            adoptableStores, identity, companionSession.companionWorldId(), ConfluxMapMod.LOGGER
        ));
        mapWorlds = new MapWorldService();
        daylightModel = new DaylightModel();
        tileService = new TileService(mapWorlds, executors, config, daylightModel);
        regionCache = new RegionCacheService(
            cacheRoot,
            mapWorlds, executors, tileService, ConfluxMapMod.LOGGER
        );
        tileService.bindRegionCache(regionCache);

        // Beside (not inside) the cache/waypoints directories above, same confluxmap/ root; a
        // failed load just leaves NativeLib.available() false and prediction permanently disabled.
        NativeLib.init(confluxRoot);
        predictionState = new PredictionState();
        spriteColorSampler = new SpriteColorSampler(client);
        biomeTintResolver = new BiomeTintResolver(client);
        structureMarkerService = new StructureMarkerService(
            cacheRoot,
            predictionState,
            companionSession::structureSearchAllowed,
            executors
        );
        predictionTileService = new PredictionTileService(sessionGuard, predictionState, executors, tileService);
        predictionTileService.bindDaylightModel(daylightModel);
        predictionTileService.setMapColorStyle(config.mapColorStyle);
        predictionTileService.setViewMode(config.predictionViewMode);
        correctionStore = new CorrectionStore(
            cacheRoot.resolve("prediction")
        );
        predictionTileService.bindCorrectionStore(correctionStore);
        predictionBootstrap = new PredictionBootstrap(
            client, predictionState, companionSession, config.predictionManualSeeds
        );
        clientNetworking = new ClientNetworking(companionSession);
        syncedMaterialResolver = new SyncedMaterialResolver(
            client, spriteColorSampler, biomeTintResolver,
            predictionTileService.syncedMaterials()
        );
        mapSyncClient = new MapSyncClient(
            companionSession, clientNetworking, correctionStore, predictionTileService,
            config, syncedMaterialResolver::register
        );
        chunkLoadStateClient = new ChunkLoadStateClient(companionSession, clientNetworking);
        mapExportService = new MapExportService(
            confluxRoot.resolve("exports"),
            sessionGuard,
            request -> new ServiceMapExportTileSource(
                tileService,
                predictionTileService,
                request,
                request.displayMode() == FullscreenDisplayMode.CHUNK_LOAD_STATE
                    ? chunkLoadStateClient::requestExportTile
                    : ignored -> java.util.concurrent.CompletableFuture.completedFuture(request.loadState())
            )
        );
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> mapExportService.tick());
        clientNetworking.bindMapSync(mapSyncClient);
        clientNetworking.bindChunkLoadStates(chunkLoadStateClient);
        clientNetworking.register();
        sharedWaypoints = new SharedWaypointClient(client);
        sharedWaypoints.register();
        groundTeleportService = new ClientGroundTeleportService(client, config);

        predictionPaletteBuilder = new PredictionPaletteBuilder(client, predictionState, spriteColorSampler);
        tileTextureManager = new TileTextureManager(config, tileService, predictionTileService, daylightModel);
        fullscreenMapBrowseService = new FullscreenMapBrowseService(
            cacheRoot,
            waypointRoot,
            annotationRoot,
            executors,
            config,
            daylightModel,
            predictionTileService
        );
        manualSeedService = new ManualSeedService(
            config,
            configIo,
            sessionGuard,
            client::isInSingleplayer,
            companionSession::isActive,
            () -> companionSession.seedFor(PredictionDimensions.OVERWORLD).isPresent(),
            this::refreshPredictionSource
        );
        layerSelector = new LayerSelector(client, config);

        chunkCapture = new ChunkCaptureService(
            client, config, mapWorlds, executors, tileService, predictionTileService,
            companionSession::serverViewDistance,
            regionCache, spriteColorSampler, biomeTintResolver, layerSelector
        );
        clientMultiworldService.bindChunkCapture(chunkCapture);
        radarViewRange = new RadarViewRange();
        radarScanner = new EntityRadarScanner(
            client, config, radarViewRange, companionSession::entityRadarAllowed
        );
        entityIconManager = new EntityIconManager(executors.workers());
        playerTrail = new PlayerTrail();
        playerTrailTracker = new PlayerTrailTracker(client, config, sessionGuard, playerTrail);
        waypointService = new WaypointService(waypointRoot, executors, ConfluxMapMod.LOGGER);
        annotationService = new AnnotationService(annotationRoot, executors, ConfluxMapMod.LOGGER);
        waypointRenderCatalog = new WaypointRenderCatalog(waypointService, sharedWaypoints::list, config);
        waypointHighlightState = new WaypointHighlightState();
        deathWatcher = new DeathWatcher(gameBridge, config, waypointService);
        uiResourceTheme = new UiResourceTheme();
        minimapHudRenderer = new MinimapHudRenderer(
            client, config, gameBridge, tileService, tileTextureManager, radarScanner, entityIconManager,
            playerTrail, annotationService, layerSelector, waypointRenderCatalog,
            radarViewRange, uiResourceTheme
        );
        waypointWorldRenderer = new WaypointWorldRenderer(
            client, config, gameBridge, waypointRenderCatalog, waypointHighlightState
        );
        fullscreenMapViewState = new FullscreenMapViewState();
        daylightTracker = new McDaylightTracker(
            client,
            config,
            daylightModel,
            mapWorlds,
            tileService,
            () -> {
                tileService.reloadLighting();
                tileTextureManager.releaseAll();
                predictionTileService.reloadAll();
            }
        );

        // RegionCacheService owns the map-world rotation and final-flush boundary as one transition.
        sessionTracker.addListener(regionCache::onSessionChanged);
        sessionTracker.addListener(chunkCapture::onSessionChanged);
        sessionTracker.addListener(tileService::onSessionChanged);
        sessionTracker.addListener(radarScanner::onSessionChanged);
        sessionTracker.addListener(session -> gameBridge.runOnRenderThread(entityIconManager::onSessionChanged));
        sessionTracker.addListener(playerTrailTracker::onSessionChanged);
        sessionTracker.addListener(fullscreenMapViewState::onSessionChanged);
        sessionTracker.addListener(waypointService::onSessionChanged);
        sessionTracker.addListener(waypointHighlightState::onSessionChanged);
        sessionTracker.addListener(annotationService::onSessionChanged);
        sessionTracker.addListener(correctionStore::onSessionChanged);
        sessionTracker.addListener(session -> mapSyncClient.reset());
        sessionTracker.addListener(session -> chunkLoadStateClient.reset());
        sessionTracker.addListener(predictionBootstrap::onSessionChanged);
        sessionTracker.addListener(predictionPaletteBuilder::onSessionChanged);
        sessionTracker.addListener(predictionTileService::onSessionChanged);
        sessionTracker.addListener(structureMarkerService::onSessionChanged);
        sessionTracker.addListener(session -> groundTeleportService.reset());
        sessionTracker.addListener(session -> gameBridge.runOnRenderThread(fullscreenMapBrowseService::clear));
        sessionTracker.addListener(session -> gameBridge.runOnRenderThread(tileTextureManager::releaseAll));
        clientMultiworldService.register();
        sessionTracker.register();

        chunkCapture.register();
        radarScanner.register();
        entityIconManager.register();
        playerTrailTracker.register();
        minimapHudRenderer.register();
        waypointWorldRenderer.register();
        deathWatcher.register();
        daylightTracker.register();
        groundTeleportService.register();
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
            new ColorReloadListener(client, spriteColorSampler, () -> {
                predictionPaletteBuilder.refreshCurrentWorld();
                syncedMaterialResolver.refresh();
                reloadPredictionTiles();
            })
        );
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
            new EntityIconReloadListener(entityIconManager)
        );
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
            new UiResourceReloadListener(uiResourceTheme)
        );

        updateCheck = new UpdateCheckService(
            ConfluxMapMod.getVersion(),
            GithubReleaseFetcher.confluxMapReleases(ConfluxMapMod.getVersion()),
            ConfluxMapMod.LOGGER
        );
        if (config.updateCheckEnabled) {
            updateCheck.checkAsync();
        }
        updateNotifier = new UpdateNotifier(client, updateCheck);
        updateNotifier.register();
        surveyReminderNotifier.register();

        keybinds = new Keybinds(config, configIo, layerSelector);
        clientMultiworldService.bindOpenMapKeyDisplayName(keybinds::openMapKeyDisplayName);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client2 -> shutdown());
        ConfluxMapMod.LOGGER.info("Conflux Map client services started ({} workers)", executors.workerCount());
    }

    private void shutdown() {
        // Quitting mid-world fires no session tick, so close the complete session lifecycle here.
        sessionTracker.endSession();
        mapExportService.close();
        correctionStore.flush();
        surveyReminderNotifier.flush();
        configIo.save(config);
        entityIconManager.close();
        //#if MC>=260200
        //$$ Mesh.close();
        //#endif
        executors.shutdown(5000L);
    }

    public ConfluxConfig config() {
        return config;
    }

    public ConfigIo configIo() {
        return configIo;
    }

    public Keybinds keybinds() {
        return keybinds;
    }

    public MapExecutors executors() {
        return executors;
    }

    public SessionGuard sessionGuard() {
        return sessionGuard;
    }

    public ServerAliasResolver serverAliasResolver() {
        return serverAliasResolver;
    }

    public WorldSessionTracker sessionTracker() {
        return sessionTracker;
    }

    public ClientMultiworldService clientMultiworldService() {
        return clientMultiworldService;
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

    public PlayerTrail playerTrail() {
        return playerTrail;
    }

    public TileTextureManager tileTextureManager() {
        return tileTextureManager;
    }

    public FullscreenMapBrowseService fullscreenMapBrowseService() {
        return fullscreenMapBrowseService;
    }

    public UiResourceTheme uiResourceTheme() {
        return uiResourceTheme;
    }

    /** Forces a prediction-only cache/queue reload; captured map textures remain resident. */
    public void reloadPredictionTiles() {
        predictionTileService.reloadAll();
        gameBridge.runOnRenderThread(tileTextureManager::releasePredicted);
        ConfluxMapMod.LOGGER.info("Prediction tiles force-reloaded");
    }

    /** Invalidates every rendered terrain plane after the shared colour style changes. */
    public void onMapColorStyleChanged() {
        predictionTileService.setMapColorStyle(config.mapColorStyle);
        predictionTileService.reloadAll();
        tileService.reloadMapColorStyle();
        gameBridge.runOnRenderThread(tileTextureManager::releaseAll);
        ConfluxMapMod.LOGGER.info("Map color style changed to {}", config.mapColorStyle);
    }

    /** Re-resolves the active session's seed source after a local seed setting changes. */
    private void refreshPredictionSource() {
        final SessionGuard.Session session = sessionGuard.current();
        predictionBootstrap.onSessionChanged(session);
        predictionPaletteBuilder.onSessionChanged(session);
        predictionTileService.reloadAll();
        structureMarkerService.onSessionChanged(session);
        gameBridge.runOnRenderThread(tileTextureManager::releasePredicted);
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

    public AnnotationService annotationService() {
        return annotationService;
    }

    public WaypointRenderCatalog waypointRenderCatalog() {
        return waypointRenderCatalog;
    }

    public WaypointHighlightState waypointHighlightState() {
        return waypointHighlightState;
    }

    public PredictionState predictionState() {
        return predictionState;
    }

    public PredictionTileService predictionTileService() {
        return predictionTileService;
    }

    public MapExportService mapExportService() {
        return mapExportService;
    }

    public StructureMarkerService structureMarkerService() {
        return structureMarkerService;
    }

    public ManualSeedService manualSeedService() {
        return manualSeedService;
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

    public ChunkLoadStateClient chunkLoadStateClient() {
        return chunkLoadStateClient;
    }

    public SharedWaypointClient sharedWaypoints() {
        return sharedWaypoints;
    }

    public UpdateCheckService updateCheck() {
        return updateCheck;
    }

    public void dismissSurveyReminder() {
        surveyReminderNotifier.dismiss();
    }

    public ClientGroundTeleportService groundTeleportService() {
        return groundTeleportService;
    }
}
