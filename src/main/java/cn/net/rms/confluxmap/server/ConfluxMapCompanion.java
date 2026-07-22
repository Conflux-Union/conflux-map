package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import net.minecraft.util.WorldSavePath;

/**
 * Top-level companion service, owned by {@code ConfluxMapMod}'s {@code main} entrypoint so the
 * same jar serves dedicated and integrated servers. It owns the handshake, summary service, and
 * per-player correction budgets for the {@code confluxmap:map_sync} channel.
 *
 * <p>State is started/stopped on {@link ServerLifecycleEvents#SERVER_STARTED} / {@link
 * ServerLifecycleEvents#SERVER_STOPPING}. The companion is safe to construct on either side
 * (dedicated or integrated) because {@link ServerNetworking} registers only Fabric-API global
 * receivers, which are inert until a player connects.
 */
public final class ConfluxMapCompanion {
    private final ServerConfigIo configIo;
    private final WorldIds worldIds;
    private final ServerNetworking networking;
    private volatile ServerConfig config;
    private volatile RegionSummaryService summaries;

    public ConfluxMapCompanion(final ServerConfigIo configIo) {
        this.configIo = configIo;
        this.worldIds = new WorldIds();
        this.config = ServerConfigIo.loadDefault();
        this.networking = new ServerNetworking(this);
    }

    public void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ConfluxMapMod.LOGGER.info("companion initialized");
    }

    private void onServerTick(final MinecraftServer server) {
        final RegionSummaryService current = summaries;
        if (current != null && config.enabled) {
            current.tick(server);
        }
    }

    private void onServerStarted(final MinecraftServer server) {
        config = configIo.load();
        if (!config.enabled) {
            ConfluxMapMod.LOGGER.info("companion disabled by server.json (enabled=false); no HELLO replies");
            return;
        }
        summaries = new RegionSummaryService(config);
        // Corrections can use the same predictor as the client when a bundled native exists;
        // failure is non-fatal and RegionSummaryService falls back to absolute samples.
        NativeLib.init(server.getSavePath(WorldSavePath.ROOT).resolve("confluxmap"));
        networking.register();
        ConfluxMapMod.LOGGER.info(
            "companion ready (shareSeed={} shareCorrections={} maxPatchLod={} maxTilesPerRequest={})",
            config.shareSeed, config.shareCorrections, config.maxPatchLod, config.maxTilesPerRequest
        );
    }

    private void onServerStopping(final MinecraftServer server) {
        worldIds.forget(server);
        ConfluxMapMod.LOGGER.info("companion stopped");
    }

    public boolean isEnabled() {
        return config.enabled;
    }

    public ServerConfig config() {
        return config;
    }

    public WorldIds worldIds() {
        return worldIds;
    }

    public RegionSummaryService summaries() {
        RegionSummaryService current = summaries;
        if (current == null) {
            current = new RegionSummaryService(config);
            summaries = current;
        }
        return current;
    }
}
