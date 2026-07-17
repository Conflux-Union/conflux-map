package cn.net.rms.confluxmap;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.mc.McGameBridge;
import cn.net.rms.confluxmap.mc.color.BiomeTintResolver;
import cn.net.rms.confluxmap.mc.color.ColorReloadListener;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import cn.net.rms.confluxmap.mc.input.Keybinds;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureService;
import cn.net.rms.confluxmap.mc.ui.hud.MinimapHudRenderer;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapViewState;
import cn.net.rms.confluxmap.mc.world.WorldSessionTracker;
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
    private SpriteColorSampler spriteColorSampler;
    private BiomeTintResolver biomeTintResolver;
    private TileTextureManager tileTextureManager;
    private ChunkCaptureService chunkCapture;
    private MinimapHudRenderer minimapHudRenderer;
    private FullscreenMapViewState fullscreenMapViewState;

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
        sessionTracker = new WorldSessionTracker(sessionGuard);
        mapWorlds = new MapWorldService();
        tileService = new TileService(mapWorlds, executors);

        spriteColorSampler = new SpriteColorSampler(client);
        biomeTintResolver = new BiomeTintResolver(client);
        tileTextureManager = new TileTextureManager(config, tileService);

        chunkCapture = new ChunkCaptureService(
            client, config, mapWorlds, executors, tileService, spriteColorSampler, biomeTintResolver
        );
        minimapHudRenderer = new MinimapHudRenderer(client, config, gameBridge, tileService, tileTextureManager);
        fullscreenMapViewState = new FullscreenMapViewState();

        sessionTracker.addListener(mapWorlds::onSessionChanged);
        sessionTracker.addListener(chunkCapture::onSessionChanged);
        sessionTracker.addListener(tileService::onSessionChanged);
        sessionTracker.addListener(session -> gameBridge.runOnRenderThread(tileTextureManager::releaseAll));
        sessionTracker.addListener(fullscreenMapViewState::onSessionChanged);
        sessionTracker.register();

        chunkCapture.register();
        minimapHudRenderer.register();
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
            new ColorReloadListener(spriteColorSampler)
        );

        new Keybinds(config, configIo);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client2 -> shutdown());
        ConfluxMapMod.LOGGER.info("Conflux Map client services started ({} workers)", executors.workerCount());
    }

    private void shutdown() {
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

    public ChunkCaptureService chunkCapture() {
        return chunkCapture;
    }

    public TileTextureManager tileTextureManager() {
        return tileTextureManager;
    }

    public FullscreenMapViewState fullscreenMapViewState() {
        return fullscreenMapViewState;
    }
}
