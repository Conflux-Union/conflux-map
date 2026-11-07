package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.FlatBaselineS2C;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.store.ServerInstanceIdStore;
import cn.net.rms.confluxmap.core.store.WorldIdStore;
import cn.net.rms.confluxmap.core.update.GithubReleaseFetcher;
import cn.net.rms.confluxmap.core.update.UpdateCheckService;
import cn.net.rms.confluxmap.nativepredict.GpuBackend;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.server.ServerConfig;
import cn.net.rms.confluxmap.server.DatapackLangService;
import cn.net.rms.confluxmap.server.PendingLiveRemovals;
import cn.net.rms.confluxmap.server.PlayerPositionBroadcastGate;
import cn.net.rms.confluxmap.server.web.WebMapServer;
import cn.net.rms.confluxmap.server.web.WebMapPrivacyStore;
import cn.net.rms.confluxmap.server.shared.SharedWaypointIo;
import cn.net.rms.confluxmap.server.shared.SharedWaypointService;
import cn.net.rms.confluxmap.server.shared.SharedWaypointStore;
import cn.net.rms.confluxmap.server.shared.SharedWaypointValidator;
import cn.net.rms.confluxmap.server.LiveCapturePolicy;
import cn.net.rms.confluxmap.server.ServerConfigIo;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

/** Owns one Paper server's companion lifecycle and platform adapters. */
final class PaperCompanion implements Listener {
    /**
     * Unloaded-chunk overlays are rechecked once a second and dropped 30s after unload even if
     * the region file never moves: a chunk that unloads clean is never written, so its mtime
     * stays frozen and only the deadline reclaims the entry (and the summary it pins).
     */
    private static final int PENDING_SCAN_INTERVAL_TICKS = 20;
    private static final int PENDING_EXPIRY_TICKS = 600;
    private static final int PENDING_MAX_REGIONS_PER_SCAN = 512;
    enum WaypointToggleResult {
        ENABLED,
        DISABLED,
        ALREADY_ENABLED,
        ALREADY_DISABLED,
        MASTER_DISABLED,
        LOAD_FAILED,
        SAVE_FAILED,
        DISABLED_SAVE_FAILED
    }

    private record ChunkKey(PaperWorldDirectory.Entry world, int chunkX, int chunkZ) {
    }

    private static final Logger SHARED_LOGGER = LogManager.getLogger("ConfluxMap-Paper");

