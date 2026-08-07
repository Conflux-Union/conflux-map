package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldDetectionState;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldObservation;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldPolicy;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileDeletionService;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileRegistry;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfileResolver;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldResolution;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldPosition;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldSignalHasher;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldTerrainFingerprint;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldVisit;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.snapshot.ChunkCaptureService;
import com.mojang.brigadier.tree.CommandNode;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;

/**
 * Client-only upstream world resolver for proxy addresses. The server seed hash is strong evidence;
 * hashed registry/command/brand metadata provides conservative fallback evidence, and existing
 * terrain cache is consulted last. Ambiguous observations intentionally return no identity so the
 * normal session lifecycle suspends every map writer until the user makes a choice.
 */
public final class ClientMultiworldService {
    private static final int SIGNAL_REFRESH_TICKS = ClientWorldChangeDetector.OBSERVATION_WINDOW_TICKS;
    private static final int MAX_PENDING_SNAPSHOTS = 256;
    private static final int VISIT_REFRESH_MIN_TICKS = 100;
    private static final int MAX_PERSISTENCE_RETRIES = 5;
    private static final int PERSISTENCE_RETRY_BASE_TICKS = 20;
    private static final int PERSISTENCE_RETRY_MAX_TICKS = 400;

    private final MinecraftClient client;
    private final CompanionSession companion;
    private final ClientWorldProfileResolver resolver;
    private final ClientWorldProfileDeletionService deletionService;
    private final Supplier<ClientWorldPolicy> policy;

    private OptionalLong seedHash = OptionalLong.empty();
    private OptionalLong previousSeedHash = OptionalLong.empty();
    private Map<String, String> signals = Map.of();
    private ClientWorldResolution resolution = ClientWorldResolution.collecting();
    private String address;
    private String serverId;
    private String spawnPosition;
    private String lockedProfileId;
    private OptionalLong lockedSeedHash = OptionalLong.empty();
    private String commandLockedProfileId;
    private String commandLockedServerId;
    private String commandResumeProfileId;
    private OptionalLong commandLockBaselineSeedHash = OptionalLong.empty();
    private Map<String, String> commandLockBaselineSignals = Map.of();
    private boolean commandLockAwaitingWorldTransition;
    private boolean commandLockObservedWeakWorldTransition;
    private boolean commandLockConflict;
    private long commandLockDeadlineTick;
    private long clientTick;
    private long detectionStartedAtTick;
    private ClientWorldDetectionState detectionState = ClientWorldDetectionState.SUSPECTED;
    private boolean gameJoinObserved;
    private boolean proxyWorldJoin;
    private int signalTicks;
    private boolean signalsCollected;
    private long observationGeneration;
    private long detectionGeneration;
    private boolean ambiguityNotified;
    private String persistenceError;
    private int persistenceFailureCount;
    private long persistenceRetryAfterTick;
    private boolean persistenceFailureLatched;
    private ClientWorldTerrainFingerprint terrainFingerprint;
    /** Candidate-specific samples captured only when their saved 3x3 is already loaded. */
    private final Map<String, ClientWorldTerrainFingerprint> terrainFingerprintsByProfileId = new LinkedHashMap<>();
    private final ClientWorldTerrainProbePolicy terrainProbePolicy = new ClientWorldTerrainProbePolicy();
    private final ClientWorldChangeDetector worldChangeDetector = new ClientWorldChangeDetector();
    private final Deque<PendingSnapshot> pendingSnapshots = new ArrayDeque<>();
    private final Set<Long> recentFullChunks = new HashSet<>();
    private final Set<Long> recentUnloadedChunks = new HashSet<>();
    private Object observedWorld;
    private String observedDimension;
    private String observedGameMode;
    private ClientWorldPosition observedPosition;
    private long fullChunkWindowStartedAt;
    private ChunkCaptureService chunkCapture;
    private Supplier<String> openMapKeyDisplayName;
    private Consumer<WorldIdentity> profileFlushBarrier = ignored -> { };
    private Supplier<ClientWorldProfileRegistry> profileRegistryLoader;
    private ClientWorldObservation lastRememberedVisit;
    private long lastVisitRefreshTick = Long.MIN_VALUE;

    public ClientMultiworldService(
        final MinecraftClient client,
        final CompanionSession companion,
        final ClientWorldProfileResolver resolver,
        final Path cacheRoot
    ) {
        this(client, companion, resolver, cacheRoot, ClientWorldPolicy::defaults);
    }

    public ClientMultiworldService(
        final MinecraftClient client,
        final CompanionSession companion,
        final ClientWorldProfileResolver resolver,
        final Path cacheRoot,
        final Supplier<ClientWorldPolicy> policy
    ) {
        this.client = client;
        this.companion = companion;
        this.resolver = resolver;
        this.policy = Objects.requireNonNull(policy, "policy");
        final Path confluxRoot = cacheRoot.toAbsolutePath().normalize().getParent();
        final Path storageRoot = confluxRoot == null ? cacheRoot.toAbsolutePath().normalize() : confluxRoot;
        deletionService = new ClientWorldProfileDeletionService(
            cacheRoot,
            storageRoot.resolve("waypoints"),
            storageRoot.resolve("annotations"),
            storageRoot.resolve("recovery").resolve("client-worlds")
        );
    }

