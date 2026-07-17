package cn.net.rms.confluxmap;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.mc.McGameBridge;
import cn.net.rms.confluxmap.mc.input.Keybinds;
import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureService;
import cn.net.rms.confluxmap.mc.world.WorldSessionTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

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
    private ChunkCaptureService chunkCapture;

    public static ConfluxMapClient get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        configIo = new ConfigIo(
            FabricLoader.getInstance().getConfigDir().resolve(ConfluxMapMod.ID).resolve("config.json"),
            ConfluxMapMod.LOGGER
        );
        config = configIo.load();
        executors = new MapExecutors();
        sessionGuard = new SessionGuard();
        gameBridge = new McGameBridge(MinecraftClient.getInstance(), sessionGuard);
        sessionTracker = new WorldSessionTracker(sessionGuard);
        mapWorlds = new MapWorldService();
        chunkCapture = new ChunkCaptureService(MinecraftClient.getInstance(), config, mapWorlds, executors);
        sessionTracker.addListener(mapWorlds::onSessionChanged);
        sessionTracker.addListener(chunkCapture::onSessionChanged);
        sessionTracker.register();
        chunkCapture.register();
        new Keybinds(config, configIo);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
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

    public ChunkCaptureService chunkCapture() {
        return chunkCapture;
    }
}
