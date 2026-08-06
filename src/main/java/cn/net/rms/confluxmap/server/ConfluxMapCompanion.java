package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.update.GithubReleaseFetcher;
import cn.net.rms.confluxmap.core.update.UpdateCheckService;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.server.shared.SharedWaypointIo;
import cn.net.rms.confluxmap.server.shared.SharedWaypointNetworking;
import cn.net.rms.confluxmap.server.shared.SharedWaypointService;
import cn.net.rms.confluxmap.server.shared.SharedWaypointStore;
import cn.net.rms.confluxmap.server.shared.SharedWaypointValidator;
import cn.net.rms.confluxmap.server.web.WebMapServer;
import cn.net.rms.confluxmap.server.web.WebMapPrivacyStore;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;

/**
 * Top-level companion service, owned by {@code ConfluxMapMod}'s {@code main} entrypoint so the
 * same jar serves dedicated and integrated servers. It owns the handshake, summary service, and
 * per-player correction budgets for the {@code confluxmap:map_sync} channel.
 *
 * <p>State is started/stopped on {@link ServerLifecycleEvents#SERVER_STARTED} / {@link
 * ServerLifecycleEvents#SERVER_STOPPING}. Dedicated servers activate immediately; integrated
 * servers stay inert until the world is published to LAN. The global Fabric-API receivers remain
 * registered so a running singleplayer world can activate without restarting.
 */
public final class ConfluxMapCompanion {
    private final ServerConfigIo configIo;
    private final WorldIds worldIds;
    private final ServerNetworking networking;
    private final SharedWaypointNetworking sharedWaypointNetworking;
    private final UpdateCheckService updateCheck;
    private final CompanionRuntimeState runtime = new CompanionRuntimeState();
    private volatile ServerConfig config;
    private volatile RegionSummaryService summaries;
    private volatile ChunkLoadStateService chunkLoadStates;
    private volatile SharedWaypointService sharedWaypoints;
    private volatile WebMapServer webMap;
    private volatile FabricWebMapBackend webMapBackend;
    private long webPlayerRevision;
    private int webPlayerTicks;
    private volatile WebMapPrivacyStore webMapPrivacy;

    public ConfluxMapCompanion(final ServerConfigIo configIo) {
        this.configIo = configIo;
        this.worldIds = new WorldIds();
        this.config = ServerConfigIo.loadDefault();
        this.networking = new ServerNetworking(this);
        this.sharedWaypointNetworking = new SharedWaypointNetworking(this);
        this.updateCheck = new UpdateCheckService(
            ConfluxMapMod.getVersion(),
            GithubReleaseFetcher.confluxMapReleases(ConfluxMapMod.getVersion()),
            ConfluxMapMod.LOGGER
        );
    }

