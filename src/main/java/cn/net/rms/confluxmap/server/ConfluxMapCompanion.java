package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Top-level companion service, owned by {@code ConfluxMapMod}'s {@code main} entrypoint so the
 * same jar serves dedicated and integrated servers. S3 owns only the handshake: load the config,
 * register the {@code confluxmap:m2} receiver, and answer HELLO_POLICY. The patch-serving
 * machinery (chunk summarizer, patch builder, rate limiter) is S4's job.
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

    public ConfluxMapCompanion(final ServerConfigIo configIo) {
        this.configIo = configIo;
        this.worldIds = new WorldIds();
        this.config = ServerConfigIo.loadDefault();
        this.networking = new ServerNetworking(this);
    }

    public void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ConfluxMapMod.LOGGER.info("companion initialized (handshake only this slice)");
    }

    private void onServerStarted(final MinecraftServer server) {
        config = configIo.load();
        if (!config.enabled) {
            ConfluxMapMod.LOGGER.info("companion disabled by server.json (enabled=false); no HELLO replies");
            return;
        }
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
}
