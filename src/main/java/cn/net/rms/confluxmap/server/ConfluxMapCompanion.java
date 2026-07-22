package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.server.shared.SharedWaypointIo;
import cn.net.rms.confluxmap.server.shared.SharedWaypointNetworking;
import cn.net.rms.confluxmap.server.shared.SharedWaypointService;
import cn.net.rms.confluxmap.server.shared.SharedWaypointStore;
import cn.net.rms.confluxmap.server.shared.SharedWaypointValidator;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;

/**
 * Top-level companion service, owned by {@code ConfluxMapMod}'s {@code main} entrypoint so the
 * same jar serves dedicated and integrated servers. It owns the handshake, summary service, and
 * per-player correction budgets for the {@code confluxmap:m2} channel.
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
    private final SharedWaypointNetworking sharedWaypointNetworking;
    private volatile ServerConfig config;
    private volatile RegionSummaryService summaries;
    private volatile SharedWaypointService sharedWaypoints;

    public ConfluxMapCompanion(final ServerConfigIo configIo) {
        this.configIo = configIo;
        this.worldIds = new WorldIds();
        this.config = ServerConfigIo.loadDefault();
        this.networking = new ServerNetworking(this);
        this.sharedWaypointNetworking = new SharedWaypointNetworking(this);
    }

    public void initialize() {
        // Fabric global receivers and command callbacks outlive individual integrated worlds.
        networking.register();
        sharedWaypointNetworking.register();
        SharedWaypointCommands.register(this);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ConfluxMapMod.LOGGER.info("companion initialized");
    }

    private void onServerStarted(final MinecraftServer server) {
        config = configIo.load();
        summaries = null;
        sharedWaypoints = null;
        if (!config.enabled) {
            ConfluxMapMod.LOGGER.info("companion disabled by server.json (enabled=false); no HELLO replies");
            return;
        }
        summaries = new RegionSummaryService(config);
        // Corrections can use the same predictor as the client when a bundled native exists;
        // failure is non-fatal and RegionSummaryService falls back to absolute samples.
        NativeLib.init(server.getSavePath(WorldSavePath.ROOT).resolve("confluxmap"));
        if (config.shareWaypoints) {
            sharedWaypoints = loadSharedWaypoints(server);
        }
        ConfluxMapMod.LOGGER.info(
            "companion ready (shareSeed={} shareCorrections={} shareWaypoints={} maxPatchLod={} maxTilesPerRequest={})",
            config.shareSeed, config.shareCorrections, sharedWaypoints != null,
            config.maxPatchLod, config.maxTilesPerRequest
        );
    }

    private void onServerStopping(final MinecraftServer server) {
        sharedWaypointNetworking.onServerStopping();
        sharedWaypoints = null;
        summaries = null;
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

    /** Returns loaded world state, retained across runtime disable/enable for idempotent retries. */
    public SharedWaypointService sharedWaypoints() {
        return sharedWaypoints;
    }

    /** Effective capability: both configuration gates are on and world state loaded successfully. */
    public boolean sharedWaypointsEnabled() {
        return config.enabled && config.shareWaypoints && sharedWaypoints != null;
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
        if (!config.enabled) {
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
}