    public void initialize() {
        // Fabric global receivers and command callbacks outlive individual integrated worlds.
        networking.register();
        sharedWaypointNetworking.register();
        ConfluxMapCommands.register(this);
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        //#if MC>=260100
        //$$ ServerChunkEvents.CHUNK_LOAD.register((world, chunk, generated) -> {
        //#else
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
        //#endif
            final RegionSummaryService current = summaries;
            if (current != null && isEnabled()) {
                current.onChunkLoad(world, chunk);
            }
            final ChunkLoadStateService loadStates = chunkLoadStates;
            if (loadStates != null) {
                loadStates.onChunkLoad(world, chunk);
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            final RegionSummaryService current = summaries;
            if (current != null && isEnabled()) {
                current.onChunkUnload(world, chunk);
            }
            final ChunkLoadStateService loadStates = chunkLoadStates;
            if (loadStates != null) {
                loadStates.onChunkUnload(world, chunk);
            }
        });
        ConfluxMapMod.LOGGER.info("companion initialized");
    }

    private void onServerTick(final MinecraftServer server) {
        activateIfNeeded(server);
        final RegionSummaryService current = summaries;
        if (current != null && isEnabled()) {
            current.tick(server);
        }
        final ChunkLoadStateService loadStates = chunkLoadStates;
        if (loadStates != null) {
            loadStates.tick(server);
        }
        final FabricWebMapBackend currentWebBackend = webMapBackend;
        if (currentWebBackend != null && config.webMap.sharePlayers
            && ++webPlayerTicks >= 40) {
            webPlayerTicks = 0;
            currentWebBackend.updatePlayers(++webPlayerRevision);
        }
    }

    private void onServerStarting(final MinecraftServer server) {
        config = configIo.load();
        runtime.deactivate();
        summaries = null;
        chunkLoadStates = null;
        sharedWaypoints = null;
        webMap = null;
        webMapBackend = null;
        webMapPrivacy = null;
    }

    private void onServerStarted(final MinecraftServer server) {
        // Console-only notice, dedicated servers only: on an integrated server the client
        // entrypoint already runs its own check and notifies in-game.
        if (server.isDedicated() && config.checkForUpdates) {
            updateCheck.checkAsync(info -> ConfluxMapMod.LOGGER.warn(
                "Conflux Map {} is available (installed {}). Download: {}",
                info.latestVersion(), info.currentVersion(), info.releaseUrl()
            ));
        }
        if (!config.enabled) {
            ConfluxMapMod.LOGGER.info("companion disabled by server.json (enabled=false); no HELLO replies");
            return;
        }
        activateIfNeeded(server);
        if (!runtime.isActive()) {
            ConfluxMapMod.LOGGER.info(
                "companion inactive in local singleplayer; publishing to LAN will activate it"
            );
        }
    }

    private void onServerStopping(final MinecraftServer server) {
        final WebMapServer currentWebMap = webMap;
        webMap = null;
        webMapBackend = null;
        webMapPrivacy = null;
        if (currentWebMap != null) {
            currentWebMap.close();
        }
        sharedWaypointNetworking.onServerStopping();
        if (!runtime.isActive()) {
            return;
        }
        final RegionSummaryService current = summaries;
        if (current != null) {
            current.prepareStop();
        }
        final ChunkLoadStateService loadStates = chunkLoadStates;
        if (loadStates != null) {
            loadStates.clear();
        }
        sharedWaypoints = null;
        webMap = null;
        ConfluxMapMod.LOGGER.info("companion stopping");
    }

    private void onServerStopped(final MinecraftServer server) {
        final boolean wasActive = runtime.isActive();
        final RegionSummaryService current = summaries;
        if (wasActive && current != null) {
            current.close(server);
        }
        summaries = null;
        chunkLoadStates = null;
        sharedWaypoints = null;
        webMapBackend = null;
        runtime.deactivate();
        if (wasActive) {
            worldIds.forget(server);
            ConfluxMapMod.LOGGER.info("companion stopped");
        }
    }

    public boolean isEnabled() {
        return config.enabled && runtime.isActive();
    }

    boolean webMapHidden(final UUID playerId) {
        final WebMapPrivacyStore privacy = webMapPrivacy;
        return privacy != null && privacy.hidden(playerId);
    }

    boolean setWebMapHidden(final UUID playerId, final boolean hidden) {
        final WebMapPrivacyStore privacy = webMapPrivacy;
        if (privacy == null) return false;
        try {
            privacy.setHidden(playerId, hidden);
            return true;
        } catch (final IOException e) {
            ConfluxMapMod.LOGGER.error("could not persist web-map privacy preference", e);
            return false;
        }
    }

    public ServerConfig config() {
        return config;
    }

    public WorldIds worldIds() {
        return worldIds;
    }

    public RegionSummaryService summaries() {
        return summaries;
    }

    public ChunkLoadStateService chunkLoadStates() {
        return chunkLoadStates;
    }

    public boolean chunkLoadStatesEnabled() {
        return isEnabled() && config.shareChunkLoadState && chunkLoadStates != null;
    }

    /** Returns loaded world state, retained across runtime disable/enable for idempotent retries. */
    public SharedWaypointService sharedWaypoints() {
        return sharedWaypoints;
    }

    /** Effective capability: both configuration gates are on and world state loaded successfully. */
    public boolean sharedWaypointsEnabled() {
        return isEnabled() && config.shareWaypoints && sharedWaypoints != null;
    }

    public enum SharedWaypointToggleResult {
        ENABLED,
        DISABLED,
        ALREADY_ENABLED,
        ALREADY_DISABLED,
        MASTER_DISABLED,
        LOAD_FAILED,
        SAVE_FAILED,
        DISABLED_SAVE_FAILED
    }

    /** Enables sharing only after world state loads and the atomic config save succeeds. */
    public synchronized SharedWaypointToggleResult enableSharedWaypoints(final MinecraftServer server) {
        if (!isEnabled()) {
            return SharedWaypointToggleResult.MASTER_DISABLED;
        }
        if (sharedWaypointsEnabled()) {
            return SharedWaypointToggleResult.ALREADY_ENABLED;
        }
        SharedWaypointService candidate = sharedWaypoints;
        if (candidate == null) {
            candidate = loadSharedWaypoints(server);
            if (candidate == null) {
                return SharedWaypointToggleResult.LOAD_FAILED;
            }
        }
        final boolean previousFlag = config.shareWaypoints;
        config.shareWaypoints = true;
        if (!configIo.saveAtomically(config)) {
            config.shareWaypoints = previousFlag;
            return SharedWaypointToggleResult.SAVE_FAILED;
        }
        sharedWaypoints = candidate;
        sharedWaypointNetworking.onFeatureStateChanged(server);
        return SharedWaypointToggleResult.ENABLED;
    }

    /** Disables writes immediately; a persistence failure never re-enables the runtime switch. */
    public synchronized SharedWaypointToggleResult disableSharedWaypoints(final MinecraftServer server) {
        final boolean wasEnabled = config.shareWaypoints || sharedWaypointsEnabled();
        config.shareWaypoints = false;
        final boolean saved = configIo.saveAtomically(config);
        if (!wasEnabled) {
            return saved
                ? SharedWaypointToggleResult.ALREADY_DISABLED
                : SharedWaypointToggleResult.DISABLED_SAVE_FAILED;
        }
        sharedWaypointNetworking.onFeatureStateChanged(server);
        return saved
            ? SharedWaypointToggleResult.DISABLED
            : SharedWaypointToggleResult.DISABLED_SAVE_FAILED;
    }

    private SharedWaypointService loadSharedWaypoints(final MinecraftServer server) {
        final SharedWaypointIo io = new SharedWaypointIo(server.getSavePath(WorldSavePath.ROOT), ConfluxMapMod.LOGGER);
        try {
            final Map<DimensionId, SharedWaypointValidator.HeightRange> dimensions = new LinkedHashMap<>();
            for (final ServerWorld world : server.getWorlds()) {
                dimensions.put(
                    DimensionId.parse(world.getRegistryKey().getValue().toString()),
                    new SharedWaypointValidator.HeightRange(world.getBottomY(), world.getTopY())
                );
            }
            final SharedWaypointValidator validator = new SharedWaypointValidator(dimensions);
            final SharedWaypointStore store = new SharedWaypointStore(
                SharedWaypointService.sanitizeLoaded(io.load(), validator, ConfluxMapMod.LOGGER)
            );
            final SharedWaypointService.Limits limits = new SharedWaypointService.Limits(
                config.maxSharedWaypointsPerWorld,
                config.maxSharedWaypointsPerPlayer,
                config.sharedWaypointMutationsPerMinute
            );
            return new SharedWaypointService(
                store,
                io,
                validator,
                Clock.systemUTC(),
                UUID::randomUUID,
                limits,
                event -> ConfluxMapMod.LOGGER.info(
                    "shared-waypoint audit operationId={} actorId={} action={} status={} error={} waypointId={} revision={}",
                    event.operationId(), event.actorId(), event.action(), event.status(), event.error(),
                    event.waypointId(), event.revision()
                ),
                ConfluxMapMod.LOGGER
            );
        } catch (final SharedWaypointIo.UnsupportedSchemaVersionException e) {
            ConfluxMapMod.LOGGER.error(
                "Shared waypoints disabled: {} uses unsupported schema {} (supported={}); file was preserved",
                io.file(), e.schemaVersion(), SharedWaypointIo.SCHEMA_VERSION
            );
        } catch (final IOException | RuntimeException e) {
            ConfluxMapMod.LOGGER.error("Shared waypoints disabled: could not initialize {}", io.file(), e);
        }
        return null;
    }

    private void activateIfNeeded(final MinecraftServer server) {
        if (!runtime.activateIfAllowed(config.enabled, server.isDedicated(), server.getServerPort())) {
            return;
        }
        summaries = new RegionSummaryService(config);
        chunkLoadStates = config.shareChunkLoadState ? new ChunkLoadStateService() : null;
        // Corrections can use the same predictor as the client when a bundled native exists;
        // failure is non-fatal and RegionSummaryService falls back to absolute samples.
        NativeLib.init(server.getSavePath(WorldSavePath.ROOT).resolve("confluxmap"));
        webMapPrivacy = new WebMapPrivacyStore(
            server.getSavePath(WorldSavePath.ROOT).resolve("confluxmap/webmap-hidden.txt")
        );
        try {
            webMapPrivacy.load();
        } catch (final IOException e) {
            ConfluxMapMod.LOGGER.error("could not load web-map privacy preferences", e);
        }
        if (config.shareWaypoints) {
            sharedWaypoints = loadSharedWaypoints(server);
        }
        if (config.webMap.enabled) {
            try {
                webMapBackend = new FabricWebMapBackend(server, this);
                webMap = WebMapServer.start(config.webMap, webMapBackend);
                ConfluxMapMod.LOGGER.info(
                    "web map listening on {}:{}",
                    config.webMap.bindAddress, config.webMap.port
                );
            } catch (final IOException e) {
                webMapBackend = null;
                ConfluxMapMod.LOGGER.error("web map failed to start", e);
            }
        }
        ConfluxMapMod.LOGGER.info(
            "companion ready (shareSeed={} allowBiomeMap={} allowStructureSearch={} shareCorrections={} shareChunkLoadState={} allowEntityRadar={} shareWaypoints={} maxTilesPerRequest={})",
            config.shareSeed, config.allowBiomeMap, config.allowStructureSearch,
            config.shareCorrections, chunkLoadStates != null, config.allowEntityRadar,
            sharedWaypoints != null, config.maxTilesPerRequest
        );
    }
}
