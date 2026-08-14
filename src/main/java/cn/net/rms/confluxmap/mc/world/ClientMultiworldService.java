package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.cache.MapCacheMigration;
import cn.net.rms.confluxmap.core.cache.RegionFileCodec;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldObservation;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileResolver;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldResolution;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldSignalHasher;
import cn.net.rms.confluxmap.core.multiworld.ServerAliasResolver;
import cn.net.rms.confluxmap.core.multiworld.TerrainFingerprintMatcher;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureService;
import com.mojang.brigadier.tree.CommandNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;

/**
 * Client-only upstream world resolver for proxy addresses. A structurally verified Velocity
 * /server response provides an exact registered-server identity. Otherwise, the seed hash,
 * hashed registry/command/brand metadata and existing terrain cache form a conservative fallback.
 * Ambiguous observations intentionally return no identity so the normal session lifecycle
 * suspends every map writer until the user makes a choice.
 */
public final class ClientMultiworldService {
    private static final int SIGNAL_REFRESH_TICKS = 20;
    private static final int VELOCITY_QUERY_TIMEOUT_TICKS = 40;

    private final MinecraftClient client;
    private final CompanionSession companion;
    private final ClientWorldProfileResolver resolver;
    private final Path cacheRoot;
    private final Executor io;
    private final ServerAliasResolver aliases;
    private final VelocityServerIdentityQuery velocityQuery = new VelocityServerIdentityQuery(
        VELOCITY_QUERY_TIMEOUT_TICKS
    );

    private OptionalLong seedHash = OptionalLong.empty();
    private OptionalLong previousSeedHash = OptionalLong.empty();
    private Map<String, String> signals = Map.of();
    private ClientWorldResolution resolution = ClientWorldResolution.collecting();
    private String address;
    private String serverId;
    private String lockedProfileId;
    private OptionalLong lockedSeedHash = OptionalLong.empty();
    private String velocityLegacyProfileId;
    private boolean gameJoinObserved;
    private boolean proxyWorldJoin;
    private int signalTicks;
    private long clientTick;
    private long observationGeneration;
    private boolean terrainAttempted;
    private boolean ambiguityNotified;
    private volatile boolean migrationInProgress;
    private ChunkCaptureService chunkCapture;
    private Supplier<String> openMapKeyDisplayName;

    public ClientMultiworldService(
        final MinecraftClient client,
        final CompanionSession companion,
        final ClientWorldProfileResolver resolver,
        final Path cacheRoot,
        final Executor io
    ) {
        this(client, companion, resolver, cacheRoot, io, null);
    }

    /**
     * @param aliases maps the typed address onto the namespace owning its data, so profiles are
     *                listed under the same server id the session stores under; null keeps the
     *                raw address
     */
    public ClientMultiworldService(
        final MinecraftClient client,
        final CompanionSession companion,
        final ClientWorldProfileResolver resolver,
        final Path cacheRoot,
        final Executor io,
        final ServerAliasResolver aliases
    ) {
        this.client = client;
        this.companion = companion;
        this.resolver = resolver;
        this.cacheRoot = cacheRoot;
        this.io = io;
        this.aliases = aliases;
    }