    private final ConfluxMapPaperPlugin plugin;
    private final ServerConfigIo configIo;
    private final PaperWorldDirectory worlds = new PaperWorldDirectory();
    private final Map<ChunkKey, Chunk> liveChunks = new LinkedHashMap<>();
    private final ExecutorService captureWorkers = new ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(PaperLiveCapturePolicy.MAX_IN_FLIGHT_CAPTURES),
        runnable -> {
            final Thread thread = new Thread(runnable, "ConfluxMap-paper-capture");
            thread.setDaemon(true);
            return thread;
        }
    );
    /**
     * Capture bookkeeping, main-thread only: the tick each chunk was last handed to a worker
     * and the captures still being summarized. Summaries are computed from immutable snapshots
     * on the worker pool and committed back on the main thread.
     */
    private final Map<ChunkKey, Long> captureTicks = new LinkedHashMap<>();
    private final Set<ChunkKey> capturesInFlight = new LinkedHashSet<>();
    private long tickCount;
    private final Map<Integer, FlatBaseline> flatBaselines = new ConcurrentHashMap<>();
    private final Map<Integer, Map<FlatBaseline, Integer>> flatCandidates = new LinkedHashMap<>();
    private ServerConfig config;
    private UUID worldId;
    private UUID instanceId;
    private long worldSeed;
    private Path primaryWorldRoot;
    private PaperCorrectionService corrections;
    // Field initializers may not forward-reference the fields above, so these two adapters
    // are declared after worlds and corrections even though they only read them at call time.
    private final PendingLiveRemovals pendingRemovals = new PendingLiveRemovals(
        (worldIndex, mcaX, mcaZ) -> {
            final PaperWorldDirectory.Entry entry = worlds.at(worldIndex);
            return entry == null
                ? 0L
                : PaperAnvilReader.mcaMtime(entry.regionDirectory(), mcaX, mcaZ);
        },
        PENDING_SCAN_INTERVAL_TICKS,
        PENDING_EXPIRY_TICKS,
        PENDING_MAX_REGIONS_PER_SCAN
    );
    private final PendingLiveRemovals.RemovalSink pendingRemovalSink = (worldIndex, chunkX, chunkZ) -> {
        final PaperWorldDirectory.Entry entry = worlds.at(worldIndex);
        if (entry != null) {
            corrections.removeLive(entry, chunkX, chunkZ);
        }
    };
    private DatapackLangService datapackLangs;
    private PaperMapColors mapColors;
    private PaperChunkLoadStateService chunkLoadStates;
    private SharedWaypointService sharedWaypoints;
    private PaperNetworking networking;
    private PaperSharedWaypointNetworking sharedNetworking;
    private PaperPluginMessageDispatcher pluginMessages;
    private BukkitTask tickTask;
    private WebMapServer webMap;
    private PaperWebMapBackend webMapBackend;
    private int webPlayerTicks;
    private WebMapPrivacyStore webMapPrivacy;
    private final PlayerPositionBroadcastGate playerPositionBroadcast =
        new PlayerPositionBroadcastGate();

    PaperCompanion(
        final ConfluxMapPaperPlugin plugin,
        final ServerConfigIo configIo
    ) {
        this.plugin = plugin;
        this.configIo = configIo;
    }

    void enable() {
        config = configIo.load();
        GpuBackend.configure(config.predictionBackend);
        for (final World world : Bukkit.getWorlds()) {
            worlds.add(world);
        }
        final World primary = primaryWorld();
        if (primary == null) {
            throw new IllegalStateException("Paper server has no loaded worlds");
        }
        primaryWorldRoot = PaperWorldDirectory.levelRoot(primary.getWorldFolder().toPath());
        worldId = WorldIdStore.loadOrCreate(primaryWorldRoot);
        // Kept beside the config rather than in the world, so a world synced to a mirror server
        // reaches it without carrying this server's identity along.
        instanceId = ServerInstanceIdStore.loadOrCreate(configIo.directory());
        worldSeed = primary.getSeed();
        webMapPrivacy = new WebMapPrivacyStore(
            primaryWorldRoot.resolve("confluxmap/webmap-hidden.txt")
        );
        try {
            webMapPrivacy.load();
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("Could not load web-map privacy preferences", e);
        }
        // Vanilla never loads a data pack's assets/ half, so biome names from custom packs are
        // only reachable by reading the level directory, exactly like the Fabric companion.
        datapackLangs = new DatapackLangService(
            () -> primaryWorldRoot.resolve("datapacks"), SHARED_LOGGER
        );
        mapColors = new PaperMapColors();
        initializeFlatBaselines(mapColors);
        corrections = new PaperCorrectionService(
            config,
            worlds,
            Bukkit.getMinecraftVersion(),
            worldSeed,
            entry -> flatBaselines.get(entry.index()),
            mapColors,
            plugin.getSLF4JLogger()
        );
        chunkLoadStates = config.enabled && config.shareChunkLoadState
            ? new PaperChunkLoadStateService() : null;
        if (config.enabled) {
            // Same game-directory rule as the Fabric companion: a loaded native library
            // must not live inside the world save, where save-restore tools hit its lock.
            NativeLib.init(plugin.getDataFolder().toPath());
            NativeLib.removeLegacyNativesUnder(primaryWorldRoot.resolve("confluxmap"));
            if (config.shareWaypoints) {
                sharedWaypoints = loadSharedWaypoints();
            }
        }
        if (config.checkForUpdates) {
            new UpdateCheckService(
                plugin.getPluginMeta().getVersion(),
                GithubReleaseFetcher.confluxMapReleases(plugin.getPluginMeta().getVersion()),
                SHARED_LOGGER
            ).checkAsync(info -> plugin.getSLF4JLogger().warn(
                "Conflux Map {} is available (installed {}). Download: {}",
                info.latestVersion(), info.currentVersion(), info.releaseUrl()
            ));
        }
        pluginMessages = new PaperPluginMessageDispatcher();
        networking = new PaperNetworking(plugin, this, pluginMessages);
        sharedNetworking = new PaperSharedWaypointNetworking(plugin, this, pluginMessages);
        networking.register();
        sharedNetworking.register();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (final PaperWorldDirectory.Entry entry : worlds.entries()) {
            plugin.getSLF4JLogger().info(
                "Paper companion dimension {} index={} region={}",
                entry.dimensionId(), entry.index(), entry.regionDirectory()
            );
            for (final Chunk chunk : entry.world().getLoadedChunks()) {
                trackLoaded(
                    entry,
                    chunk,
                    entry.preset() == WorldPreset.FLAT
                        && !flatBaselines.containsKey(entry.index())
                );
            }
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        if (config.enabled && config.webMap.enabled) {
            try {
                webMapBackend = new PaperWebMapBackend(plugin, this);
                webMap = WebMapServer.start(config.webMap, webMapBackend, configIo.directory());
                plugin.getSLF4JLogger().info(
                    "Web map listening on {}:{}",
                    config.webMap.bindAddress, config.webMap.port
                );
            } catch (final IOException e) {
                webMapBackend = null;
                plugin.getSLF4JLogger().error("Web map failed to start", e);
            }
        }
        plugin.getSLF4JLogger().info(
            "Paper companion ready (enabled={}, seed={}, corrections={}, loadState={}, waypoints={})",
            config.enabled, config.shareSeed, config.shareCorrections,
            chunkLoadStates != null, sharedWaypointsEnabled()
        );
    }

    void disable() {
        if (webMap != null) {
            webMap.close();
            webMap = null;
            webMapBackend = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        HandlerList.unregisterAll(this);
        if (networking != null) {
            networking.unregister();
        }
        if (sharedNetworking != null) {
            sharedNetworking.unregister();
        }
        if (pluginMessages != null) {
            pluginMessages.clear();
            pluginMessages = null;
        }
        if (chunkLoadStates != null) {
            chunkLoadStates.clear();
            chunkLoadStates = null;
        }
        if (corrections != null) {
            corrections.close();
            corrections = null;
        }
        capturesInFlight.clear();
        captureTicks.clear();
        captureWorkers.shutdownNow();
        try {
            captureWorkers.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (datapackLangs != null) {
            datapackLangs.shutdown();
            datapackLangs = null;
        }
        sharedWaypoints = null;
        liveChunks.clear();
        pendingRemovals.clear();
        flatBaselines.clear();
        flatCandidates.clear();
        mapColors = null;
        webMapPrivacy = null;
    }

    @EventHandler
    public void onWorldLoad(final WorldLoadEvent event) {
        worlds.add(event.getWorld());
        final PaperWorldDirectory.Entry entry = worlds.find(event.getWorld());
        if (entry == null) {
            return;
        }
        if (entry.preset() == WorldPreset.FLAT && mapColors != null) {
            PaperWorldMetadata.flatBaseline(entry.world(), mapColors::mapColorId).ifPresent(
                baseline -> flatBaselines.put(entry.index(), baseline)
            );
        }
        for (final Chunk chunk : event.getWorld().getLoadedChunks()) {
            trackLoaded(
                entry,
                chunk,
                entry.preset() == WorldPreset.FLAT
                    && !flatBaselines.containsKey(entry.index())
            );
        }
    }

    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        final PaperWorldDirectory.Entry entry = worlds.find(event.getWorld());
        trackLoaded(
            entry,
            event.getChunk(),
            entry != null && entry.preset() == WorldPreset.FLAT
                && !flatBaselines.containsKey(entry.index())
        );
    }

    @EventHandler
    public void onChunkUnload(final ChunkUnloadEvent event) {
        final PaperWorldDirectory.Entry entry = worlds.find(event.getWorld());
        if (entry == null) {
            return;
        }
        final Chunk chunk = event.getChunk();
        final ChunkKey key = new ChunkKey(entry, chunk.getX(), chunk.getZ());
        // An unload capture is the chunk's last chance to record its final in-memory state, and
        // it is only worth the main-thread cost while somebody is watching the area: unwatched
        // chunks keep their previous summary until the region mtime or the removal deadline
        // retires it, and the saved region file is the durable source anyway.
        if (corrections.liveSummaryDemanded(
            entry, chunk.getX(), chunk.getZ(), System.nanoTime()
        )) {
            captureAsync(entry, chunk);
        }
        liveChunks.remove(key);
        captureTicks.remove(key);
        pendingRemovals.onUnload(
            entry.index(),
            chunk.getX(),
            chunk.getZ(),
            PaperAnvilReader.regionMtime(
                entry.regionDirectory(), chunk.getX(), chunk.getZ()
            ),
            event.isSaveChunk(),
            tickCount
        );
        if (chunkLoadStates != null) {
            chunkLoadStates.onChunkUnload(entry, chunk);
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final UUID playerId = event.getPlayer().getUniqueId();
        networking.disconnect(playerId);
        sharedNetworking.disconnect(playerId);
        pluginMessages.disconnect(playerId);
    }

    @EventHandler
    public void onPlayerRegisterChannel(final PlayerRegisterChannelEvent event) {
        pluginMessages.channelRegistered(
            PaperPluginMessageDispatcher.recipient(plugin, event.getPlayer()),
            event.getChannel()
        );
    }

    boolean isEnabled() {
        return config != null && config.enabled;
    }

    boolean webMapHidden(final UUID playerId) {
        return webMapPrivacy != null && webMapPrivacy.hidden(playerId);
    }

    boolean setWebMapHidden(final UUID playerId, final boolean hidden) {
        if (webMapPrivacy == null) return false;
        try {
            webMapPrivacy.setHidden(playerId, hidden);
            return true;
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("Could not persist web-map privacy preference", e);
            return false;
        }
    }

    ServerConfig config() {
        return config;
    }

    UUID worldId() {
        return worldId;
    }

    /** Identity of this server installation, unaffected by copying the world it hosts. */
    UUID instanceId() {
        return instanceId;
    }

    long worldSeed() {
        return worldSeed;
    }

    PaperWorldDirectory worlds() {
        return worlds;
    }

    DatapackLangService datapackLangs() {
        return datapackLangs;
    }

    PaperCorrectionService corrections() {
        return corrections;
    }

    PaperChunkLoadStateService chunkLoadStates() {
        return chunkLoadStates;
    }

    boolean chunkLoadStatesEnabled() {
        return isEnabled() && config.shareChunkLoadState && chunkLoadStates != null;
    }

    SharedWaypointService sharedWaypoints() {
        return sharedWaypoints;
    }

    boolean sharedWaypointsEnabled() {
        return isEnabled() && config.shareWaypoints && sharedWaypoints != null;
    }

    void onSharedWaypointCommandMutation(
        final SharedWaypointService.MutationResult mutation
    ) {
        sharedNetworking.commandMutation(mutation);
    }

    List<FlatBaselineS2C.Entry> flatBaselines() {
        final List<FlatBaselineS2C.Entry> result = new ArrayList<>();
        flatBaselines.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> result.add(new FlatBaselineS2C.Entry(
                entry.getKey(), entry.getValue()
            )));
        return List.copyOf(result);
    }

    synchronized WaypointToggleResult enableSharedWaypoints() {
        if (!isEnabled()) {
            return WaypointToggleResult.MASTER_DISABLED;
        }
        if (sharedWaypointsEnabled()) {
            return WaypointToggleResult.ALREADY_ENABLED;
        }
        SharedWaypointService candidate = sharedWaypoints;
        if (candidate == null) {
            candidate = loadSharedWaypoints();
            if (candidate == null) {
                return WaypointToggleResult.LOAD_FAILED;
            }
        }
        final boolean previous = config.shareWaypoints;
        config.shareWaypoints = true;
        if (!configIo.saveAtomically(config)) {
            config.shareWaypoints = previous;
            return WaypointToggleResult.SAVE_FAILED;
        }
        sharedWaypoints = candidate;
        sharedNetworking.featureStateChanged();
        return WaypointToggleResult.ENABLED;
    }

    synchronized WaypointToggleResult disableSharedWaypoints() {
        final boolean wasEnabled = config.shareWaypoints || sharedWaypointsEnabled();
        config.shareWaypoints = false;
        final boolean saved = configIo.saveAtomically(config);
        if (!wasEnabled) {
            return saved
                ? WaypointToggleResult.ALREADY_DISABLED
                : WaypointToggleResult.DISABLED_SAVE_FAILED;
        }
        sharedNetworking.featureStateChanged();
        return saved ? WaypointToggleResult.DISABLED : WaypointToggleResult.DISABLED_SAVE_FAILED;
    }

    private void tick() {
        tickCount++;
        if (!isEnabled()) {
            return;
        }
        refreshLiveChunks();
        pendingRemovals.tick(tickCount, pendingRemovalSink);
        corrections.tick();
        if (chunkLoadStates != null) {
            chunkLoadStates.tick();
        }
        sharedNetworking.tick();
        if (playerPositionBroadcast.tick(config.allowEntityRadar)) {
            networking.broadcastPlayerPositions();
        }
        if (webMapBackend != null && (config.webMap.sharePlayers || sharedWaypointsEnabled())
            && ++webPlayerTicks >= 40) {
            webPlayerTicks = 0;
            webMapBackend.updatePlayers();
        }
    }

    private void trackLoaded(
        final PaperWorldDirectory.Entry entry,
        final Chunk chunk,
        final boolean captureNow
    ) {
        if (entry == null || chunk == null) {
            return;
        }
        final ChunkKey key = new ChunkKey(entry, chunk.getX(), chunk.getZ());
        liveChunks.put(key, chunk);
        pendingRemovals.onChunkReload(entry.index(), chunk.getX(), chunk.getZ());
        if (chunkLoadStates != null) {
            chunkLoadStates.onChunkLoad(entry, chunk);
        }
        if (captureNow && isEnabled()) {
            captureAsync(entry, chunk);
        }
    }

    private void refreshLiveChunks() {
        final int captureBudget = LiveCapturePolicy.perTickCaptures(
            config.maxChunkSummariesPerSecond
        );
        final int available = liveChunks.size();
        final int inspectionBudget = Math.min(available, LiveCapturePolicy.MAX_LIVE_INSPECTIONS_PER_TICK);
        final long nowNanos = System.nanoTime();
        int captured = 0;
        for (int inspected = 0;
             inspected < inspectionBudget && captured < captureBudget;
             inspected++) {
            final Iterator<Map.Entry<ChunkKey, Chunk>> iterator = liveChunks.entrySet().iterator();
            final Map.Entry<ChunkKey, Chunk> entry = iterator.next();
            iterator.remove();
            liveChunks.put(entry.getKey(), entry.getValue());
            if (!corrections.liveSummaryDemanded(
                entry.getKey().world(),
                entry.getKey().chunkX(),
                entry.getKey().chunkZ(),
                nowNanos
            )) {
                continue;
            }
            if (!PaperLiveCapturePolicy.shouldScanCapture(
                corrections.liveSummary(
                    entry.getKey().world(), entry.getKey().chunkX(), entry.getKey().chunkZ()
                ) != null,
                ticksSinceCaptured(entry.getKey())
            )) {
                continue;
            }
            if (!captureAsync(entry.getKey().world(), entry.getValue())) {
                continue;
            }
            captured++;
            if (System.nanoTime() - nowNanos >= PaperLiveCapturePolicy.SNAPSHOT_BUDGET_NANOS) {
                break;
            }
        }
    }

    private long ticksSinceCaptured(final ChunkKey key) {
        final Long capturedAt = captureTicks.get(key);
        return capturedAt == null ? Long.MAX_VALUE : tickCount - capturedAt;
    }

    /**
     * Takes the chunk snapshot on the main thread (the only part of a capture that must happen
     * while the chunk is still loaded) and hands the immutable snapshot to a worker for
     * summarization; the commit lands back on the main thread.
     *
     * @return whether a capture was scheduled
     */
    private boolean captureAsync(final PaperWorldDirectory.Entry entry, final Chunk chunk) {
        if (!isEnabled() || !chunk.isLoaded()) {
            return false;
        }
        final ChunkKey key = new ChunkKey(entry, chunk.getX(), chunk.getZ());
        if (capturesInFlight.contains(key)
            || capturesInFlight.size() >= PaperLiveCapturePolicy.MAX_IN_FLIGHT_CAPTURES) {
            return false;
        }
        try {
            final ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
            final long revision = Math.max(1L, entry.world().getFullTime());
            final int minHeight = entry.world().getMinHeight();
            final int maxHeight = entry.world().getMaxHeight();
            captureTicks.put(key, tickCount);
            capturesInFlight.add(key);
            try {
                captureWorkers.execute(
                    () -> summarizeCapture(entry, key, snapshot, revision, minHeight, maxHeight)
                );
            } catch (final RejectedExecutionException e) {
                capturesInFlight.remove(key);
                return false;
            }
            return true;
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn(
                "Could not capture loaded chunk {},{} in {}",
                chunk.getX(), chunk.getZ(), entry.dimensionId(), e
            );
            return false;
        }
    }

    private void summarizeCapture(
        final PaperWorldDirectory.Entry entry,
        final ChunkKey key,
        final ChunkSnapshot snapshot,
        final long revision,
        final int minHeight,
        final int maxHeight
    ) {
        final SummaryCodec.Chunk summary;
        try {
            summary = corrections.summarizeLive(snapshot, revision, minHeight, maxHeight);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn(
                "Could not summarize loaded chunk {},{} in {}",
                key.chunkX(), key.chunkZ(), entry.dimensionId(), e
            );
            capturesInFlight.remove(key);
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                capturesInFlight.remove(key);
                commitCapture(entry, key, summary);
            });
        } catch (final IllegalPluginAccessException ignored) {
            // The plugin is disabling and disable() clears the in-flight set wholesale.
        }
    }

    private void commitCapture(
        final PaperWorldDirectory.Entry entry,
        final ChunkKey key,
        final SummaryCodec.Chunk summary
    ) {
        if (!isEnabled()) {
            return;
        }
        try {
            corrections.putLiveIfNewer(entry, key.chunkX(), key.chunkZ(), summary);
            learnFlatBaseline(entry, summary);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn(
                "Could not store live summary for chunk {},{} in {}",
                key.chunkX(), key.chunkZ(), entry.dimensionId(), e
            );
        }
    }

    private void learnFlatBaseline(
        final PaperWorldDirectory.Entry entry,
        final SummaryCodec.Chunk summary
    ) {
        if (entry.preset() != WorldPreset.FLAT || summary == null || !summary.generated()) {
            return;
        }
        final Map<FlatBaseline, Integer> candidates = flatCandidates.computeIfAbsent(
            entry.index(), ignored -> new LinkedHashMap<>()
        );
        for (int z = 2; z < 16; z += 4) {
            for (int x = 2; x < 16; x += 4) {
                final SummaryCodec.Column column = summary.columns()[z * 16 + x];
                if (column == null || column.kind() == SurfaceKind.UNKNOWN.ordinal()) {
                    continue;
                }
                final FlatBaseline candidate = new FlatBaseline(
                    column.biomeId(), column.surfaceY(), column.kind(), column.mapColorId(),
                    column.fluidDepth(), column.materialId()
                );
                if (candidates.size() < 64 || candidates.containsKey(candidate)) {
                    candidates.merge(candidate, 1, Integer::sum);
                }
            }
        }
        candidates.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .ifPresent(best -> flatBaselines.put(entry.index(), best.getKey()));
    }

    private void initializeFlatBaselines(final PaperMapColors mapColors) {
        for (final PaperWorldDirectory.Entry entry : worlds.entries()) {
            if (entry.preset() != WorldPreset.FLAT) {
                continue;
            }
            PaperWorldMetadata.flatBaseline(entry.world(), mapColors::mapColorId).ifPresent(
                baseline -> flatBaselines.put(entry.index(), baseline)
            );
        }
    }

    private SharedWaypointService loadSharedWaypoints() {
        final SharedWaypointIo io = new SharedWaypointIo(
            primaryWorldRoot, instanceId.toString(), SHARED_LOGGER
        );
        try {
            final Map<DimensionId, SharedWaypointValidator.HeightRange> dimensions =
                new LinkedHashMap<>();
            for (final PaperWorldDirectory.Entry entry : worlds.entries()) {
                dimensions.put(
                    entry.parsedDimensionId(),
                    new SharedWaypointValidator.HeightRange(
                        entry.world().getMinHeight(), entry.world().getMaxHeight()
                    )
                );
            }
            final SharedWaypointValidator validator = new SharedWaypointValidator(dimensions);
            final SharedWaypointStore store = new SharedWaypointStore(
                SharedWaypointService.sanitizeLoaded(io.load(), validator, SHARED_LOGGER)
            );
            return new SharedWaypointService(
                store,
                io,
                validator,
                Clock.systemUTC(),
                UUID::randomUUID,
                new SharedWaypointService.Limits(
                    config.maxSharedWaypointsPerWorld,
                    config.maxSharedWaypointsPerPlayer,
                    config.sharedWaypointMutationsPerMinute
                ),
                config.sharedWaypointAccessPolicy(),
                event -> plugin.getSLF4JLogger().info(
                    "Shared waypoint operation={} actor={} action={} status={} error={} waypoint={} revision={}",
                    event.operationId(), event.actorId(), event.action(), event.status(), event.error(),
                    event.waypointId(), event.revision()
                ),
                SHARED_LOGGER
            );
        } catch (final SharedWaypointIo.UnsupportedSchemaVersionException e) {
            plugin.getSLF4JLogger().error(
                "Shared waypoints disabled: {} has unsupported schema {}",
                io.file(), e.schemaVersion()
            );
        } catch (final IOException | RuntimeException e) {
            plugin.getSLF4JLogger().error(
                "Shared waypoints disabled: could not initialize {}", io.file(), e
            );
        }
        return null;
    }

    private World primaryWorld() {
        return worlds.entries().stream()
            .map(PaperWorldDirectory.Entry::world)
            .min(Comparator.comparingInt(world ->
                world.getEnvironment() == World.Environment.NORMAL ? 0 : 1
            ))
            .orElse(null);
    }
}