    public void register() {
        ClientWorldIdentityHandler.bind(this);
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tickSignals());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, ignored) ->
            ClientWorldIdentityHandler.connectionEstablished()
        );
        ClientPlayConnectionEvents.DISCONNECT.register((handler, ignored) -> resetObservation());
    }

    public void bindChunkCapture(final ChunkCaptureService service) {
        chunkCapture = service;
        service.bindPendingSnapshotBuffer(this);
    }

    /**
     * Deletes must not race an old session's asynchronous region flush. The composition root
     * supplies the barrier so this client-only service remains usable in unit tests.
     */
    public void bindProfileFlushBarrier(final Consumer<WorldIdentity> barrier) {
        profileFlushBarrier = Objects.requireNonNull(barrier, "barrier");
    }

    /** Supplies an explicit user-triggered reload path after fail-closed registry quarantine. */
    public void bindProfileRegistryLoader(final Supplier<ClientWorldProfileRegistry> loader) {
        profileRegistryLoader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * Called by the capture pipeline while the session is suspended. Command switches deliberately
     * remain SUSPECTED until a real world boundary arrives, so pre-command chunks are never queued
     * for the selected target.
     */
    public boolean shouldBufferSnapshots() {
        return detectionState == ClientWorldDetectionState.PROBING
            || detectionState == ClientWorldDetectionState.WAITING_FOR_USER;
    }

    public void bufferSnapshot(final ChunkSnapshot snapshot, final MapLayer layer) {
        if (!shouldBufferSnapshots()) {
            return;
        }
        if (pendingSnapshots.size() == MAX_PENDING_SNAPSHOTS) {
            pendingSnapshots.removeFirst();
        }
        pendingSnapshots.addLast(new PendingSnapshot(snapshot, layer, detectionGeneration));
    }

    public List<PendingSnapshot> drainPendingSnapshots() {
        final List<PendingSnapshot> snapshots = pendingSnapshots.stream()
            .filter(snapshot -> snapshot.detectionGeneration() == detectionGeneration)
            .toList();
        pendingSnapshots.clear();
        return snapshots;
    }

    /** A snapshot is valid only for the recognition generation that captured it. */
    public record PendingSnapshot(ChunkSnapshot snapshot, MapLayer layer, long detectionGeneration) {
    }

    public void bindOpenMapKeyDisplayName(final Supplier<String> supplier) {
        openMapKeyDisplayName = Objects.requireNonNull(supplier, "supplier");
    }

    public void onGameJoin(final long observedSeedHash) {
        final OptionalLong departedSeedHash = seedHash;
        final boolean switchedUpstreamWorld = gameJoinObserved;
        resetObservation(false);
        gameJoinObserved = true;
        previousSeedHash = switchedUpstreamWorld ? departedSeedHash : OptionalLong.empty();
        proxyWorldJoin = switchedUpstreamWorld;
        seedHash = OptionalLong.of(observedSeedHash);
        if (hasPendingCommand()) {
            confirmPendingCommandTransitionAfterGameJoin();
        }
        beginProbing();
        ConfluxMapMod.LOGGER.info(
            "Client world join observed (proxySwitch={} seedHashSignature={})",
            switchedUpstreamWorld,
            ClientWorldSignalHasher.hash(Long.toUnsignedString(observedSeedHash)).substring(0, 12)
        );
    }

    /** Starts a fresh client-owned recognition generation when the network play connection returns. */
    public void onConnectionEstablished() {
        observedWorld = null;
        observedDimension = null;
        observedGameMode = null;
        observedPosition = null;
        beginProbing();
    }

    public void onRespawn(final long observedSeedHash) {
        // Respawn is a logical-world lifecycle event (death or dimension transfer), not proof
        // that a proxy selected another upstream. Reusing the same seed must therefore keep the
        // existing profile and learn a per-dimension visit.
        if (shouldKeepStableProfileOnRespawn(observedSeedHash)) {
            previousSeedHash = OptionalLong.empty();
            proxyWorldJoin = false;
            seedHash = OptionalLong.of(observedSeedHash);
            lockedSeedHash = seedHash;
            signals = Map.of();
            signalsCollected = false;
            signalTicks = 0;
            terrainFingerprint = null;
            terrainFingerprintsByProfileId.clear();
            terrainProbePolicy.reset();
            worldChangeDetector.reset();
            pendingSnapshots.clear();
            recentFullChunks.clear();
            recentUnloadedChunks.clear();
            lastRememberedVisit = null;
            lastVisitRefreshTick = Long.MIN_VALUE;
            ambiguityNotified = false;
            persistenceError = null;
            observationGeneration++;
            return;
        }
        previousSeedHash = OptionalLong.empty();
        proxyWorldJoin = false;
        seedHash = OptionalLong.of(observedSeedHash);
        beginProbing();
    }

    /** Called after one full logical chunk packet has been applied to the client world. */
    public void onFullChunkLoaded(final int chunkX, final int chunkZ) {
        if (client == null || !canObserveWorldReplacementSignals()) {
            return;
        }
        prepareChunkWindow();
        recentFullChunks.add(chunkKey(chunkX, chunkZ));
        detectLargeChunkReplacement();
    }

    /** A large unload wave is also an upstream-world replacement signal, not a resource reload. */
    public void onFullChunkUnloaded(final int chunkX, final int chunkZ) {
        if (client == null || !canObserveWorldReplacementSignals()) {
            return;
        }
        prepareChunkWindow();
        recentUnloadedChunks.add(chunkKey(chunkX, chunkZ));
        detectLargeChunkReplacement();
    }

    /** Resolves only the client-owned fallback. Companion world UUIDs remain authoritative. */
    public Optional<WorldIdentity> resolve(final String currentAddress) {
        final ClientWorldResolution currentResolution = resolveProfile(currentAddress);
        if (detectionState != ClientWorldDetectionState.STABLE
            || currentResolution.state() != ClientWorldResolution.State.RESOLVED) {
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
        if (!currentAddress.equals(address)) {
            address = currentAddress;
            serverId = WorldIdentity.multiplayer(currentAddress).serverId();
            deletionService.recoverPendingTransactions(
                serverId,
                resolver.profiles(serverId).stream().map(ClientWorldProfile::id).collect(java.util.stream.Collectors.toSet())
            );
            clearProfileLock();
            resolution = ClientWorldResolution.collecting();
            detectionState = ClientWorldDetectionState.PROBING;
            terrainFingerprint = null;
            terrainFingerprintsByProfileId.clear();
            terrainProbePolicy.reset();
            worldChangeDetector.reset();
            pendingSnapshots.clear();
            detectionGeneration++;
            ambiguityNotified = false;
            if (!Objects.equals(commandLockedServerId, serverId)) {
                clearCommandLock();
            }
            observationGeneration++;
            resetPersistenceBackoff();
        }
        if (applyPendingCommand()) {
            return resolution;
        }
        refreshTerrainFingerprint();
        if (persistenceRetryBlocked()) {
            return resolution;
        }
        if (isUnrepresentedSameSeedTransition()) {
            resolution = signalsCollected
                ? ClientWorldResolution.ambiguous()
                : ClientWorldResolution.collecting();
            detectionState = signalsCollected
                ? ClientWorldDetectionState.WAITING_FOR_USER
                : ClientWorldDetectionState.PROBING;
            return resolution;
        }
        if (detectionState == ClientWorldDetectionState.STABLE
            && resolution.state() == ClientWorldResolution.State.RESOLVED) {
            return resolution;
        }
        final boolean uniqueKnownSeed = seedHash.isPresent()
            && resolver.profileCountWithSeed(serverId, seedHash.getAsLong()) == 1;
        if (!signalsCollected && !canCreateIsolatedProfile() && !uniqueKnownSeed) {
            resolution = ClientWorldResolution.collecting();
            detectionState = ClientWorldDetectionState.PROBING;
            return resolution;
        }
        resolution = resolver.resolve(serverId, observation());
        if (resolution.state() == ClientWorldResolution.State.RESOLVED) {
            lockProfile(resolution.profile());
            detectionState = ClientWorldDetectionState.STABLE;
            resetPersistenceBackoff();
        } else if (resolution.state() == ClientWorldResolution.State.PERSISTENCE_FAILED) {
            persistenceError = resolution.error();
            recordPersistenceFailure();
            detectionState = ClientWorldDetectionState.WAITING_FOR_USER;
        } else if (signalsCollected && shouldRetryTerrainProbe()) {
            detectionState = ClientWorldDetectionState.PROBING;
        } else if (signalsCollected) {
            detectionState = ClientWorldDetectionState.WAITING_FOR_USER;
        } else {
            detectionState = ClientWorldDetectionState.PROBING;
        }
        return resolution;
    }

    public boolean canManageProfiles() {
        return client.world != null && !client.isInSingleplayer() && companion.state() != CompanionSession.State.ACTIVE;
    }

    public boolean needsSelection() {
        return canManageProfiles() && manualSelectionAvailable(
            detectionState, resolution.state(), signalsCollected
        );
    }

    static boolean manualSelectionAvailable(
        final ClientWorldDetectionState state,
        final ClientWorldResolution.State resolutionState,
        final boolean ignoredSignalsCollected
    ) {
        return state == ClientWorldDetectionState.WAITING_FOR_USER
            || resolutionState == ClientWorldResolution.State.AMBIGUOUS;
    }

    public ClientWorldDetectionState detectionState() {
        return detectionState;
    }

    public List<ClientWorldProfile> profiles() {
        return serverId == null ? List.of() : resolver.profiles(serverId);
    }

    public boolean profileRegistryAvailable() {
        return resolver.available();
    }

    /**
     * Retries loading only when startup entered fail-closed mode. The user must first restore a
     * valid client_worlds.json; quarantined evidence is never deleted or rewritten here.
     */
    public ClientWorldProfileResolver.MutationResult retryProfileRegistryLoad() {
        if (resolver.available()) {
            return new ClientWorldProfileResolver.MutationResult(true, null);
        }
        if (profileRegistryLoader == null) {
            persistenceError = "client world registry reload is unavailable";
            return new ClientWorldProfileResolver.MutationResult(false, persistenceError);
        }
        final ClientWorldProfileRegistry restored;
        try {
            restored = profileRegistryLoader.get();
        } catch (final RuntimeException error) {
            ConfluxMapMod.LOGGER.warn("Client world registry reload failed", error);
            persistenceError = "client world registry reload failed: " + error.getClass().getSimpleName();
            return new ClientWorldProfileResolver.MutationResult(false, persistenceError);
        }
        final ClientWorldProfileResolver.MutationResult result = resolver.restore(restored);
        if (!result.applied()) {
            persistenceError = result.error();
            return result;
        }
        persistenceError = null;
        clearCommandLock();
        clearProfileLock();
        resetPersistenceBackoff();
        if (address == null) {
            resolution = ClientWorldResolution.collecting();
            detectionState = ClientWorldDetectionState.SUSPECTED;
            pendingSnapshots.clear();
            detectionGeneration++;
        } else {
            beginProbing();
        }
        observationGeneration++;
        return result;
    }

    public Optional<ClientWorldProfile> currentProfile() {
        return resolution.state() == ClientWorldResolution.State.RESOLVED
            ? Optional.of(resolution.profile())
            : Optional.empty();
    }

    /** Stable cache identity for one profile without changing the active map session. */
    public Optional<WorldIdentity> identityForProfile(final String profileId) {
        if (serverId == null || profileId == null) {
            return Optional.empty();
        }
        return resolver.profiles(serverId).stream()
            .filter(profile -> profile.id().equals(profileId))
            .findFirst()
            .map(profile -> new WorldIdentity(serverId, profile.storageId()));
    }

    public List<ClientWorldResolution.Candidate> candidates() {
        return resolution.candidates();
    }

    public ClientWorldProfileResolver.MutationResult select(final String profileId) {
        requireConnection();
        clearCommandLock();
        resolution = resolver.select(serverId, profileId, observation());
        if (resolution.state() == ClientWorldResolution.State.RESOLVED) {
            lockProfile(resolution.profile());
            detectionState = ClientWorldDetectionState.STABLE;
            resetPersistenceBackoff();
            observationGeneration++;
            return new ClientWorldProfileResolver.MutationResult(true, null);
        }
        persistenceError = resolution.error();
        detectionState = ClientWorldDetectionState.WAITING_FOR_USER;
        return new ClientWorldProfileResolver.MutationResult(false, persistenceError);
    }

    public void onSpawnPosition(final int x, final int y, final int z, final float angle) {
        spawnPosition = x + ":" + y + ":" + z + ":" + Float.floatToIntBits(angle);
        if (detectionState != ClientWorldDetectionState.STABLE
            || resolution.state() != ClientWorldResolution.State.RESOLVED) {
            signals = Map.of();
            signalsCollected = false;
            resolution = ClientWorldResolution.collecting();
        }
        signalTicks = 0;
        ambiguityNotified = false;
        observationGeneration++;
        resetPersistenceBackoff();
    }

    public ClientWorldProfileResolver.MutationResult createAndSelect(final String displayName) {
        requireConnection();
        clearCommandLock();
        resolution = resolver.createAndSelect(serverId, displayName, observation());
        if (resolution.state() == ClientWorldResolution.State.RESOLVED) {
            lockProfile(resolution.profile());
            detectionState = ClientWorldDetectionState.STABLE;
            resetPersistenceBackoff();
            observationGeneration++;
            return new ClientWorldProfileResolver.MutationResult(true, null);
        }
        persistenceError = resolution.error();
        detectionState = ClientWorldDetectionState.WAITING_FOR_USER;
        return new ClientWorldProfileResolver.MutationResult(false, persistenceError);
    }

    public ClientWorldProfileResolver.MutationResult rename(final String profileId, final String displayName) {
        requireConnection();
        return rememberMutation(resolver.rename(serverId, profileId, displayName));
    }

    public ClientWorldProfileResolver.MutationResult clearBindings(final String profileId) {
        requireConnection();
        final ClientWorldProfileResolver.MutationResult result = rememberMutation(
            resolver.clearBindings(serverId, profileId)
        );
        if (!result.applied()) {
            return result;
        }
        if (currentProfile().map(ClientWorldProfile::id).filter(profileId::equals).isPresent()) {
            clearCommandLock();
            clearProfileLock();
            resolution = ClientWorldResolution.ambiguous();
            detectionState = ClientWorldDetectionState.WAITING_FOR_USER;
            ambiguityNotified = false;
            observationGeneration++;
            notifyAmbiguity();
        }
        return result;
    }

    /**
     * Observes the exact text submitted through the chat screen. This method only updates local
     * profile state; the caller leaves Minecraft's send path untouched, so the original command
     * is still delivered once to the server.
     */
    public boolean onChatSubmitted(final String rawText) {
        if (serverId == null || companion.state() == CompanionSession.State.ACTIVE) {
            return false;
        }
        final Optional<ClientWorldProfile> target = resolver.profileForCommand(serverId, rawText);
        if (target.isEmpty()) {
            return false;
        }
        final ClientWorldProfile profile = target.get();
        commandLockedProfileId = profile.id();
        commandLockedServerId = serverId;
        commandResumeProfileId = currentProfile().map(ClientWorldProfile::id).orElse(lockedProfileId);
        commandLockBaselineSeedHash = seedHash;
        commandLockBaselineSignals = identitySignals(signals);
        commandLockAwaitingWorldTransition = true;
        commandLockObservedWeakWorldTransition = false;
        commandLockConflict = false;
        commandLockDeadlineTick = clientTick + policy().commandConfirmationTicks();
        clearProfileLock();
        resolution = ClientWorldResolution.collecting();
        detectionState = ClientWorldDetectionState.SUSPECTED;
        pendingSnapshots.clear();
        recentFullChunks.clear();
        recentUnloadedChunks.clear();
        fullChunkWindowStartedAt = clientTick;
        detectionStartedAtTick = clientTick;
        detectionGeneration++;
        ambiguityNotified = false;
        observationGeneration++;
        return true;
    }

    public ClientWorldProfileResolver.CommandBindingResult addSwitchCommand(
        final String profileId,
        final String command,
        final boolean rebind
    ) {
        requireConnection();
        final ClientWorldProfileResolver.CommandBindingResult result = resolver.addSwitchCommand(
            serverId, profileId, command, rebind
        );
        rememberMutation(result.mutation());
        return result;
    }

    public ClientWorldProfileResolver.MutationResult removeSwitchCommand(final String profileId, final String command) {
        requireConnection();
        return rememberMutation(resolver.removeSwitchCommand(serverId, profileId, command));
    }

    /**
     * Deletes a non-active profile only after its local data has moved into the recovery directory.
     * A failed registry save restores every moved file before reporting failure.
     */
    public ClientWorldProfileDeletionService.DeletionResult delete(final String profileId) {
        requireConnection();
        if (currentProfile().map(ClientWorldProfile::id).filter(profileId::equals).isPresent()) {
            return ClientWorldProfileDeletionService.DeletionResult.failure("current_profile");
        }
        final ClientWorldProfile profile = profile(profileId)
            .orElseThrow(() -> new IllegalArgumentException("unknown client world profile " + profileId));
        profileFlushBarrier.accept(new WorldIdentity(serverId, profile.storageId()));
        final ClientWorldProfileDeletionService.Transaction transaction = deletionService.moveToRecovery(serverId, profile);
        if (!transaction.prepared()) {
            persistenceError = transaction.error();
            return ClientWorldProfileDeletionService.DeletionResult.failure(transaction.error());
        }
        final ClientWorldProfileResolver.MutationResult mutation = resolver.delete(serverId, profileId);
        if (mutation.applied()) {
            final String journalError = transaction.commit();
            if (journalError != null) {
                ConfluxMapMod.LOGGER.warn("Client world deletion committed but journal cleanup failed: {}", journalError);
            }
            persistenceError = null;
            return ClientWorldProfileDeletionService.DeletionResult.success();
        }
        final String restoreError = transaction.restore();
        persistenceError = mutation.error();
        return ClientWorldProfileDeletionService.DeletionResult.failure(
            restoreError == null ? mutation.error() : mutation.error() + "; " + restoreError
        );
    }

    public Optional<ClientWorldProfile> profile(final String profileId) {
        return profiles().stream().filter(candidate -> candidate.id().equals(profileId)).findFirst();
    }

    /** The latest persistence error for profile-management UI. Null means the last mutation saved. */
    public String persistenceError() {
        return persistenceError;
    }

    private void tickSignals() {
        advanceDetectionClock();
        if (client.world == null || client.player == null || client.getNetworkHandler() == null || client.isInSingleplayer()) {
            observeWorldCleared();
            return;
        }
        observeWorldChanges();
        rememberStableVisit();
        if (++signalTicks < SIGNAL_REFRESH_TICKS) {
            return;
        }
        signalTicks = 0;
        final Map<String, String> observed = collectSignals();
        signalsCollected = true;
        observeSignals(observed);
    }

    /** Applies one completed client-signal sample without changing an already locked visit. */
    void observeSignals(final Map<String, String> observed) {
        signalsCollected = true;
        if (observed.equals(signals)) {
            return;
        }
        signals = observed;
        confirmPendingCommandTransitionAfterWeakEvidence();
        observationGeneration++;
        if (serverId != null) {
            resolveProfile(address);
            notifyAmbiguity();
            tryTerrainMatch();
        }
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
        observed.put("world_shape", ClientWorldSignalHasher.hash(
            client.world.getBottomY() + ":" + client.world.getTopY()
        ));
        observed.put("difficulty", ClientWorldSignalHasher.hash(String.valueOf(client.world.getDifficulty())));
        if (spawnPosition != null) {
            observed.put("spawn", ClientWorldSignalHasher.hash(spawnPosition));
        }
        observed.put("world_border", ClientWorldSignalHasher.hash(
            Double.toHexString(client.world.getWorldBorder().getCenterX()) + ":"
                + Double.toHexString(client.world.getWorldBorder().getCenterZ()) + ":"
                + Double.toHexString(client.world.getWorldBorder().getSizeLerpTarget()) + ":"
                + client.world.getWorldBorder().getWarningBlocks()
        ));
        return Map.copyOf(observed);
    }

    private void tryTerrainMatch() {
        // Terrain is now a bounded in-memory 3x3 fingerprint. Never scan every profile's region
        // files on the shared cache IO executor while an ambiguous session is waiting.
        refreshTerrainFingerprint();
        if (serverId != null && address != null && signalsCollected
            && detectionState == ClientWorldDetectionState.PROBING) {
            resolveProfile(address);
        }
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

    private ClientWorldObservation observation() {
        String dimension = null;
        String gameMode = null;
        ClientWorldPosition position = null;
        if (client != null && client.world != null && client.player != null) {
            final Identifier id = client.world.getRegistryKey().getValue();
            dimension = DimensionId.of(id.getNamespace(), id.getPath()).fileName();
            position = new ClientWorldPosition(
                client.player.getBlockPos().getX(),
                client.player.getBlockPos().getY(),
                client.player.getBlockPos().getZ()
            );
            if (client.interactionManager != null) {
                gameMode = String.valueOf(client.interactionManager.getCurrentGameMode());
            }
        }
        return new ClientWorldObservation(
            seedHash, signals, dimension, gameMode, position, terrainFingerprint,
            terrainFingerprintsByProfileId
        );
    }

    private void notifyAmbiguity() {
        if (detectionState != ClientWorldDetectionState.WAITING_FOR_USER || ambiguityNotified
            || client == null || client.player == null) {
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

    private boolean applyPendingCommand() {
        if (commandLockConflict) {
            resolution = ClientWorldResolution.ambiguous();
            detectionState = ClientWorldDetectionState.WAITING_FOR_USER;
            return true;
        }
        if (commandLockedProfileId == null || !Objects.equals(commandLockedServerId, serverId)) {
            return false;
        }
        final Optional<ClientWorldProfile> profile = resolver.profiles(serverId).stream()
            .filter(candidate -> candidate.id().equals(commandLockedProfileId))
            .findFirst();
        if (profile.isEmpty()) {
            clearCommandLock();
            return false;
        }
        if (commandLockAwaitingWorldTransition) {
            resolution = ClientWorldResolution.collecting();
            detectionState = ClientWorldDetectionState.SUSPECTED;
            return true;
        }
        final ClientWorldObservation currentObservation = observation();
        final boolean knownTargetSeedConflict = seedHash.isPresent()
            && resolver.hasKnownSeedConflict(serverId, commandLockedProfileId, seedHash.getAsLong());
        final boolean knownTargetSignalConflict = resolver.hasKnownSignalConflict(
            serverId, commandLockedProfileId, currentObservation
        );
        if (knownTargetSeedConflict || knownTargetSignalConflict) {
            commandLockConflict = true;
            resolution = ClientWorldResolution.ambiguous();
            detectionState = ClientWorldDetectionState.WAITING_FOR_USER;
            return true;
        }
        if (!signalsCollected) {
            resolution = ClientWorldResolution.collecting();
            detectionState = ClientWorldDetectionState.PROBING;
            return true;
        }
        resolution = resolver.select(serverId, commandLockedProfileId, currentObservation);
        if (resolution.state() != ClientWorldResolution.State.RESOLVED) {
            persistenceError = resolution.error();
            recordPersistenceFailure();
            clearCommandLock();
            detectionState = ClientWorldDetectionState.WAITING_FOR_USER;
            return true;
        }
        lockProfile(resolution.profile());
        resetPersistenceBackoff();
        detectionState = ClientWorldDetectionState.STABLE;
        clearCommandLock();
        return true;
    }

    private void resetObservation() {
        resetObservation(true);
    }

    private void resetObservation(final boolean clearCommandLock) {
        seedHash = OptionalLong.empty();
        previousSeedHash = OptionalLong.empty();
        signals = Map.of();
        signalsCollected = false;
        resolution = ClientWorldResolution.collecting();
        address = null;
        serverId = null;
        spawnPosition = null;
        terrainFingerprint = null;
        terrainFingerprintsByProfileId.clear();
        terrainProbePolicy.reset();
        worldChangeDetector.reset();
        pendingSnapshots.clear();
        recentFullChunks.clear();
        recentUnloadedChunks.clear();
        observedWorld = null;
        observedDimension = null;
        observedGameMode = null;
        observedPosition = null;
        clearProfileLock();
        if (clearCommandLock) {
            clearCommandLock();
        }
        gameJoinObserved = false;
        proxyWorldJoin = false;
        signalTicks = 0;
        ambiguityNotified = false;
        persistenceError = null;
        resetPersistenceBackoff();
        detectionState = ClientWorldDetectionState.SUSPECTED;
        detectionGeneration++;
        observationGeneration++;
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

    private void clearCommandLock() {
        commandLockedProfileId = null;
        commandLockedServerId = null;
        commandResumeProfileId = null;
        commandLockBaselineSeedHash = OptionalLong.empty();
        commandLockBaselineSignals = Map.of();
        commandLockAwaitingWorldTransition = false;
        commandLockObservedWeakWorldTransition = false;
        commandLockConflict = false;
        commandLockDeadlineTick = 0L;
    }

    private boolean shouldKeepStableProfileOnRespawn(final long observedSeedHash) {
        return !hasPendingCommand()
            && detectionState == ClientWorldDetectionState.STABLE
            && resolution.state() == ClientWorldResolution.State.RESOLVED
            && seedHash.isPresent()
            && seedHash.getAsLong() == observedSeedHash
            && Objects.equals(lockedProfileId, resolution.profile().id());
    }

    private void beginProbing() {
        clearProfileLock();
        resolution = ClientWorldResolution.collecting();
        signals = Map.of();
        signalsCollected = false;
        terrainFingerprint = null;
        terrainFingerprintsByProfileId.clear();
        terrainProbePolicy.reset();
        worldChangeDetector.reset();
        pendingSnapshots.clear();
        recentFullChunks.clear();
        recentUnloadedChunks.clear();
        fullChunkWindowStartedAt = clientTick;
        detectionStartedAtTick = clientTick;
        detectionGeneration++;
        signalTicks = 0;
        ambiguityNotified = false;
        persistenceError = null;
        resetPersistenceBackoff();
        detectionState = ClientWorldDetectionState.PROBING;
        observationGeneration++;
    }

    private void observeWorldChanges() {
        final Object currentWorld = client.world;
        final Identifier dimension = client.world.getRegistryKey().getValue();
        final String currentDimension = dimension.toString();
        final boolean worldObjectChanged = observedWorld != null && observedWorld != currentWorld;
        final boolean dimensionChanged = observedDimension != null && !observedDimension.equals(currentDimension);
        if (shouldProbeAfterWorldReferenceChange(worldObjectChanged, dimensionChanged)) {
            observePotentialWorldReplacement(false);
        }
        observedWorld = currentWorld;
        observedDimension = currentDimension;
        final String gameMode = client.interactionManager == null
            ? null
            : String.valueOf(client.interactionManager.getCurrentGameMode());
        final ClientWorldPosition position = new ClientWorldPosition(
            client.player.getBlockPos().getX(), client.player.getBlockPos().getY(), client.player.getBlockPos().getZ()
        );
        final boolean gameModeChanged = observedGameMode != null && !observedGameMode.equals(gameMode);
        final boolean coordinateJump = observedPosition != null
            && observedPosition.horizontalDistanceTo(position) > 96.0D;
        observedGameMode = gameMode;
        observedPosition = position;
        if (canObserveWorldReplacementSignals()
            && gameModeChanged
            && worldChangeDetector.observeWeakSignal(
                clientTick, ClientWorldChangeDetector.WeakSignal.GAME_MODE
            )) {
            observePotentialWorldReplacement(true);
            return;
        }
        if (canObserveWorldReplacementSignals()
            && coordinateJump
            && worldChangeDetector.observeWeakSignal(
                clientTick, ClientWorldChangeDetector.WeakSignal.POSITION
            )) {
            observePotentialWorldReplacement(true);
        }
    }

    static boolean shouldProbeAfterWorldReferenceChange(
        final boolean worldObjectChanged,
        final boolean dimensionChanged
    ) {
        // Vanilla dimension transfers can replace ClientWorld, but they are a visit boundary inside
        // the same logical profile. WorldSessionTracker already rotates the active dimension.
        return worldObjectChanged && !dimensionChanged;
    }

    private void prepareChunkWindow() {
        if (clientTick - fullChunkWindowStartedAt > SIGNAL_REFRESH_TICKS) {
            recentFullChunks.clear();
            recentUnloadedChunks.clear();
            fullChunkWindowStartedAt = clientTick;
        }
        worldChangeDetector.expire(clientTick);
    }

    private void detectLargeChunkReplacement() {
        final int viewDistance = MinecraftAccess.viewDistance(client);
        final int expected = (viewDistance * 2 + 1) * (viewDistance * 2 + 1);
        final int replacedChunks = overlappingChunkCount(recentFullChunks, recentUnloadedChunks);
        final ClientWorldChangeDetector.ReplacementStrength strength = worldChangeDetector.replacementStrength(
            clientTick, expected, replacedChunks
        );
        if (strength == ClientWorldChangeDetector.ReplacementStrength.DEFINITE) {
            observePotentialWorldReplacement(true);
        }
    }

    static int overlappingChunkCount(final Set<Long> loaded, final Set<Long> unloaded) {
        final Set<Long> replaced = new HashSet<>(loaded);
        replaced.retainAll(unloaded);
        return replaced.size();
    }

    private static long chunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private void refreshTerrainFingerprint() {
        if (chunkCapture == null || client == null
            || client.world == null || client.player == null) {
            return;
        }
        final int centerChunkX = client.player.getBlockPos().getX() >> 4;
        final int centerChunkZ = client.player.getBlockPos().getZ() >> 4;
        if (terrainFingerprint != null && (!terrainFingerprint.hasCenter()
            || terrainFingerprint.centerChunkX() != centerChunkX
            || terrainFingerprint.centerChunkZ() != centerChunkZ)) {
            terrainFingerprint = null;
            terrainFingerprintsByProfileId.clear();
            terrainProbePolicy.reset();
        }
        final List<ClientWorldProfile> profiles = serverId == null ? List.of() : resolver.profiles(serverId);
        final String dimensionId = currentDimensionId();
        if (!hasPendingTerrainEvidence(profiles, dimensionId)) {
            return;
        }
        if (!terrainProbePolicy.shouldProbe(clientTick)) {
            return;
        }
        terrainProbePolicy.recordAttempt(clientTick);
        final MapLayer layer = terrainProbeLayer();
        if (terrainFingerprint == null) {
            final List<ChunkSnapshot> probes = chunkCapture.probeSquareAt(layer, centerChunkX, centerChunkZ);
            if (probes.size() == 9) {
                final ClientWorldTerrainFingerprint captured = ClientWorldTerrainFingerprint.from(
                    probes, centerChunkX, centerChunkZ
                );
                if (captured.complete()) {
                    terrainFingerprint = captured;
                }
            }
        }
        for (final ClientWorldProfile profile : profiles) {
            final ClientWorldVisit visit = profile.visit(dimensionId);
            final ClientWorldTerrainFingerprint saved = visit == null ? null : visit.terrainFingerprint();
            if (saved == null || !saved.hasCenter() || terrainFingerprintsByProfileId.containsKey(profile.id())) {
                continue;
            }
            if (terrainFingerprint != null && terrainFingerprint.sameCenter(saved)) {
                terrainFingerprintsByProfileId.put(profile.id(), terrainFingerprint);
                continue;
            }
            final List<ChunkSnapshot> probes = chunkCapture.probeSquareAt(
                layer, saved.centerChunkX(), saved.centerChunkZ()
            );
            if (probes.size() == 9) {
                final ClientWorldTerrainFingerprint captured = ClientWorldTerrainFingerprint.from(
                    probes, saved.centerChunkX(), saved.centerChunkZ()
                );
                if (captured.complete()) {
                    terrainFingerprintsByProfileId.put(profile.id(), captured);
                }
            }
        }
        rememberStableVisit();
    }

    private boolean shouldRetryTerrainProbe() {
        if (chunkCapture == null || terrainProbePolicy.exhausted()) {
            return false;
        }
        return hasPendingTerrainEvidence(
            serverId == null ? List.of() : resolver.profiles(serverId), currentDimensionId()
        );
    }

    private boolean hasPendingTerrainEvidence(
        final List<ClientWorldProfile> profiles,
        final String dimensionId
    ) {
        if (terrainFingerprint == null) {
            return true;
        }
        for (final ClientWorldProfile profile : profiles) {
            final ClientWorldVisit visit = profile.visit(dimensionId);
            final ClientWorldTerrainFingerprint saved = visit == null ? null : visit.terrainFingerprint();
            if (saved != null && saved.hasCenter() && !terrainFingerprintsByProfileId.containsKey(profile.id())) {
                return true;
            }
        }
        return false;
    }

    private String currentDimensionId() {
        if (client == null || client.world == null) {
            return observedDimension;
        }
        final Identifier id = client.world.getRegistryKey().getValue();
        return DimensionId.of(id.getNamespace(), id.getPath()).fileName();
    }

    private void rememberStableVisit() {
        if (detectionState != ClientWorldDetectionState.STABLE
            || serverId == null
            || resolution.state() != ClientWorldResolution.State.RESOLVED
            || persistenceFailureLatched) {
            return;
        }
        final long elapsedTicks = lastVisitRefreshTick == Long.MIN_VALUE
            ? Long.MAX_VALUE
            : clientTick - lastVisitRefreshTick;
        if (elapsedTicks < VISIT_REFRESH_MIN_TICKS) {
            return;
        }
        final ClientWorldObservation current = observation();
        if (current.dimensionId() == null
            || !shouldRefreshVisit(lastRememberedVisit, current, elapsedTicks, policy())) {
            return;
        }
        final ClientWorldProfileResolver.MutationResult result = resolver.rememberVisit(
            serverId, resolution.profile().id(), current
        );
        if (!result.applied()) {
            persistenceError = result.error();
            recordPersistenceFailure();
            return;
        }
        lastRememberedVisit = current;
        lastVisitRefreshTick = clientTick;
    }

    static boolean shouldRefreshVisit(
        final ClientWorldObservation previous,
        final ClientWorldObservation current,
        final long elapsedTicks
    ) {
        return shouldRefreshVisit(previous, current, elapsedTicks, ClientWorldPolicy.defaults());
    }

    static boolean shouldRefreshVisit(
        final ClientWorldObservation previous,
        final ClientWorldObservation current,
        final long elapsedTicks,
        final ClientWorldPolicy policy
    ) {
        if (previous == null) {
            return true;
        }
        if (!Objects.equals(previous.dimensionId(), current.dimensionId())
            || !Objects.equals(previous.gameMode(), current.gameMode())
            || !previous.signals().equals(current.signals())
            || previous.terrainFingerprint() != current.terrainFingerprint()) {
            return true;
        }
        if (previous.position() == null || current.position() == null) {
            return previous.position() != current.position() || elapsedTicks >= policy.visitRefreshTicks();
        }
        return previous.position().horizontalDistanceTo(current.position()) >= policy.visitRefreshDistance()
            || elapsedTicks >= policy.visitRefreshTicks();
    }

    private void observeWorldCleared() {
        if (observedWorld == null) {
            return;
        }
        observedWorld = null;
        observedDimension = null;
        observedGameMode = null;
        observedPosition = null;
        beginProbing();
    }

    private boolean canCreateIsolatedProfile() {
        if (serverId == null || seedHash.isEmpty()) {
            return false;
        }
        return resolver.profiles(serverId).isEmpty()
            || !resolver.hasProfileWithSeed(serverId, seedHash.getAsLong());
    }

    private boolean isUnrepresentedSameSeedTransition() {
        return proxyWorldJoin && previousSeedHash.isPresent() && seedHash.isPresent()
            && previousSeedHash.getAsLong() == seedHash.getAsLong()
            && resolver.profileCountWithSeed(serverId, seedHash.getAsLong()) <= 1;
    }

    void observeInferredWorldTransition() {
        observePotentialWorldReplacement(true);
    }

    private void observePotentialWorldReplacement(final boolean inferredUpstreamTransition) {
        if (inferredUpstreamTransition) {
            previousSeedHash = seedHash;
            proxyWorldJoin = seedHash.isPresent();
        }
        beginProbing();
        if (hasPendingCommand()) {
            commandLockObservedWeakWorldTransition = true;
        }
    }

    private boolean canObserveWorldReplacementSignals() {
        if (detectionState == ClientWorldDetectionState.STABLE
            || hasPendingCommand() && commandLockAwaitingWorldTransition) {
            return true;
        }
        return (detectionState == ClientWorldDetectionState.PROBING
            || detectionState == ClientWorldDetectionState.WAITING_FOR_USER)
            && clientTick - detectionStartedAtTick >= SIGNAL_REFRESH_TICKS;
    }

    private void confirmPendingCommandTransitionAfterGameJoin() {
        if (hasPendingCommand()) {
            commandLockAwaitingWorldTransition = false;
            commandLockObservedWeakWorldTransition = false;
        }
    }

    private void confirmPendingCommandTransitionAfterWeakEvidence() {
        if (!hasPendingCommand()
            || !commandLockAwaitingWorldTransition
            || !commandLockObservedWeakWorldTransition
            || !commandIdentityChanged(
                commandLockBaselineSeedHash, commandLockBaselineSignals, seedHash, signals
            )) {
            return;
        }
        commandLockAwaitingWorldTransition = false;
    }

    static boolean commandIdentityChanged(
        final OptionalLong baselineSeedHash,
        final Map<String, String> baselineSignals,
        final OptionalLong observedSeedHash,
        final Map<String, String> observedSignals
    ) {
        if (baselineSeedHash.isPresent()
            && observedSeedHash.isPresent()
            && baselineSeedHash.getAsLong() != observedSeedHash.getAsLong()) {
            return true;
        }
        final Map<String, String> baselineIdentitySignals = identitySignals(baselineSignals);
        final Map<String, String> observedIdentitySignals = identitySignals(observedSignals);
        return !baselineIdentitySignals.isEmpty()
            && !observedIdentitySignals.isEmpty()
            && !baselineIdentitySignals.equals(observedIdentitySignals);
    }

    private static Map<String, String> identitySignals(final Map<String, String> observedSignals) {
        final Map<String, String> identitySignals = new LinkedHashMap<>();
        for (final Map.Entry<String, String> signal : observedSignals.entrySet()) {
            if (!isVisitSignal(signal.getKey())) {
                identitySignals.put(signal.getKey(), signal.getValue());
            }
        }
        return Map.copyOf(identitySignals);
    }

    private static boolean isVisitSignal(final String signal) {
        return "dimension".equals(signal)
            || "dimension_type".equals(signal)
            || "world_shape".equals(signal)
            || "difficulty".equals(signal)
            || "spawn".equals(signal)
            || "world_border".equals(signal);
    }

    private boolean persistenceRetryBlocked() {
        return persistenceFailureLatched
            || persistenceFailureCount > 0 && clientTick < persistenceRetryAfterTick
            || resolution.state() == ClientWorldResolution.State.PERSISTENCE_FAILED
                && clientTick < persistenceRetryAfterTick;
    }

    private void recordPersistenceFailure() {
        persistenceFailureCount++;
        if (persistenceFailureCount >= MAX_PERSISTENCE_RETRIES) {
            persistenceFailureLatched = true;
            persistenceRetryAfterTick = Long.MAX_VALUE;
            return;
        }
        final int delay = Math.min(
            PERSISTENCE_RETRY_MAX_TICKS,
            PERSISTENCE_RETRY_BASE_TICKS << Math.min(4, persistenceFailureCount - 1)
        );
        persistenceRetryAfterTick = clientTick + delay;
    }

    private void resetPersistenceBackoff() {
        persistenceFailureCount = 0;
        persistenceRetryAfterTick = 0L;
        persistenceFailureLatched = false;
        lastRememberedVisit = null;
        lastVisitRefreshTick = Long.MIN_VALUE;
    }

    private boolean hasPendingCommand() {
        return commandLockedProfileId != null;
    }

    void advanceDetectionClock() {
        clientTick++;
        if (!hasPendingCommand() || !commandLockAwaitingWorldTransition || clientTick < commandLockDeadlineTick) {
            return;
        }
        final String resumeProfileId = commandResumeProfileId;
        clearCommandLock();
        if (serverId != null && resumeProfileId != null) {
            final Optional<ClientWorldProfile> resume = resolver.profiles(serverId).stream()
                .filter(profile -> profile.id().equals(resumeProfileId))
                .findFirst();
            if (resume.isPresent()) {
                resolution = resolver.activateCommand(serverId, resumeProfileId);
                lockProfile(resolution.profile());
                detectionState = ClientWorldDetectionState.STABLE;
                notifyUnconfirmedCommand(resume.get().displayName());
                ConfluxMapMod.LOGGER.info(
                    "Client world switch command was not confirmed within {} ticks; restored profile {}",
                    policy().commandConfirmationTicks(), resumeProfileId
                );
                return;
            }
        }
        resolution = ClientWorldResolution.ambiguous();
        detectionState = ClientWorldDetectionState.WAITING_FOR_USER;
    }

    private void notifyUnconfirmedCommand(final String restoredProfileName) {
        if (client == null || client.player == null) {
            return;
        }
        //#if MC>=260100
        //$$ client.player.sendSystemMessage(
        //$$     Texts.translatable(
        //$$         "confluxmap.client_world.command_unconfirmed_chat", restoredProfileName
        //$$     ).withStyle(ChatFormatting.YELLOW)
        //$$ );
        //#else
        client.player.sendMessage(
            Texts.translatable(
                "confluxmap.client_world.command_unconfirmed_chat", restoredProfileName
            ).formatted(Formatting.YELLOW),
            false
        );
        //#endif
    }

    private ClientWorldPolicy policy() {
        return Objects.requireNonNull(policy.get(), "client world policy");
    }

    private ClientWorldProfileResolver.MutationResult rememberMutation(
        final ClientWorldProfileResolver.MutationResult result
    ) {
        if (result.applied()) {
            persistenceError = null;
            resetPersistenceBackoff();
        } else {
            persistenceError = result.error();
            recordPersistenceFailure();
        }
        return result;
    }
}