    public void register() {
        ClientWorldIdentityHandler.bind(this);
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tickSignals());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, ignored) -> onDisconnect(handler));
    }

    public void bindChunkCapture(final ChunkCaptureService service) {
        chunkCapture = service;
    }

    public void bindOpenMapKeyDisplayName(final Supplier<String> supplier) {
        openMapKeyDisplayName = Objects.requireNonNull(supplier, "supplier");
    }

    public void onGameJoin(final long observedSeedHash) {
        final OptionalLong departedSeedHash = seedHash;
        final boolean switchedUpstreamWorld = gameJoinObserved;
        resetObservation();
        gameJoinObserved = true;
        previousSeedHash = switchedUpstreamWorld ? departedSeedHash : OptionalLong.empty();
        proxyWorldJoin = switchedUpstreamWorld;
        seedHash = OptionalLong.of(observedSeedHash);
        velocityQuery.arm(!switchedUpstreamWorld);
        if (switchedUpstreamWorld) {
            ConfluxMapMod.LOGGER.info(
                "Client proxy world transition observed (seedChanged={})",
                departedSeedHash.isEmpty() || departedSeedHash.getAsLong() != observedSeedHash
            );
        }
    }

    public void onRespawn(final long observedSeedHash) {
        final boolean seedChanged = seedHash.isEmpty() || seedHash.getAsLong() != observedSeedHash;
        if (seedChanged) {
            previousSeedHash = seedHash;
            proxyWorldJoin = gameJoinObserved;
            clearProfileLock();
            resolution = ClientWorldResolution.collecting();
        }
        seedHash = OptionalLong.of(observedSeedHash);
        signals = Map.of();
        signalTicks = 0;
        terrainAttempted = false;
        ambiguityNotified = false;
        observationGeneration++;
    }

    /** Resolves only the client-owned fallback. Companion world UUIDs remain authoritative. */
    public Optional<WorldIdentity> resolve(final String currentAddress) {
        observeAddress(currentAddress);
        if (shouldAwaitVelocityIdentity()) {
            return Optional.empty();
        }
        final ClientWorldResolution currentResolution = resolveProfile(currentAddress);
        if (currentResolution.state() != ClientWorldResolution.State.RESOLVED) {
            notifyAmbiguity();
            tryTerrainMatch();
            return Optional.empty();
        }
        return Optional.of(WorldIdentity.multiplayer(
            currentAddress, currentResolution.profile().storageId()
        ));
    }

    /** Applies the profile state machine without triggering UI or terrain fallback side effects. */
    ClientWorldResolution resolveProfile(final String currentAddress) {
        observeAddress(currentAddress);
        if (velocityQuery.blocksFallback() && supportsVelocityServerQuery()) {
            resolution = ClientWorldResolution.collecting();
            return resolution;
        }
        if (lockedProfileId != null && resolution.state() != ClientWorldResolution.State.RESOLVED) {
            resolution = resolver.select(serverId, lockedProfileId, observation());
        } else if (resolution.state() != ClientWorldResolution.State.RESOLVED) {
            resolution = proxyWorldJoin
                ? resolver.resolveAfterProxyWorldJoin(serverId, previousSeedHash, observation())
                : resolver.resolve(serverId, observation());
        }
        if (resolution.state() == ClientWorldResolution.State.RESOLVED) {
            lockProfile(resolution.profile());
        }
        return resolution;
    }

    public boolean canManageProfiles() {
        if (client == null || client.world == null || client.isInSingleplayer()) {
            return false;
        }
        // The companion owns the active cache identity, but the client profile list is still the
        // recovery path for data created before the companion was installed. Prime the address
        // namespace here because the normal session tracker intentionally bypasses this service
        // while the companion is active.
        if (companionWorldIdentityAuthoritative()) {
            ensureAddressObserved();
        }
        return true;
    }

    /**
     * Storage namespace the current connection reads and writes under, empty when not connected to
     * a server. This is the alias-resolved id, not the typed address.
     */
    public Optional<String> currentServerId() {
        if (client == null || client.world == null || client.isInSingleplayer()) {
            return Optional.empty();
        }
        ensureAddressObserved();
        return Optional.ofNullable(serverId);
    }

    public boolean needsSelection() {
        return canManageProfiles() && resolution.state() == ClientWorldResolution.State.AMBIGUOUS;
    }

    public List<ClientWorldProfile> profiles() {
        ensureAddressObserved();
        return serverId == null ? List.of() : resolver.profiles(serverId);
    }

    /** The companion's UUID is authoritative for active map storage while this flag is true. */
    public boolean companionWorldIdentityAuthoritative() {
        return companion.state() == CompanionSession.State.ACTIVE;
    }

    /** Returns the server-owned world identity currently controlling map storage, if any. */
    public Optional<WorldIdentity> companionWorldIdentity() {
        if (!companionWorldIdentityAuthoritative()) {
            return Optional.empty();
        }
        ensureAddressObserved();
        return address == null ? Optional.empty() : companion.resolveWorldIdentity(address);
    }

    /**
     * Name the player gave the world the companion is currently serving. Empty when unnamed, when
     * no companion is active, or when there is no server connection; callers fall back to
     * {@link #companionWorldOrdinal()}. The raw world UUID is an implementation detail players
     * have no use for, so it never reaches the UI.
     */
    public Optional<String> companionWorldName() {
        final String worldId = companion.companionWorldId();
        if (worldId == null) {
            return Optional.empty();
        }
        return currentServerId().flatMap(serverId -> resolver.serverWorldName(serverId, worldId));
    }

    /** 1-based label for an unnamed companion world; 1 when this server has recorded none yet. */
    public int companionWorldOrdinal() {
        final String worldId = companion.companionWorldId();
        if (aliases == null || worldId == null) {
            return 1;
        }
        return currentServerId()
            .map(serverId -> Math.max(1, aliases.worldOrdinal(serverId, worldId)))
            .orElse(1);
    }

    /** Renames the active companion world, or clears the name when {@code name} is blank. */
    public void renameCompanionWorld(final String name) {
        final String worldId = companion.companionWorldId();
        if (worldId == null) {
            return;
        }
        currentServerId().ifPresent(serverId -> resolver.nameServerWorld(serverId, worldId, name));
    }

    /** Session writes stay suspended while an explicit cache migration drains the old cache. */
    public boolean migrationInProgress() {
        return migrationInProgress;
    }

    /** Whether this profile was recorded on the seed observed for the current server world. */
    public boolean profileMatchesCurrentSeed(final String profileId) {
        if (seedHash.isEmpty()) {
            return false;
        }
        return profiles().stream()
            .filter(profile -> profile.id().equals(profileId))
            .findFirst()
            .map(profile -> profile.matchesSeed(seedHash.getAsLong()))
            .orElse(false);
    }

    public enum ProfileMigrationStatus {
        READY,
        NOT_CONNECTED,
        COMPANION_REQUIRED,
        SEED_UNKNOWN,
        SEED_MISMATCH,
        SOURCE_IS_TARGET,
        ALREADY_RUNNING
    }

    public record ProfileMigrationPreparation(
        ProfileMigrationStatus status,
        String profileId,
        WorldIdentity source,
        WorldIdentity target
    ) {
        public boolean ready() {
            return status == ProfileMigrationStatus.READY;
        }
    }

    /** Validates an explicit source selection without moving any data. */
    public ProfileMigrationPreparation prepareProfileMigration(final String profileId) {
        if (migrationInProgress) {
            return new ProfileMigrationPreparation(
                ProfileMigrationStatus.ALREADY_RUNNING, profileId, null, null
            );
        }
        if (client == null || client.world == null || client.isInSingleplayer() || serverId == null) {
            return new ProfileMigrationPreparation(
                ProfileMigrationStatus.NOT_CONNECTED, profileId, null, null
            );
        }
        if (!companionWorldIdentityAuthoritative()) {
            return new ProfileMigrationPreparation(
                ProfileMigrationStatus.COMPANION_REQUIRED, profileId, null, null
            );
        }
        if (seedHash.isEmpty()) {
            return new ProfileMigrationPreparation(
                ProfileMigrationStatus.SEED_UNKNOWN, profileId, null, null
            );
        }
        final ClientWorldProfile profile = profiles().stream()
            .filter(candidate -> candidate.id().equals(profileId))
            .findFirst()
            .orElse(null);
        if (profile == null) {
            return new ProfileMigrationPreparation(
                ProfileMigrationStatus.NOT_CONNECTED, profileId, null, null
            );
        }
        if (!profile.matchesSeed(seedHash.getAsLong())) {
            return new ProfileMigrationPreparation(
                ProfileMigrationStatus.SEED_MISMATCH, profileId, null, null
            );
        }
        final WorldIdentity source = WorldIdentity.multiplayer(address, profile.storageId());
        final WorldIdentity target = companion.resolveWorldIdentity(address).orElse(null);
        if (target == null) {
            return new ProfileMigrationPreparation(
                ProfileMigrationStatus.COMPANION_REQUIRED, profileId, null, null
            );
        }
        if (source.equals(target)) {
            return new ProfileMigrationPreparation(
                ProfileMigrationStatus.SOURCE_IS_TARGET, profileId, source, target
            );
        }
        migrationInProgress = true;
        return new ProfileMigrationPreparation(
            ProfileMigrationStatus.READY, profileId, source, target
        );
    }

    /** Executes a previously validated, user-confirmed cache migration on the map IO executor. */
    public CompletableFuture<MapCacheMigration.Result> executeProfileMigration(
        final ProfileMigrationPreparation preparation
    ) {
        if (preparation == null || !preparation.ready()) {
            throw new IllegalArgumentException("profile migration was not prepared");
        }
        try {
            return CompletableFuture.supplyAsync(
                () -> MapCacheMigration.merge(
                    cacheRoot, preparation.source(), preparation.target(), ConfluxMapMod.LOGGER
                ),
                io
            ).whenComplete((ignored, error) -> migrationInProgress = false);
        } catch (final RuntimeException error) {
            migrationInProgress = false;
            throw error;
        }
    }

    /** Storage identity owned by one profile shown in the current server's profile manager. */
    public WorldIdentity worldIdentity(final ClientWorldProfile profile) {
        requireConnection();
        if (profiles().stream().noneMatch(candidate -> candidate.id().equals(profile.id()))) {
            throw new IllegalArgumentException("profile does not belong to the current server");
        }
        return WorldIdentity.multiplayer(address, profile.storageId());
    }

    public Optional<ClientWorldProfile> currentProfile() {
        return resolution.state() == ClientWorldResolution.State.RESOLVED
            ? Optional.of(resolution.profile())
            : Optional.empty();
    }

    public void select(final String profileId) {
        requireConnection();
        resolution = resolver.select(serverId, profileId, observation());
        lockProfile(resolution.profile());
        observationGeneration++;
    }

    public void createAndSelect(final String displayName) {
        requireConnection();
        resolution = resolver.createAndSelect(serverId, displayName, observation());
        lockProfile(resolution.profile());
        observationGeneration++;
    }

    public void rename(final String profileId, final String displayName) {
        requireConnection();
        resolver.rename(serverId, profileId, displayName);
    }

    public void clearBindings(final String profileId) {
        requireConnection();
        resolver.clearBindings(serverId, profileId);
        if (currentProfile().map(ClientWorldProfile::id).filter(profileId::equals).isPresent()) {
            clearProfileLock();
            resolution = ClientWorldResolution.ambiguous();
            terrainAttempted = false;
            ambiguityNotified = false;
            observationGeneration++;
            notifyAmbiguity();
        }
    }

    private void tickSignals() {
        clientTick++;
        if (client.world == null || client.player == null || client.getNetworkHandler() == null || client.isInSingleplayer()) {
            return;
        }
        if (++signalTicks < SIGNAL_REFRESH_TICKS) {
            return;
        }
        signalTicks = 0;
        final Map<String, String> observed = collectSignals();
        observeSignals(observed);
    }

    /** Applies one completed client-signal sample without changing an already locked visit. */
    void observeSignals(final Map<String, String> observed) {
        if (observed.equals(signals)) {
            return;
        }
        signals = observed;
        terrainAttempted = false;
        observationGeneration++;
        if (serverId != null) {
            if (velocityQuery.blocksFallback() && supportsVelocityServerQuery()) {
                resolution = ClientWorldResolution.collecting();
                return;
            }
            if (lockedProfileId != null && lockedSeedHash.equals(seedHash)) {
                resolution = resolver.select(serverId, lockedProfileId, observation());
            } else {
                resolution = proxyWorldJoin
                    ? resolver.resolveAfterProxyWorldJoin(serverId, previousSeedHash, observation())
                    : resolver.resolve(serverId, observation());
                if (resolution.state() == ClientWorldResolution.State.RESOLVED) {
                    lockProfile(resolution.profile());
                }
            }
            notifyAmbiguity();
            tryTerrainMatch();
        }
    }

    /** Consumes only the response sequence from the currently pending client-issued query. */
    boolean onVelocityServerMessage(
        final Optional<String> velocityServerName,
        final boolean currentServerNotice
    ) {
        final VelocityServerIdentityQuery.Response response = velocityQuery.observe(
            velocityServerName,
            currentServerNotice
        );
        response.match().ifPresent(match -> {
            if (serverId == null) {
                return;
            }
            resolution = resolver.resolveVelocityServer(
                serverId,
                match.serverName(),
                observation(),
                velocityLegacyProfileId,
                match.mayAdoptLegacyProfile()
            );
            velocityLegacyProfileId = null;
            terrainAttempted = false;
            ambiguityNotified = false;
            observationGeneration++;
            if (resolution.state() == ClientWorldResolution.State.RESOLVED) {
                lockProfile(resolution.profile());
            } else {
                notifyAmbiguity();
                tryTerrainMatch();
            }
        });
        return response.consumed();
    }

    private Map<String, String> collectSignals() {
        final Map<String, String> observed = new LinkedHashMap<>();
        //#if MC>=260100
        //$$ final String brand = client.getConnection().serverBrand();
        //#elseif MC>=12100
        //$$ final String brand = client.getNetworkHandler().getBrand();
        //#else
        final String brand = client.player.getServerBrand();
        //#endif
        if (brand != null && !brand.isBlank()) {
            observed.put("brand", ClientWorldSignalHasher.hash(brand));
        }

        final List<String> commands = client.getNetworkHandler().getCommandDispatcher().getRoot().getChildren().stream()
            .map(CommandNode::getName)
            .toList();
        if (!commands.isEmpty()) {
            observed.put("commands", ClientWorldSignalHasher.hashSorted(commands));
        }

        final List<String> biomes = Regs.biomes(client.world).getIds().stream()
            .map(Identifier::toString)
            .toList();
        if (!biomes.isEmpty()) {
            observed.put("biomes", ClientWorldSignalHasher.hashSorted(biomes));
        }

        final Identifier dimension = client.world.getRegistryKey().getValue();
        observed.put("dimension", ClientWorldSignalHasher.hash(dimension.toString()));
        observed.put("dimension_type", ClientWorldSignalHasher.hash(
            client.world.getDimension().hasCeiling() + ":"
                + client.world.getDimension().hasSkyLight()
        ));
        return Map.copyOf(observed);
    }

    private void tryTerrainMatch() {
        final ChunkCaptureService capture = chunkCapture;
        if (terrainAttempted || proxyWorldJoin || capture == null || serverId == null
            || resolution.state() != ClientWorldResolution.State.AMBIGUOUS) {
            return;
        }
        final List<ClientWorldProfile> profiles = resolver.profiles(serverId);
        if (profiles.size() < 2 || client.world == null) {
            return;
        }
        final MapLayer layer = terrainProbeLayer();
        final List<ChunkSnapshot> probes = capture.probeNearest(layer, TerrainFingerprintMatcher.MIN_CHUNKS);
        if (probes.size() < TerrainFingerprintMatcher.MIN_CHUNKS) {
            return;
        }
        terrainAttempted = true;
        final long generation = observationGeneration;
        final String expectedServer = serverId;
        final String dimension = DimensionId.of(
            client.world.getRegistryKey().getValue().getNamespace(),
            client.world.getRegistryKey().getValue().getPath()
        ).fileName();
        io.execute(() -> {
            final List<TerrainFingerprintMatcher.Candidate> candidates = loadTerrainCandidates(
                expectedServer, dimension, layer, probes, profiles
            );
            final TerrainFingerprintMatcher.Result result = TerrainFingerprintMatcher.match(probes, candidates);
            result.profileId().ifPresent(profileId -> client.execute(() -> {
                if (generation != observationGeneration || !expectedServer.equals(serverId)
                    || resolution.state() != ClientWorldResolution.State.AMBIGUOUS) {
                    return;
                }
                resolution = resolver.select(serverId, profileId, observation());
                lockProfile(resolution.profile());
                observationGeneration++;
                ConfluxMapMod.LOGGER.info(
                    "Matched client world profile {} from cached terrain (score={}, gap={})",
                    profileId, result.bestScore(), result.bestScore() - result.runnerUpScore()
                );
            }));
        });
    }

    private MapLayer terrainProbeLayer() {
        switch (LayerSelector.classify(client.world.getDimension())) {
            case HAS_CEILING:
                return MapLayer.NETHER_CEILING;
            case NO_SKY_NO_CEILING:
                return MapLayer.END_SURFACE;
            default:
                return MapLayer.SURFACE;
        }
    }

    private List<TerrainFingerprintMatcher.Candidate> loadTerrainCandidates(
        final String expectedServer,
        final String dimension,
        final MapLayer layer,
        final List<ChunkSnapshot> probes,
        final List<ClientWorldProfile> profiles
    ) {
        final List<TerrainFingerprintMatcher.Candidate> candidates = new ArrayList<>();
        for (final ClientWorldProfile profile : profiles) {
            final Map<TerrainFingerprintMatcher.RegionPos, RegionFileCodec.RegionData> regions = new LinkedHashMap<>();
            for (final ChunkSnapshot probe : probes) {
                final TerrainFingerprintMatcher.RegionPos region = new TerrainFingerprintMatcher.RegionPos(
                    probe.chunkX >> 4, probe.chunkZ >> 4
                );
                if (regions.containsKey(region)) {
                    continue;
                }
                final Path file = cacheRoot.resolve(expectedServer)
                    .resolve(profile.storageId())
                    .resolve(dimension)
                    .resolve(layer.cacheId())
                    .resolve("r." + region.x() + "." + region.z() + ".cfr");
                try (InputStream input = Files.newInputStream(file)) {
                    regions.put(region, RegionFileCodec.decode(
                        input, region.x(), region.z(), layer.type().ordinal()
                    ));
                } catch (final IOException | RegionFileCodec.RegionFileException ignored) {
                    // Recognition is read-only and best-effort; cache ownership remains with RegionDiskCache.
                }
            }
            candidates.add(new TerrainFingerprintMatcher.Candidate(profile.id(), regions));
        }
        return candidates;
    }

    private ClientWorldObservation observation() {
        return new ClientWorldObservation(seedHash, signals);
    }

    private void notifyAmbiguity() {
        if (ambiguityNotified || resolution.state() != ClientWorldResolution.State.AMBIGUOUS
            || client.player == null) {
            return;
        }
        ambiguityNotified = true;
        //#if MC>=260100
        //$$ client.player.sendSystemMessage(
        //$$     Texts.translatable(
        //$$         "confluxmap.client_world.ambiguous_chat", openMapKeyDisplayName.get()
        //$$     ).withStyle(ChatFormatting.YELLOW)
        //$$ );
        //#else
        client.player.sendMessage(
            Texts.translatable(
                "confluxmap.client_world.ambiguous_chat", openMapKeyDisplayName.get()
            ).formatted(Formatting.YELLOW),
            false
        );
        //#endif
    }

    private void requireConnection() {
        if (serverId == null) {
            throw new IllegalStateException("not connected to a multiplayer server");
        }
    }

    private void resetObservation() {
        seedHash = OptionalLong.empty();
        previousSeedHash = OptionalLong.empty();
        signals = Map.of();
        resolution = ClientWorldResolution.collecting();
        address = null;
        serverId = null;
        clearProfileLock();
        velocityQuery.disarm();
        velocityLegacyProfileId = null;
        gameJoinObserved = false;
        proxyWorldJoin = false;
        signalTicks = 0;
        terrainAttempted = false;
        ambiguityNotified = false;
        migrationInProgress = false;
        observationGeneration++;
    }

    private void onDisconnect(final ClientPlayNetworkHandler handler) {
        client.execute(() -> {
            final ClientPlayNetworkHandler current = client.getNetworkHandler();
            if (current == null || current == handler) {
                resetObservation();
            }
        });
    }

    private void lockProfile(final ClientWorldProfile profile) {
        lockedProfileId = profile.id();
        lockedSeedHash = seedHash;
        proxyWorldJoin = false;
    }

    private void clearProfileLock() {
        lockedProfileId = null;
        lockedSeedHash = OptionalLong.empty();
    }

    private void observeAddress(final String currentAddress) {
        if (currentAddress.equals(address)) {
            return;
        }
        address = currentAddress;
        serverId = WorldIdentity.multiplayer(currentAddress).serverId();
        clearProfileLock();
        resolution = ClientWorldResolution.collecting();
        terrainAttempted = false;
        ambiguityNotified = false;
        velocityLegacyProfileId = null;
        observationGeneration++;
    }

    private void ensureAddressObserved() {
        if (client != null && client.world != null && !client.isInSingleplayer()) {
            observeAddress(canonicalAddress(resolveAddress(client)));
        }
    }

    /**
     * Keeps this service's server namespace identical to the session's. The session tracker hands
     * an already-resolved address to {@link #resolve(String)}; the paths that read the client
     * directly resolve it here.
     */
    private String canonicalAddress(final String rawAddress) {
        return aliases == null
            ? rawAddress
            : aliases.resolve(rawAddress, companion.companionWorldId()).canonicalId();
    }

    private static String resolveAddress(final MinecraftClient client) {
        final ServerInfo server = client.getCurrentServerEntry();
        if (server != null) {
            return server.address;
        }
        if (client.getNetworkHandler() != null && client.getNetworkHandler().getConnection() != null) {
            return client.getNetworkHandler().getConnection().getAddress().toString();
        }
        return "unknown";
    }

    private boolean shouldAwaitVelocityIdentity() {
        if (companion.state() == CompanionSession.State.ACTIVE) {
            velocityQuery.disarm();
            velocityLegacyProfileId = null;
            return false;
        }
        if (velocityQuery.ready()) {
            velocityLegacyProfileId = velocityQuery.mayAdoptLegacyProfile()
                && resolution.state() == ClientWorldResolution.State.RESOLVED
                ? resolution.profile().id()
                : null;
        }
        return velocityQuery.shouldAwait(
            clientTick,
            supportsVelocityServerQuery(),
            () -> MinecraftAccess.sendCommand(client, "server")
        );
    }

    private boolean supportsVelocityServerQuery() {
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            return false;
        }
        //#if MC>=260100
        //$$ final String brand = client.getConnection().serverBrand();
        //$$ final boolean hasServerCommand = client.getConnection().getCommands().getRoot().getChild("server") != null;
        //#elseif MC>=12100
        //$$ final String brand = client.getNetworkHandler().getBrand();
        //$$ final boolean hasServerCommand = client.getNetworkHandler()
        //$$     .getCommandDispatcher().getRoot().getChild("server") != null;
        //#else
        final String brand = client.player.getServerBrand();
        final boolean hasServerCommand = client.getNetworkHandler()
            .getCommandDispatcher().getRoot().getChild("server") != null;
        //#endif
        return brand != null && brand.toLowerCase(Locale.ROOT).contains("velocity") && hasServerCommand;
    }
}
