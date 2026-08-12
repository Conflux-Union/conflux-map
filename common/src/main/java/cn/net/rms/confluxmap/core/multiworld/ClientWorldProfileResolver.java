package cn.net.rms.confluxmap.core.multiworld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Conservative matcher: strong unique evidence wins; weak or duplicated evidence never guesses. */
public final class ClientWorldProfileResolver {
    public static final int MAX_PROFILES_PER_SERVER = ClientWorldPolicy.DEFAULT_MAX_PROFILES_PER_SERVER;
    private static final double AUTO_SELECT_MIN_CONFIDENCE = 0.60D;
    /** Scores within three percentage points are indistinguishable and require a manual choice. */
    private static final double AUTO_SELECT_ERROR_MARGIN = 0.03D;
    private static final double QUEUE_TWO_MIN_CONFIDENCE = 0.70D;
    private static final double QUEUE_THREE_MIN_CONFIDENCE = 0.80D;
    private static final double QUEUE_THREE_ERROR_MARGIN = 0.15D;
    private static final double SINGLE_PROFILE_MIN_CONFIDENCE = 0.95D;
    private static final double AUXILIARY_WEIGHT = 0.75D;
    private static final double TERRAIN_WEIGHT = 0.25D;
    private static final double TRAJECTORY_WEIGHT = 0.60D;
    private static final double LAST_STABLE_WEIGHT = 0.20D;
    private static final double GAME_MODE_WEIGHT = 0.15D;
    private static final double VISIT_CONTEXT_WEIGHT = 0.25D;
    private static final double IDENTITY_SIGNAL_WEIGHT = 0.25D;
    private static final double OVERWORLD_POSITION_RADIUS = 48.0D;
    private static final double NETHER_POSITION_RADIUS = 6.0D;
    private static final double POSITION_CONFIDENCE_CUTOFF_DISTANCE = 1_024.0D;
    private static final double TERRAIN_MATCH_MIN_SCORE = 0.85D;
    private static final double TERRAIN_DISCRIMINATOR_MIN_GAP = 0.10D;
    // A fingerprint with no matching surface-height evidence tops out at 0.55 from the
    // categorical fields alone. Treat it as a hard contradiction, not a weak match.
    private static final double TERRAIN_HARD_MISMATCH_SCORE = 0.60D;
    private static final long MAX_TRAJECTORY_AGE_MS = 15_000L;
    private static final long PREDICTION_HORIZON_MS = 5_000L;

    private final ClientWorldProfileRegistry registry;
    private final Supplier<UUID> ids;
    private final Persistence persistence;
    private final Supplier<ClientWorldPolicy> policy;
    /** Serializes disk images so a deferred visit never overwrites a newer management mutation. */
    private final Object persistenceLock = new Object();
    private long registryGeneration;
    /** Service-level automatic scoring can admit a profile without learning new bindings on tick. */
    private boolean readOnlyAutomaticResolution;

    public ClientWorldProfileResolver(
        final ClientWorldProfileRegistry registry,
        final Supplier<UUID> ids
    ) {
        this(
            registry,
            ids,
            ignored -> ClientWorldProfileIo.SaveResult.success(),
            ClientWorldPolicy::defaults
        );
    }

    /**
     * Compatibility constructor for callers that already own persistence. New callers should use
     * {@link Persistence}, which receives an isolated registry image before it becomes visible.
     */
    public ClientWorldProfileResolver(
        final ClientWorldProfileRegistry registry,
        final Supplier<UUID> ids,
        final Runnable onChange
    ) {
        this(
            registry,
            ids,
            ignored -> {
                onChange.run();
                return ClientWorldProfileIo.SaveResult.success();
            },
            ClientWorldPolicy::defaults
        );
    }

    public ClientWorldProfileResolver(
        final ClientWorldProfileRegistry registry,
        final Supplier<UUID> ids,
        final Persistence persistence
    ) {
        this(registry, ids, persistence, ClientWorldPolicy::defaults);
    }

    public ClientWorldProfileResolver(
        final ClientWorldProfileRegistry registry,
        final Supplier<UUID> ids,
        final Persistence persistence,
        final Supplier<ClientWorldPolicy> policy
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public ClientWorldResolution resolve(
        final String serverId,
        final ClientWorldObservation observation
    ) {
        return resolve(serverId, observation, false);
    }

    /**
     * Scores an automatic observation without publishing a binding or last-stable pointer. The
     * service uses this on its lifecycle path; explicit user selection still uses {@link #select}.
     * Stable visit persistence records the current observation asynchronously after admission.
     */
    public ClientWorldResolution resolveReadOnly(
        final String serverId,
        final ClientWorldObservation observation
    ) {
        final boolean previous = readOnlyAutomaticResolution;
        readOnlyAutomaticResolution = true;
        try {
            return resolve(serverId, observation, false);
        } finally {
            readOnlyAutomaticResolution = previous;
        }
    }

    /**
     * Whether automatic resolution would create a fresh profile for this observation. The client
     * service uses this to move that durable mutation off its lifecycle thread.
     */
    public boolean needsAutomaticProfileCreation(
        final String serverId,
        final ClientWorldObservation observation
    ) {
        if (observation.seedHash().isEmpty() || !registry.available()) {
            return false;
        }
        final List<ClientWorldProfile> profiles = registry.mutableProfiles(serverId);
        final long seedHash = observation.seedHash().getAsLong();
        return profiles.stream().noneMatch(profile -> profile.matchesSeed(seedHash))
            && profiles.stream().noneMatch(ClientWorldProfile::recognitionDisabled)
            && profiles.size() < policy().maxProfilesPerServer();
    }

    /** Captures the durable automatic-profile creation into an isolated registry image. */
    public PreparedAutomaticProfileMutation prepareAutomaticProfileCreation(
        final String serverId,
        final ClientWorldObservation observation
    ) {
        synchronized (persistenceLock) {
            if (!needsAutomaticProfileCreation(serverId, observation)) {
                return PreparedAutomaticProfileMutation.failure(
                    "automatic client world profile creation is no longer applicable"
                );
            }
            try {
                final ClientWorldProfileRegistry candidate = registry.copy();
                final List<ClientWorldProfile> profiles = candidate.mutableProfiles(serverId);
                final ClientWorldProfile profile = create(
                    profiles, profiles.isEmpty() ? "world" : nextStorageId(), observation
                );
                rememberLastStable(candidate, serverId, profile.id(), observation);
                return PreparedAutomaticProfileMutation.prepared(
                    candidate, registryGeneration, serverId, profile.id()
                );
            } catch (final RuntimeException error) {
                return PreparedAutomaticProfileMutation.failure(errorMessage(error));
            }
        }
    }

    /** Writes a prepared automatic profile while keeping its registry image private. */
    public MutationResult persistPreparedAutomaticProfile(final PreparedAutomaticProfileMutation mutation) {
        if (mutation == null || !mutation.prepared()) {
            return mutation == null
                ? MutationResult.failure("missing prepared automatic client world profile")
                : mutation.result();
        }
        synchronized (persistenceLock) {
            if (mutation.generation != registryGeneration) {
                return MutationResult.failure("client world registry changed before automatic profile persistence");
            }
            try {
                final ClientWorldProfileIo.SaveResult saved = persistence.save(mutation.candidate);
                return saved != null && saved.saved()
                    ? MutationResult.success() : MutationResult.failure(saved == null ? null : saved.error());
            } catch (final RuntimeException error) {
                return MutationResult.failure(errorMessage(error));
            }
        }
    }

    /** Publishes a successfully persisted automatic profile from the client thread. */
    public AutomaticProfilePublication publishPreparedAutomaticProfile(
        final PreparedAutomaticProfileMutation mutation
    ) {
        if (mutation == null || !mutation.prepared()) {
            return new AutomaticProfilePublication(
                mutation == null
                    ? MutationResult.failure("missing prepared automatic client world profile")
                    : mutation.result(),
                null
            );
        }
        synchronized (persistenceLock) {
            if (mutation.generation != registryGeneration) {
                return new AutomaticProfilePublication(
                    MutationResult.failure("client world registry changed before automatic profile publication"), null
                );
            }
            registry.replaceWith(mutation.candidate);
            registryGeneration++;
            return new AutomaticProfilePublication(
                MutationResult.success(), requireProfile(registry.mutableProfiles(mutation.serverId), mutation.profileId)
            );
        }
    }

    /**
     * Restores the live registry image when recognition became unsafe after an automatic profile
     * write had already started. A newer registry generation has already superseded that disk
     * image and therefore needs no rollback.
     */
    public MutationResult rejectPersistedAutomaticProfile(
        final PreparedAutomaticProfileMutation mutation
    ) {
        if (mutation == null || !mutation.prepared()) {
            return mutation == null
                ? MutationResult.failure("missing prepared automatic client world profile")
                : mutation.result();
        }
        synchronized (persistenceLock) {
            if (mutation.generation != registryGeneration) {
                return MutationResult.success();
            }
            try {
                final ClientWorldProfileIo.SaveResult saved = persistence.save(registry.copy());
                return saved != null && saved.saved()
                    ? MutationResult.success() : MutationResult.failure(saved == null ? null : saved.error());
            } catch (final RuntimeException error) {
                return MutationResult.failure(errorMessage(error));
            }
        }
    }

    /** Whether legacy server keys still need a durable merge into their canonical key. */
    public boolean needsServerAliasAdoption(final String canonicalServerId, final List<String> legacyServerIds) {
        return registry.available() && legacyServerIds.stream()
            .anyMatch(legacy -> !Objects.equals(canonicalServerId, legacy) && !registry.profiles(legacy).isEmpty());
    }

    /** Captures an alias merge into an isolated registry image for background persistence. */
    public PreparedServerAliasMutation prepareServerAliasAdoption(
        final String canonicalServerId,
        final List<String> legacyServerIds
    ) {
        synchronized (persistenceLock) {
            final List<String> populatedAliases = legacyServerIds.stream()
                .filter(legacy -> !Objects.equals(canonicalServerId, legacy) && !registry.profiles(legacy).isEmpty())
                .toList();
            if (!registry.available()) {
                return PreparedServerAliasMutation.failure(registry.loadFailure());
            }
            if (populatedAliases.isEmpty()) {
                return PreparedServerAliasMutation.failure("server alias adoption is no longer applicable");
            }
            try {
                final ClientWorldProfileRegistry candidate = registry.copy();
                final List<String> conflicts = new ArrayList<>();
                boolean changed = false;
                for (final String legacyServerId : populatedAliases) {
                    final ClientWorldProfileRegistry.AliasMerge merge = candidate.mergeServerAlias(
                        canonicalServerId, legacyServerId
                    );
                    changed |= merge.changed();
                    conflicts.addAll(merge.conflicts());
                }
                return PreparedServerAliasMutation.prepared(
                    candidate, registryGeneration, canonicalServerId, List.copyOf(conflicts), changed
                );
            } catch (final RuntimeException error) {
                return PreparedServerAliasMutation.failure(errorMessage(error));
            }
        }
    }

    /** Persists a prepared alias merge without exposing its registry image to readers. */
    public MutationResult persistPreparedServerAlias(final PreparedServerAliasMutation mutation) {
        if (mutation == null || !mutation.prepared()) {
            return mutation == null ? MutationResult.failure("missing prepared server alias merge") : mutation.result();
        }
        synchronized (persistenceLock) {
            if (mutation.generation != registryGeneration) {
                return MutationResult.failure("client world registry changed before server alias persistence");
            }
            try {
                final ClientWorldProfileIo.SaveResult saved = persistence.save(mutation.candidate);
                return saved != null && saved.saved()
                    ? MutationResult.success() : MutationResult.failure(saved == null ? null : saved.error());
            } catch (final RuntimeException error) {
                return MutationResult.failure(errorMessage(error));
            }
        }
    }

    /** Publishes a successfully persisted alias merge from the client thread. */
    public ServerAliasResult publishPreparedServerAlias(final PreparedServerAliasMutation mutation) {
        if (mutation == null || !mutation.prepared()) {
            return new ServerAliasResult(
                false, false, List.of(), mutation == null ? "missing prepared server alias merge" : mutation.result().error()
            );
        }
        synchronized (persistenceLock) {
            if (mutation.generation != registryGeneration) {
                return new ServerAliasResult(
                    false, false, List.of(), "client world registry changed before server alias publication"
                );
            }
            registry.replaceWith(mutation.candidate);
            registryGeneration++;
            return new ServerAliasResult(true, mutation.changed, mutation.conflicts, null);
        }
    }

    private ClientWorldResolution resolve(
        final String serverId,
        final ClientWorldObservation observation,
        final boolean suppressLastStable
    ) {
        if (!registry.available()) {
            return ClientWorldResolution.persistenceFailed(registry.loadFailure());
        }
        final List<ClientWorldProfile> profiles = registry.mutableProfiles(serverId);
        if (profiles.isEmpty()) {
            return create(serverId, "world", observation);
        }

        if (observation.seedHash().isPresent()) {
            final long seedHash = observation.seedHash().getAsLong();
            final List<ClientWorldProfile> seedMatches = profiles.stream()
                .filter(profile -> profile.matchesSeed(seedHash))
                .toList();
            if (seedMatches.isEmpty()) {
                if (profiles.stream().anyMatch(ClientWorldProfile::recognitionDisabled)) {
                    return diagnoseBlocked(serverId, observation, "seed_no_compatible_profile");
                }
                if (profiles.size() >= policy().maxProfilesPerServer()) {
                    return ClientWorldResolution.persistenceFailed(
                        "client world profile limit reached",
                        displayInsufficientObservation(profiles, observation, "profile_limit_reached")
                    );
                }
                return create(serverId, nextStorageId(), observation);
            }
            return resolveCandidates(serverId, seedMatches, observation, suppressLastStable);
        }

        return resolveCandidates(serverId, profiles, observation, suppressLastStable);
    }

    /** Persistently adopts old explicit-default-port registry keys without moving map folders. */
    public ServerAliasResult adoptServerAliases(
        final String canonicalServerId,
        final List<String> legacyServerIds
    ) {
        final List<String> populatedAliases = legacyServerIds.stream()
            .filter(legacy -> !registry.profiles(legacy).isEmpty())
            .toList();
        if (populatedAliases.isEmpty()) {
            return new ServerAliasResult(true, false, List.of(), null);
        }
        final List<String> conflicts = new ArrayList<>();
        final Mutation<Boolean> mutation = mutate(copy -> {
            boolean changed = false;
            for (final String legacyServerId : populatedAliases) {
                final ClientWorldProfileRegistry.AliasMerge merge = copy.mergeServerAlias(
                    canonicalServerId, legacyServerId
                );
                changed |= merge.changed();
                conflicts.addAll(merge.conflicts());
            }
            return changed;
        });
        return new ServerAliasResult(
            mutation.applied(), mutation.applied() && Boolean.TRUE.equals(mutation.value()),
            List.copyOf(conflicts), mutation.result().error()
        );
    }

    public record ServerAliasResult(boolean applied, boolean changed, List<String> conflicts, String error) { }

    /**
     * Resolves the first observation after a proxy replaced its upstream world. The previous
     * upstream seed is supplied separately so this boundary can reject a transitional/reused seed
     * before any map session starts.
     */
    public ClientWorldResolution resolveAfterProxyWorldJoin(
        final String serverId,
        final OptionalLong previousSeedHash,
        final ClientWorldObservation observation
    ) {
        return resolveAfterProxyWorldJoin(serverId, previousSeedHash, observation, null);
    }

    /** Read-only automatic variant used after proxy boundaries on the client lifecycle path. */
    public ClientWorldResolution resolveAfterProxyWorldJoinReadOnly(
        final String serverId,
        final OptionalLong previousSeedHash,
        final ClientWorldObservation observation,
        final String departedProfileId
    ) {
        final boolean previous = readOnlyAutomaticResolution;
        readOnlyAutomaticResolution = true;
        try {
            return resolveAfterProxyWorldJoin(serverId, previousSeedHash, observation, departedProfileId);
        } finally {
            readOnlyAutomaticResolution = previous;
        }
    }

    /**
     * Proxy-boundary variant that also excludes the profile which the join packet just left.
     * A real Velocity backend switch cannot arrive back in the same departed child world; allowing
     * that profile to compete merely because its departure point is fresh is a direct route to
     * cross-profile map writes.
     */
    public ClientWorldResolution resolveAfterProxyWorldJoin(
        final String serverId,
        final OptionalLong previousSeedHash,
        final ClientWorldObservation observation,
        final String departedProfileId
    ) {
        if (!registry.available()) {
            return ClientWorldResolution.persistenceFailed(registry.loadFailure());
        }
        final boolean reusedSeed = previousSeedHash.isPresent() && observation.seedHash().isPresent()
            && previousSeedHash.getAsLong() == observation.seedHash().getAsLong();
        if (observation.seedHash().isPresent()) {
            final long seedHash = observation.seedHash().getAsLong();
            final List<ClientWorldProfile> matchingProfiles = registry.mutableProfiles(serverId).stream()
                .filter(profile -> profile.matchesSeed(seedHash))
                .toList();
            if (reusedSeed && matchingProfiles.size() <= 1) {
                return diagnoseBlocked(
                    serverId, observation, "same_seed_proxy_transition", true
                );
            }
            if (matchingProfiles.size() > 1) {
                final ClientWorldResolution scored = scoreCandidates(
                    serverId, matchingProfiles, observation, null, true, departedProfileId
                );
                return scored != null ? scored : ClientWorldResolution.ambiguous(
                    displayInsufficientObservation(
                        matchingProfiles, observation, "same_seed_requires_discriminator"
                    )
                );
            }
        }
        // A real upstream boundary invalidates the old backend-continuity prior even when the
        // replacement has a different seed. Stable identity can be learned again after this
        // observation is independently confirmed.
        return resolve(serverId, observation, true);
    }

    /**
     * Resolves a profile from Velocity's exact registered-server name. An optional legacy profile
     * may adopt the first learned name, but a new unseen name otherwise always gets isolated
     * storage even when it shares every vanilla signal and seed with another backend.
     */
    public ClientWorldResolution resolveVelocityServer(
        final String serverId,
        final String serverName,
        final ClientWorldObservation observation,
        final String legacyProfileId,
        final boolean mayAdoptUnresolvedLegacyProfile
    ) {
        final String normalized = VelocityServerListParser.normalizeServerName(serverName);
        final List<ClientWorldProfile> profiles = registry.mutableProfiles(serverId);
        final List<ClientWorldProfile> exact = profiles.stream()
            .filter(profile -> profile.matchesVelocityServer(normalized))
            .limit(2L)
            .toList();
        if (exact.size() > 1) {
            return ClientWorldResolution.ambiguous();
        }
        if (exact.size() == 1) {
            return resolvedAndLearn(
                serverId,
                exact.get(0),
                observation,
                List.of(),
                ClientWorldResolution.ConfirmationSource.AUTOMATIC
            );
        }

        ClientWorldProfile legacy = legacyProfileId == null ? null : profiles.stream()
            .filter(profile -> profile.id().equals(legacyProfileId))
            .filter(profile -> profile.velocityServerName().isEmpty())
            .findFirst()
            .orElse(null);
        if (legacy == null && mayAdoptUnresolvedLegacyProfile && observation.seedHash().isPresent()) {
            final long observedSeedHash = observation.seedHash().getAsLong();
            final List<ClientWorldProfile> legacySeedMatches = profiles.stream()
                .filter(profile -> profile.velocityServerName().isEmpty())
                .filter(profile -> profile.matchesSeed(observedSeedHash))
                .limit(2L)
                .toList();
            if (legacySeedMatches.size() == 1) {
                legacy = legacySeedMatches.get(0);
            }
        }
        if (legacy != null) {
            final String legacyId = legacy.id();
            final Mutation<String> mutation = mutate(copy -> {
                final ClientWorldProfile candidate = requireProfile(
                    copy.mutableProfiles(serverId), legacyId
                );
                candidate.rename(normalized);
                candidate.bindVelocityServer(normalized);
                candidate.bind(observation, policy().maxBindingsPerProfile());
                rememberLastStable(copy, serverId, candidate.id(), observation);
                return candidate.id();
            });
            return resolvedMutation(serverId, mutation);
        }
        if (profiles.size() >= policy().maxProfilesPerServer()) {
            return ClientWorldResolution.persistenceFailed("client world profile limit reached");
        }
        final String storageId = profiles.isEmpty() ? "world" : nextStorageId();
        final Mutation<String> mutation = mutate(copy -> {
            final List<ClientWorldProfile> candidates = copy.mutableProfiles(serverId);
            final ClientWorldProfile profile = new ClientWorldProfile(
                ids.get().toString(), storageId, normalized
            );
            profile.bind(observation, policy().maxBindingsPerProfile());
            profile.bindVelocityServer(normalized);
            candidates.add(profile);
            rememberLastStable(copy, serverId, profile.id(), observation);
            return profile.id();
        });
        return resolvedMutation(serverId, mutation);
    }

    public ClientWorldResolution select(
        final String serverId,
        final String profileId,
        final ClientWorldObservation observation
    ) {
        return select(serverId, profileId, List.of(), observation);
    }

    /**
     * Confirms one profile and publishes observations that were captured while that exact profile
     * was only provisional. Owned history is applied before the live observation so an older
     * dimension can never replace the position that the player currently occupies.
     */
    public ClientWorldResolution select(
        final String serverId,
        final String profileId,
        final List<ClientWorldObservation> ownedHistory,
        final ClientWorldObservation observation
    ) {
        final Mutation<String> mutation = mutate(copy -> {
            final List<ClientWorldProfile> profiles = copy.mutableProfiles(serverId);
            final ClientWorldProfile profile = requireProfile(profiles, profileId);
            rememberOwnedHistory(profile, ownedHistory);
            // A seed is compatible with multiple logical worlds. Manual confirmation adds the
            // observation to the selected profile without erasing the same-seed membership of
            // any other profile.
            // Candidate-specific historical terrain is match-only evidence. A manual selection
            // must learn the current player-centered sample, never relabel the visit to a saved
            // historical center.
            profile.bind(observation, policy().maxBindingsPerProfile());
            rememberLastStable(copy, serverId, profile.id(), observation);
            return profile.id();
        });
        return resolvedMutation(
            serverId, mutation, List.of(), ClientWorldResolution.ConfirmationSource.MANUAL
        );
    }

    /**
     * Validates one already-admitted provisional identity without running another candidate
     * election. New evidence may confirm the locked profile or prove a hard conflict; a temporary
     * score change can never return a different profile from this method.
     */
    public ClientWorldResolution validateProvisional(
        final String serverId,
        final String profileId,
        final ClientWorldObservation observation,
        final List<ClientWorldResolution.Candidate> admissionDiagnostics,
        final boolean suppressLastStable
    ) {
        return validateProvisional(
            serverId, profileId, List.of(), observation, admissionDiagnostics, suppressLastStable
        );
    }

    /** Validates a provisional profile together with observations owned by that hypothesis. */
    public ClientWorldResolution validateProvisional(
        final String serverId,
        final String profileId,
        final List<ClientWorldObservation> ownedHistory,
        final ClientWorldObservation observation,
        final List<ClientWorldResolution.Candidate> admissionDiagnostics,
        final boolean suppressLastStable
    ) {
        final ClientWorldProfile locked = requireProfile(serverId, profileId);
        final List<ClientWorldProfile> compatible = observation.seedHash().isPresent()
            ? registry.mutableProfiles(serverId).stream()
                .filter(profile -> profile.matchesSeed(observation.seedHash().getAsLong()))
                .toList()
            : List.copyOf(registry.mutableProfiles(serverId));
        final ClientWorldVisit visit = observation.dimensionId() == null
            ? null : locked.visit(observation.dimensionId());
        final boolean seedConflict = observation.seedHash().isPresent()
            && !locked.matchesSeed(observation.seedHash().getAsLong());
        final boolean signalConflict = locked.hasSignalConflict(observation);
        final boolean contextConflict = visit != null && visit.contextMatch(observation.signals()).hasStableConflict();

        double lockedTerrainScore = Double.NaN;
        boolean terrainConflict = false;
        if (visit != null) {
            final ClientWorldTerrainFingerprint observed = observation.terrainFingerprintFor(profileId);
            final ClientWorldTerrainAnchor anchor = visit.terrainAnchorFor(observed);
            if (anchor != null) {
                final ClientWorldTerrainFingerprint.Match match = observed.match(anchor.fingerprint());
                if (match.available()) {
                    lockedTerrainScore = match.score();
                    terrainConflict = lockedTerrainScore < TERRAIN_HARD_MISMATCH_SCORE;
                }
            }
        }
        if (seedConflict || signalConflict || contextConflict || terrainConflict) {
            return diagnoseBlocked(
                serverId, observation, "provisional_identity_conflict", suppressLastStable
            );
        }

        final boolean terrainConfirmed = !Double.isNaN(lockedTerrainScore)
            && lockedTerrainScore >= TERRAIN_MATCH_MIN_SCORE
            && provisionalTerrainDiscriminator(
                locked, lockedTerrainScore, compatible, observation
            );
        if (terrainConfirmed) {
            return resolvedAndLearn(
                serverId, locked, ownedHistory, observation, admissionDiagnostics,
                ClientWorldResolution.ConfirmationSource.TERRAIN
            );
        }
        final boolean stableSignalsConfirmed = compatible.size() == 1
            && identitySignalMatches(locked, observation) >= 2;
        if (stableSignalsConfirmed) {
            return resolvedAndLearn(
                serverId, locked, ownedHistory, observation, admissionDiagnostics,
                ClientWorldResolution.ConfirmationSource.STABLE_SIGNALS
            );
        }
        return ClientWorldResolution.provisional(locked, admissionDiagnostics);
    }

    private static boolean provisionalTerrainDiscriminator(
        final ClientWorldProfile locked,
        final double lockedScore,
        final List<ClientWorldProfile> compatible,
        final ClientWorldObservation observation
    ) {
        for (final ClientWorldProfile other : compatible) {
            if (other.id().equals(locked.id()) || other.hasSignalConflict(observation)) {
                continue;
            }
            final ClientWorldVisit visit = other.visit(observation.dimensionId());
            final ClientWorldTerrainFingerprint observed = observation.terrainFingerprintFor(other.id());
            final ClientWorldTerrainAnchor anchor = visit == null ? null : visit.terrainAnchorFor(observed);
            if (anchor == null) {
                return false;
            }
            final ClientWorldTerrainFingerprint.Match match = observed.match(anchor.fingerprint());
            if (!match.available() || lockedScore - match.score() < TERRAIN_DISCRIMINATOR_MIN_GAP) {
                return false;
            }
        }
        return true;
    }

    /**
     * Activates a command-selected profile without learning the current observation. The command
     * is sent before a proxy may replace its upstream world, so binding the old evidence here
     * would corrupt the target profile.
     */
    public ClientWorldResolution activateCommand(final String serverId, final String profileId) {
        return ClientWorldResolution.resolved(
            requireProfile(serverId, profileId), List.of(),
            ClientWorldResolution.ConfirmationSource.COMMAND
        );
    }

    /** Whether a command-selected profile has an explicit seed binding that rejects this visit. */
    public boolean hasKnownSeedConflict(final String serverId, final String profileId, final long seedHash) {
        final ClientWorldProfile profile = requireProfile(serverId, profileId);
        return profile.hasKnownSeedBinding() && !profile.matchesSeed(seedHash);
    }

    /** Whether every known identity binding contradicts the observed command-switch target. */
    public boolean hasKnownSignalConflict(
        final String serverId,
        final String profileId,
        final ClientWorldObservation observation
    ) {
        return requireProfile(serverId, profileId).hasSignalConflict(observation);
    }

    /** Looks up one submitted command within one server's isolated profile collection. */
    public Optional<ClientWorldProfile> profileForCommand(final String serverId, final String rawText) {
        if (!registry.available()) {
            return Optional.empty();
        }
        final Optional<String> command = ClientWorldCommand.fromSubmittedText(rawText);
        if (command.isEmpty()) {
            return Optional.empty();
        }
        return registry.mutableProfiles(serverId).stream()
            .filter(profile -> profile.hasSwitchCommand(command.get()))
            .findFirst();
    }

    public CommandBindingResult addSwitchCommand(
        final String serverId,
        final String profileId,
        final String command,
        final boolean rebind
    ) {
        final String normalized;
        final List<ClientWorldProfile> profiles;
        final ClientWorldProfile target;
        try {
            normalized = ClientWorldCommand.normalizeConfigured(command);
            profiles = registry.mutableProfiles(serverId);
            target = requireProfile(profiles, profileId);
        } catch (final RuntimeException error) {
            return CommandBindingResult.failed(null, MutationResult.failure(errorMessage(error)));
        }
        final ClientWorldProfile owner = profiles.stream()
            .filter(profile -> profile.hasSwitchCommand(normalized))
            .findFirst()
            .orElse(null);
        if (owner != null && owner != target && !rebind) {
            return CommandBindingResult.conflict(owner);
        }
        if (owner == target && target.hasSwitchCommand(normalized)) {
            return CommandBindingResult.bound(target, MutationResult.success());
        }
        final Mutation<String> mutation = mutate(copy -> {
            final List<ClientWorldProfile> copiedProfiles = copy.mutableProfiles(serverId);
            final ClientWorldProfile copiedTarget = requireProfile(copiedProfiles, profileId);
            final ClientWorldProfile copiedOwner = copiedProfiles.stream()
                .filter(profile -> profile.hasSwitchCommand(normalized))
                .findFirst()
                .orElse(null);
            if (copiedOwner != null && copiedOwner != copiedTarget) {
                copiedOwner.removeSwitchCommand(normalized);
            }
            copiedTarget.addSwitchCommand(normalized);
            return copiedTarget.id();
        });
        return mutation.applied()
            ? CommandBindingResult.bound(requireProfile(serverId, mutation.value()), mutation.result())
            : CommandBindingResult.failed(target, mutation.result());
    }

    public MutationResult removeSwitchCommand(final String serverId, final String profileId, final String command) {
        try {
            final String normalized = ClientWorldCommand.normalizeConfigured(command);
            if (!requireProfile(serverId, profileId).hasSwitchCommand(normalized)) {
                return MutationResult.success();
            }
            return mutate(copy -> {
                requireProfile(copy.mutableProfiles(serverId), profileId).removeSwitchCommand(normalized);
                return profileId;
            }).result();
        } catch (final RuntimeException error) {
            return MutationResult.failure(errorMessage(error));
        }
    }

    public ClientWorldResolution createAndSelect(
        final String serverId,
        final String displayName,
        final ClientWorldObservation observation
    ) {
        if (!registry.available()) {
            return ClientWorldResolution.persistenceFailed(registry.loadFailure());
        }
        if (registry.mutableProfiles(serverId).size() >= policy().maxProfilesPerServer()) {
            return ClientWorldResolution.persistenceFailed(
                "client world profile limit reached",
                displayInsufficientObservation(
                    registry.mutableProfiles(serverId), observation, "profile_limit_reached"
                )
            );
        }
        final Mutation<String> mutation = mutate(copy -> {
            final List<ClientWorldProfile> profiles = copy.mutableProfiles(serverId);
            final ClientWorldProfile profile = create(profiles, nextStorageId(), observation);
            profile.rename(displayName);
            rememberLastStable(copy, serverId, profile.id(), observation);
            return profile.id();
        });
        return resolvedMutation(
            serverId, mutation, List.of(), ClientWorldResolution.ConfirmationSource.CREATED
        );
    }

    public List<ClientWorldProfile> profiles(final String serverId) {
        return registry.profiles(serverId);
    }

    public boolean available() {
        return registry.available();
    }

    public String loadFailure() {
        return registry.loadFailure();
    }

    /**
     * Publishes a registry that was reloaded successfully after a fail-closed startup. This never
     * overwrites an already-available registry, preventing a stale UI retry from replacing newer
     * persisted state.
     */
    public MutationResult restore(final ClientWorldProfileRegistry restored) {
        Objects.requireNonNull(restored, "restored");
        synchronized (persistenceLock) {
            if (registry.available()) {
                return MutationResult.success();
            }
            if (!restored.available()) {
                return MutationResult.failure(restored.loadFailure());
            }
            registry.replaceWith(restored);
            registryGeneration++;
            return MutationResult.success();
        }
    }

    /** Whether this server already has a profile bound to the supplied seed signature. */
    public boolean hasProfileWithSeed(final String serverId, final long seedHash) {
        if (!registry.available()) {
            return false;
        }
        return registry.mutableProfiles(serverId).stream().anyMatch(profile -> profile.matchesSeed(seedHash));
    }

    public int profileCountWithSeed(final String serverId, final long seedHash) {
        if (!registry.available()) {
            return 0;
        }
        return (int) registry.mutableProfiles(serverId).stream()
            .filter(profile -> profile.matchesSeed(seedHash))
            .limit(2L)
            .count();
    }

    public MutationResult rename(final String serverId, final String profileId, final String displayName) {
        return mutate(copy -> {
            requireProfile(copy.mutableProfiles(serverId), profileId).rename(displayName);
            return profileId;
        }).result();
    }

    public MutationResult clearBindings(final String serverId, final String profileId) {
        return mutate(copy -> {
            requireProfile(copy.mutableProfiles(serverId), profileId).clearBindings();
            return profileId;
        }).result();
    }

    /**
     * Persists per-dimension visit evidence after a stable profile receives late terrain data.
     * This intentionally leaves identity bindings untouched: movement inside one world must not
     * create a new candidate signature.
     */
    public MutationResult rememberVisit(
        final String serverId,
        final String profileId,
        final ClientWorldObservation observation
    ) {
        if (observation.dimensionId() == null) {
            return MutationResult.success();
        }
        return mutate(copy -> {
            // Stable movement refreshes the active visit with the current player-centered sample.
            // Candidate-specific historical probes are only for matching/selection and must not
            // relabel the active profile's new terrain center.
            requireProfile(copy.mutableProfiles(serverId), profileId).rememberVisit(observation);
            rememberLastStable(copy, serverId, profileId, observation);
            return profileId;
        }).result();
    }

    /**
     * Captures a stable visit into an isolated registry image for background persistence.
     * The caller must persist and publish this object through the methods below; publishing is
     * rejected when a newer registry mutation has already reached disk.
     */
    public PreparedVisitMutation prepareRememberVisit(
        final String serverId,
        final String profileId,
        final ClientWorldObservation observation
    ) {
        if (observation.dimensionId() == null) {
            return PreparedVisitMutation.noop();
        }
        synchronized (persistenceLock) {
            if (!registry.available()) {
                return PreparedVisitMutation.failure(registry.loadFailure());
            }
            try {
                final ClientWorldProfileRegistry candidate = registry.copy();
                requireProfile(candidate.mutableProfiles(serverId), profileId).rememberVisit(observation);
                rememberLastStable(candidate, serverId, profileId, observation);
                return PreparedVisitMutation.prepared(candidate, registryGeneration);
            } catch (final RuntimeException error) {
                return PreparedVisitMutation.failure(errorMessage(error));
            }
        }
    }

    /** Persists a prepared visit without exposing its registry image to readers. */
    public MutationResult persistPreparedVisit(final PreparedVisitMutation mutation) {
        if (mutation == null || !mutation.prepared()) {
            return mutation == null ? MutationResult.failure("missing prepared client world visit") : mutation.result();
        }
        synchronized (persistenceLock) {
            if (mutation.generation != registryGeneration) {
                return MutationResult.failure("client world registry changed before visit persistence");
            }
            try {
                final ClientWorldProfileIo.SaveResult saved = persistence.save(mutation.candidate);
                return saved != null && saved.saved()
                    ? MutationResult.success() : MutationResult.failure(saved == null ? null : saved.error());
            } catch (final RuntimeException error) {
                return MutationResult.failure(errorMessage(error));
            }
        }
    }

    /** Publishes a successfully persisted visit on the client thread. */
    public MutationResult publishPreparedVisit(final PreparedVisitMutation mutation) {
        if (mutation == null || !mutation.prepared()) {
            return mutation == null ? MutationResult.failure("missing prepared client world visit") : mutation.result();
        }
        synchronized (persistenceLock) {
            if (mutation.generation != registryGeneration) {
                return MutationResult.failure("client world registry changed before visit publication");
            }
            registry.replaceWith(mutation.candidate);
            registryGeneration++;
            return MutationResult.success();
        }
    }

    /** Removes only registry metadata. Callers must relocate the profile's local data first. */
    public MutationResult delete(final String serverId, final String profileId) {
        try {
            requireProfile(serverId, profileId);
        } catch (final RuntimeException error) {
            return MutationResult.failure(errorMessage(error));
        }
        return mutate(copy -> {
            final boolean removed = copy.mutableProfiles(serverId).removeIf(profile -> profile.id().equals(profileId));
            if (!removed) {
                throw new IllegalArgumentException("unknown client world profile " + profileId);
            }
            if (profileId.equals(copy.lastStableProfileId(serverId))) {
                copy.setLastStableProfileId(serverId, null);
            }
            return profileId;
        }).result();
    }

    public record CommandBindingResult(Status status, ClientWorldProfile profile, MutationResult mutation) {
        public enum Status { BOUND, CONFLICT, PERSISTENCE_FAILED }

        static CommandBindingResult bound(final ClientWorldProfile profile, final MutationResult mutation) {
            return new CommandBindingResult(Status.BOUND, profile, mutation);
        }

        static CommandBindingResult conflict(final ClientWorldProfile profile) {
            return new CommandBindingResult(Status.CONFLICT, profile, MutationResult.success());
        }

        static CommandBindingResult failed(final ClientWorldProfile profile, final MutationResult mutation) {
            return new CommandBindingResult(Status.PERSISTENCE_FAILED, profile, mutation);
        }
    }

    /** Outcome for profile-management mutations; an unsuccessful result never changed the registry. */
    public record MutationResult(boolean applied, String error) {
        static MutationResult success() {
            return new MutationResult(true, null);
        }

        static MutationResult failure(final String error) {
            return new MutationResult(false, error == null || error.isBlank() ? "unknown persistence error" : error);
        }
    }

    /** Result of publishing an automatic profile after its isolated image reached durable storage. */
    public record AutomaticProfilePublication(MutationResult mutation, ClientWorldProfile profile) {
    }

    /** Opaque isolated registry image used by the client IO queue for automatic profile creation. */
    public static final class PreparedAutomaticProfileMutation {
        private final ClientWorldProfileRegistry candidate;
        private final long generation;
        private final String serverId;
        private final String profileId;
        private final MutationResult result;

        private PreparedAutomaticProfileMutation(
            final ClientWorldProfileRegistry candidate,
            final long generation,
            final String serverId,
            final String profileId,
            final MutationResult result
        ) {
            this.candidate = candidate;
            this.generation = generation;
            this.serverId = serverId;
            this.profileId = profileId;
            this.result = result;
        }

        private static PreparedAutomaticProfileMutation prepared(
            final ClientWorldProfileRegistry candidate,
            final long generation,
            final String serverId,
            final String profileId
        ) {
            return new PreparedAutomaticProfileMutation(
                candidate, generation, serverId, profileId, MutationResult.success()
            );
        }

        private static PreparedAutomaticProfileMutation failure(final String error) {
            return new PreparedAutomaticProfileMutation(
                null, -1L, null, null, MutationResult.failure(error)
            );
        }

        public boolean prepared() {
            return candidate != null && result.applied();
        }

        public MutationResult result() {
            return result;
        }
    }

    /** Opaque isolated registry image used by the client IO queue for a server alias merge. */
    public static final class PreparedServerAliasMutation {
        private final ClientWorldProfileRegistry candidate;
        private final long generation;
        private final String canonicalServerId;
        private final List<String> conflicts;
        private final boolean changed;
        private final MutationResult result;

        private PreparedServerAliasMutation(
            final ClientWorldProfileRegistry candidate,
            final long generation,
            final String canonicalServerId,
            final List<String> conflicts,
            final boolean changed,
            final MutationResult result
        ) {
            this.candidate = candidate;
            this.generation = generation;
            this.canonicalServerId = canonicalServerId;
            this.conflicts = conflicts;
            this.changed = changed;
            this.result = result;
        }

        private static PreparedServerAliasMutation prepared(
            final ClientWorldProfileRegistry candidate,
            final long generation,
            final String canonicalServerId,
            final List<String> conflicts,
            final boolean changed
        ) {
            return new PreparedServerAliasMutation(
                candidate, generation, canonicalServerId, conflicts, changed, MutationResult.success()
            );
        }

        private static PreparedServerAliasMutation failure(final String error) {
            return new PreparedServerAliasMutation(
                null, -1L, null, List.of(), false, MutationResult.failure(error)
            );
        }

        public boolean prepared() {
            return candidate != null && result.applied();
        }

        public MutationResult result() {
            return result;
        }
    }

    /** Opaque isolated registry image used by the client IO queue for a visit refresh. */
    public static final class PreparedVisitMutation {
        private final ClientWorldProfileRegistry candidate;
        private final long generation;
        private final MutationResult result;

        private PreparedVisitMutation(
            final ClientWorldProfileRegistry candidate,
            final long generation,
            final MutationResult result
        ) {
            this.candidate = candidate;
            this.generation = generation;
            this.result = result;
        }

        private static PreparedVisitMutation prepared(
            final ClientWorldProfileRegistry candidate,
            final long generation
        ) {
            return new PreparedVisitMutation(candidate, generation, MutationResult.success());
        }

        private static PreparedVisitMutation noop() {
            return new PreparedVisitMutation(null, -1L, MutationResult.success());
        }

        private static PreparedVisitMutation failure(final String error) {
            return new PreparedVisitMutation(null, -1L, MutationResult.failure(error));
        }

        public boolean prepared() {
            return candidate != null && result.applied();
        }

        public MutationResult result() {
            return result;
        }
    }

    /** Persists an isolated registry copy. Returning failure prevents publication in memory. */
    @FunctionalInterface
    public interface Persistence {
        ClientWorldProfileIo.SaveResult save(ClientWorldProfileRegistry registry);
    }

    private ClientWorldResolution resolveCandidates(
        final String serverId,
        final List<ClientWorldProfile> candidates,
        final ClientWorldObservation observation,
        final boolean suppressLastStable
    ) {
        final ClientWorldResolution scored = scoreCandidates(
            serverId, candidates, observation, null, suppressLastStable, null
        );
        return scored != null
            ? scored
            : ClientWorldResolution.ambiguous(displayInsufficientObservation(candidates, observation));
    }

    /** Runs normal diagnostics while a service-level safety rule still forbids auto selection. */
    public ClientWorldResolution diagnoseBlocked(
        final String serverId,
        final ClientWorldObservation observation,
        final String blocker
    ) {
        return diagnoseBlocked(serverId, observation, blocker, false);
    }

    public ClientWorldResolution diagnoseBlocked(
        final String serverId,
        final ClientWorldObservation observation,
        final String blocker,
        final boolean suppressLastStable
    ) {
        final List<ClientWorldProfile> all = registry.mutableProfiles(serverId);
        final List<ClientWorldProfile> candidates = observation.seedHash().isPresent()
            ? all.stream().filter(profile -> profile.matchesSeed(observation.seedHash().getAsLong())).toList()
            : List.copyOf(all);
        final List<ClientWorldProfile> visible = candidates.isEmpty() ? List.copyOf(all) : candidates;
        final ClientWorldResolution result = scoreCandidates(
            serverId, visible, observation, blocker, suppressLastStable, null
        );
        return result == null
            ? ClientWorldResolution.ambiguous(displayInsufficientObservation(visible, observation, blocker))
            : result;
    }

    private ClientWorldResolution scoreCandidates(
        final String serverId,
        final List<ClientWorldProfile> profiles,
        final ClientWorldObservation observation,
        final String forcedBlocker,
        final boolean suppressLastStable,
        final String departedProfileId
    ) {
        if (observation.dimensionId() == null
            || observation.gameMode() == null && observation.position() == null
                && observation.trajectory() == null && observation.terrainFingerprint() == null
                && observation.terrainFingerprintsByProfileId().isEmpty()) {
            return null;
        }
        final List<CandidateScore> scores = new ArrayList<>();
        final List<TerrainCacheEntry> terrainCache = new ArrayList<>();
            final ClientWorldProfileRegistry.LastStableProfile lastStable = registry.lastStableProfile(serverId);
        final String lastStableProfileId = lastStable == null ? null : lastStable.profileId();
        final boolean strongerCurrentTrajectory = !suppressLastStable
            && lastStableProfileId != null
            && strongerCurrentTrajectoryExists(profiles, lastStableProfileId, observation);
        for (final ClientWorldProfile profile : profiles) {
            final ClientWorldVisit dimensionVisit = profile.visit(observation.dimensionId());
            ClientWorldVisit continuityVisit = profile.lastObservedVisit(observation.dimensionId());
            boolean checkpointBackedVisit = false;
            final ClientWorldVisit checkpointVisit = transientVisitFromCandidateTrajectory(
                profile.id(), observation
            );
            if (checkpointVisit != null && (continuityVisit == null
                || checkpointVisit.lastVisitedAtEpochMs() >= continuityVisit.lastVisitedAtEpochMs())) {
                // The checkpoint is captured at the departure boundary on the client thread.
                // Prefer it over an older registry image that may still be waiting for async IO.
                continuityVisit = checkpointVisit;
                checkpointBackedVisit = true;
            }
            final ClientWorldVisit lastObservedVisit = profile.lastObservedVisit();
            final boolean knownDimensionHistoryAfterProxyBoundary = suppressLastStable
                && dimensionVisit != null;
            final ClientWorldVisit visit = dimensionVisit == null ? continuityVisit : dimensionVisit;
            final boolean seedCompatible = observation.seedHash().isEmpty()
                || profile.matchesSeed(observation.seedHash().getAsLong());
            final boolean lastDimensionMismatch = lastObservedVisit != null
                && !observation.dimensionId().equals(lastObservedVisit.dimensionId())
                // A profile-owned checkpoint is current-dimension continuity evidence. Its
                // existence must outrank a stale persisted visit in another dimension.
                && !checkpointBackedVisit
                // A proxy boundary may arrive while a same-profile dimension transition is still
                // waiting for persistence. A previously confirmed visit in this dimension is
                // safer continuity evidence than treating the profile's other-dimension visit as
                // a cross-profile conflict; the resulting selection remains provisional.
                && !knownDimensionHistoryAfterProxyBoundary;
            if (visit == null) {
                final boolean conflicted = !seedCompatible || profile.hasSignalConflict(observation);
                scores.add(lastDimensionMismatch
                    ? CandidateScore.dimensionMismatch(profile, seedCompatible)
                    : profile.visits().isEmpty()
                        ? CandidateScore.legacy(profile, conflicted, seedCompatible)
                        : CandidateScore.dimensionUnavailable(profile, conflicted, seedCompatible));
                continue;
            }
            final List<String> reasons = new ArrayList<>();
            final List<ClientWorldResolution.Factor> factors = new ArrayList<>();
            if (checkpointBackedVisit) {
                reasons.add("candidate_dimension_checkpoint");
            }
            if (knownDimensionHistoryAfterProxyBoundary) {
                reasons.add("known_dimension_history_after_proxy_boundary");
            }
            double terrainScore = Double.NaN;
            int terrainComparableChunks = 0;
            final ClientWorldVisit.ContextMatch context = visit.contextMatch(observation.signals());
            if (observation.seedHash().isPresent()) {
                reasons.add(seedCompatible ? "seed_match" : "seed_conflict");
            }
            double auxiliary = 0.0D;
            double availableAuxiliaryWeight = 0.0D;
            int independentFactors = 0;
            final TrajectoryEvidence trajectoryEvidence = continuityVisit == null
                ? TrajectoryEvidence.unavailable()
                : trajectoryEvidence(profile.id(), continuityVisit, observation);
            final double trajectoryScore = trajectoryEvidence.confidence();
            // Position is intentionally owned only by the latest dimension visit. At a proxy
            // boundary, a previously confirmed visit in the arriving dimension still proves that
            // this profile can legitimately host it, but only enough for a buffered provisional
            // admission until independent confirmation arrives.
            final boolean continuityEvidence = trajectoryScore > 0.0D
                || knownDimensionHistoryAfterProxyBoundary;
            if (trajectoryScore >= 0.0D) {
                auxiliary += TRAJECTORY_WEIGHT * trajectoryScore;
                availableAuxiliaryWeight += TRAJECTORY_WEIGHT;
                if (trajectoryScore > 0.0D) {
                    independentFactors++;
                }
                reasons.add(trajectoryScore > 0.0D ? "trajectory_continuity" : "trajectory_stale");
            }
            if (knownDimensionHistoryAfterProxyBoundary && trajectoryScore <= 0.0D) {
                reasons.add("known_dimension_history_continuity");
            }
            final boolean lastStableAvailable = lastStableProfileId != null
                && !suppressLastStable && !strongerCurrentTrajectory;
            if (lastStableAvailable) {
                availableAuxiliaryWeight += LAST_STABLE_WEIGHT;
                if (profile.id().equals(lastStableProfileId)) {
                    if (lastStable.conflicts(observation)) {
                        reasons.add("last_stable_conflict");
                    } else {
                        auxiliary += LAST_STABLE_WEIGHT;
                        independentFactors++;
                        reasons.add("last_stable_profile");
                    }
                } else {
                    reasons.add("not_last_stable_profile");
                }
            } else if (lastStableProfileId != null) {
                reasons.add(strongerCurrentTrajectory
                    ? "last_stable_suppressed_stronger_current_trajectory"
                    : "last_stable_suppressed_world_boundary");
            }
            final ClientWorldProfile.IdentitySignalMatch identity = profile.identitySignalMatch(observation);
            final int identitySignalMatches = identity.matches();
            if (identity.comparable() > 0) {
                final double identityScore = Math.min(1.0D, identitySignalMatches / 2.0D);
                auxiliary += IDENTITY_SIGNAL_WEIGHT * identityScore;
                availableAuxiliaryWeight += IDENTITY_SIGNAL_WEIGHT;
                independentFactors++;
                reasons.add("identity_signals_" + identitySignalMatches);
            }
            if (observation.gameMode() != null && visit.gameMode() != null) {
                final boolean matches = observation.gameMode().equals(visit.gameMode());
                auxiliary += matches ? GAME_MODE_WEIGHT : 0.0D;
                availableAuxiliaryWeight += GAME_MODE_WEIGHT;
                if (matches) {
                    independentFactors++;
                }
                reasons.add(matches ? "game_mode_match" : "game_mode_mismatch");
            }
            int queue = 3;
            final double continuityDistance = trajectoryEvidence.corridorDistance();
            final boolean corridorNear;
            if (continuityDistance >= 0.0D) {
                final double radius = positionRadius(observation.dimensionId());
                if (continuityDistance <= radius) {
                    queue = 2;
                    corridorNear = true;
                    reasons.add("corridor_near");
                } else {
                    corridorNear = false;
                    reasons.add("corridor_outside_radius");
                }
            } else if (observation.position() != null && continuityVisit != null
                && continuityVisit.lastPosition() != null) {
                final double distance = observation.position().spatialDistanceTo(
                    continuityVisit.lastPosition()
                );
                if (distance <= positionRadius(observation.dimensionId())) {
                    queue = 2;
                    corridorNear = true;
                    reasons.add("position_near_without_trajectory");
                } else {
                    queue = 3;
                    corridorNear = false;
                    reasons.add("position_far_without_trajectory");
                }
            } else {
                corridorNear = false;
            }
            final ClientWorldTerrainFingerprint observedTerrain = observation.terrainFingerprintFor(profile.id());
            boolean terrainAvailable = false;
            boolean terrainConflict = false;
            final ClientWorldTerrainAnchor terrainAnchor = visit.terrainAnchorFor(observedTerrain);
            if (terrainAnchor != null) {
                final ClientWorldTerrainFingerprint.Match terrain = cachedTerrainMatch(
                    terrainCache, observation.dimensionId(), terrainAnchor.position(),
                    observedTerrain, terrainAnchor.fingerprint()
                );
                if (terrain.available()) {
                    terrainScore = terrain.score();
                    terrainComparableChunks = terrain.comparableChunks();
                    terrainAvailable = true;
                    if (corridorNear) {
                        queue = 1;
                    }
                    reasons.add("terrain_" + terrain.comparableChunks() + "_of_9");
                    if (terrainScore < TERRAIN_HARD_MISMATCH_SCORE) {
                        terrainConflict = true;
                        reasons.add("terrain_mismatch");
                    } else if (terrainScore < TERRAIN_MATCH_MIN_SCORE) {
                        reasons.add("terrain_weak_match");
                    }
                } else {
                    reasons.add("terrain_unavailable");
                }
            } else {
                reasons.add("terrain_unavailable");
            }
            if (context.shared() > 0) {
                auxiliary += VISIT_CONTEXT_WEIGHT * context.score();
                availableAuxiliaryWeight += VISIT_CONTEXT_WEIGHT;
                if (context.score() > 0.0D) {
                    independentFactors++;
                }
                reasons.add("visit_context_" + context.matches() + "_of_" + context.shared());
            }
            final boolean stablePointerConflict = profile.id().equals(lastStableProfileId)
                && lastStable != null && lastStableAvailable && lastStable.conflicts(observation);
            final boolean departedAtBoundary = departedProfileId != null
                && departedProfileId.equals(profile.id());
            final boolean conflicted = departedAtBoundary || lastDimensionMismatch || !seedCompatible
                || profile.hasSignalConflict(observation)
                || context.hasStableConflict() || terrainConflict || stablePointerConflict;
            if (conflicted) {
                reasons.add(departedAtBoundary ? "departed_profile_boundary"
                    : lastDimensionMismatch ? "last_dimension_mismatch"
                    : terrainConflict ? "terrain_conflict"
                    : context.hasStableConflict() ? "visit_context_conflict"
                    : stablePointerConflict ? "last_stable_conflict"
                    : !seedCompatible ? "seed_conflict" : "signal_conflict");
            }
            final double auxiliaryScore = availableAuxiliaryWeight == 0.0D
                ? 0.0D : auxiliary / availableAuxiliaryWeight;
            final double score = terrainAvailable
                ? AUXILIARY_WEIGHT * auxiliaryScore + TERRAIN_WEIGHT * terrainScore
                : auxiliaryScore;
            final double auxiliaryScale = terrainAvailable ? AUXILIARY_WEIGHT : 1.0D;
            factors.add(diagnosticFactor(
                "trajectory", trajectoryScore >= 0.0D, Math.max(0.0D, trajectoryScore),
                TRAJECTORY_WEIGHT, availableAuxiliaryWeight, auxiliaryScale, false,
                trajectoryMetrics(
                    continuityVisit == null ? visit : continuityVisit,
                    observation, trajectoryEvidence
                )
            ));
            factors.add(diagnosticFactor(
                "last_stable", lastStableAvailable,
                lastStableAvailable && profile.id().equals(lastStableProfileId)
                    && !stablePointerConflict ? 1.0D : 0.0D,
                LAST_STABLE_WEIGHT, availableAuxiliaryWeight, auxiliaryScale, stablePointerConflict,
                java.util.Map.of(
                    "hit", Boolean.toString(profile.id().equals(lastStableProfileId)),
                    "suppressed", Boolean.toString(!lastStableAvailable),
                    "suppression_reason", suppressLastStable ? "world_boundary"
                        : strongerCurrentTrajectory ? "stronger_current_trajectory" : "none"
                )
            ));
            factors.add(diagnosticFactor(
                "identity_signals", identity.comparable() > 0,
                Math.min(1.0D, identitySignalMatches / 2.0D), IDENTITY_SIGNAL_WEIGHT,
                availableAuxiliaryWeight, auxiliaryScale, profile.hasSignalConflict(observation),
                java.util.Map.of(
                    "matches", Integer.toString(identitySignalMatches),
                    "comparable", Integer.toString(identity.comparable())
                )
            ));
            final boolean gameModeAvailable = observation.gameMode() != null && visit.gameMode() != null;
            factors.add(diagnosticFactor(
                "game_mode", gameModeAvailable,
                gameModeAvailable && observation.gameMode().equals(visit.gameMode()) ? 1.0D : 0.0D,
                GAME_MODE_WEIGHT, availableAuxiliaryWeight, auxiliaryScale, false,
                java.util.Map.of(
                    "observed", observation.gameMode() == null ? "unavailable" : observation.gameMode(),
                    "candidate", visit.gameMode() == null ? "unavailable" : visit.gameMode()
                )
            ));
            factors.add(diagnosticFactor(
                "visit_context", context.shared() > 0, context.score(), VISIT_CONTEXT_WEIGHT,
                availableAuxiliaryWeight, auxiliaryScale, context.hasStableConflict(),
                java.util.Map.of(
                    "matches", Integer.toString(context.matches()),
                    "comparable", Integer.toString(context.shared())
                )
            ));
            factors.add(new ClientWorldResolution.Factor(
                "terrain",
                terrainAvailable ? ClientWorldResolution.FactorAvailability.AVAILABLE
                    : ClientWorldResolution.FactorAvailability.UNAVAILABLE,
                terrainAvailable ? terrainScore : 0.0D, TERRAIN_WEIGHT,
                terrainAvailable ? TERRAIN_WEIGHT : 0.0D,
                terrainAvailable ? TERRAIN_WEIGHT * terrainScore : 0.0D,
                terrainConflict,
                java.util.Map.of(
                    "comparable_chunks", Integer.toString(terrainComparableChunks),
                    "high_match", Boolean.toString(terrainAvailable && terrainScore >= TERRAIN_MATCH_MIN_SCORE),
                    "hard_mismatch", Boolean.toString(terrainConflict)
                )
            ));
            factors.add(new ClientWorldResolution.Factor(
                "seed_filter",
                observation.seedHash().isPresent() ? ClientWorldResolution.FactorAvailability.AVAILABLE
                    : ClientWorldResolution.FactorAvailability.UNAVAILABLE,
                observation.seedHash().isPresent() ? 1.0D : 0.0D,
                0.0D, 0.0D, 0.0D, !seedCompatible,
                java.util.Map.of("compatible", Boolean.toString(seedCompatible))
            ));
            factors.add(new ClientWorldResolution.Factor(
                "proxy_boundary",
                departedProfileId == null ? ClientWorldResolution.FactorAvailability.UNAVAILABLE
                    : ClientWorldResolution.FactorAvailability.AVAILABLE,
                departedAtBoundary ? 0.0D : 1.0D,
                0.0D, 0.0D, 0.0D, departedAtBoundary,
                java.util.Map.of(
                    "departed_profile", Boolean.toString(departedAtBoundary)
                )
            ));
            factors.add(new ClientWorldResolution.Factor(
                "latest_dimension",
                lastObservedVisit == null ? ClientWorldResolution.FactorAvailability.UNAVAILABLE
                    : ClientWorldResolution.FactorAvailability.AVAILABLE,
                lastDimensionMismatch ? 0.0D : 1.0D,
                0.0D, 0.0D, 0.0D, lastDimensionMismatch,
                java.util.Map.of(
                    "observed", observation.dimensionId(),
                    "candidate", lastObservedVisit == null
                        ? "unavailable" : lastObservedVisit.dimensionId()
                )
            ));
            scores.add(new CandidateScore(
                profile, score, reasons, conflicted, terrainScore, queue, independentFactors,
                continuityEvidence, factors, seedCompatible
            ));
        }
        if (scores.isEmpty()) {
            return ClientWorldResolution.ambiguous();
        }
        final List<CandidateScore> eligible = scores.stream()
            .filter(candidate -> !candidate.conflicted())
            .sorted(Comparator.comparingDouble(CandidateScore::score).reversed()
                .thenComparingInt(CandidateScore::queue))
            .toList();
        if (eligible.isEmpty()) {
            final CandidateScore first = scores.stream()
                .max(Comparator.comparingDouble(CandidateScore::score)).orElseThrow();
            return ClientWorldResolution.ambiguous(displayScores(
                scores, first, 0.0D, requiredConfidence(first.queue(), false), requiredMargin(first),
                observation.seedHash().isPresent() && profiles.size() > 1,
                List.of("candidate_conflicted"), ClientWorldResolution.CandidateOutcome.CONFLICTED
            ));
        }
        final CandidateScore best = eligible.get(0);
        final CandidateScore runnerUpCandidate = eligible.size() > 1 ? eligible.get(1) : null;
        final double runnerUp = runnerUpCandidate == null ? 0.0D : runnerUpCandidate.score();
        final boolean hasTerrain = best.hasTerrainScore();
        final boolean terrainDiscriminated = profiles.size() <= 1 || hasTerrainDiscriminator(best, scores);
        final double requiredMargin = requiredMargin(best, runnerUpCandidate);
        // Until this server has more than one confirmed profile, a familiar seed, terrain sample,
        // or server signature identifies only the one known profile, not the current upstream
        // world. Keep automatic relocking at the stricter threshold without changing any factor.
        final boolean strictSingleProfileConfidence = profiles.size() == 1;
        final boolean singleProfileFarPosition = strictSingleProfileConfidence
            && !best.hasTerrainScore()
            && (best.reasons().contains("corridor_outside_radius")
                || best.reasons().contains("position_far_without_trajectory"));
        final double requiredConfidence = requiredConfidence(best.queue(), strictSingleProfileConfidence);
        final boolean hasEnoughAuxiliary = best.independentFactors() >= 2;
        final boolean hasUnresolvedLegacyCandidate = eligible.stream()
            .anyMatch(candidate -> candidate.reasons().contains("legacy_profile"));
        final boolean higherPriorityConflict = scores.stream()
            .anyMatch(candidate -> candidate.conflicted() && candidate.queue() < best.queue());
        final List<String> bestBlockers = new ArrayList<>();
        if (forcedBlocker != null) bestBlockers.add(forcedBlocker);
        if (singleProfileFarPosition) bestBlockers.add("single_profile_far_position");
        if (best.score() < requiredConfidence) bestBlockers.add("confidence_below_threshold");
        if (best.score() - runnerUp <= requiredMargin) bestBlockers.add("margin_not_strictly_greater");
        if (!best.continuityEvidence()) bestBlockers.add("continuity_required");
        if (!hasTerrain && !hasEnoughAuxiliary) bestBlockers.add("independent_factors_insufficient");
        if (hasUnresolvedLegacyCandidate) bestBlockers.add("legacy_candidate_unresolved");
        if (higherPriorityConflict) bestBlockers.add("higher_priority_conflict");
        final boolean canAutoSelect = forcedBlocker == null && !best.conflicted()
            && best.score() >= requiredConfidence
            && best.score() - runnerUp > requiredMargin
            && best.continuityEvidence()
            && (hasTerrain || hasEnoughAuxiliary)
            && !hasUnresolvedLegacyCandidate && !higherPriorityConflict && !singleProfileFarPosition;
        final boolean sameSeedCandidates = observation.seedHash().isPresent() && profiles.size() > 1;
        if (canAutoSelect) {
            final boolean terrainConfirmed = hasTerrain
                && best.terrainScore() >= TERRAIN_MATCH_MIN_SCORE
                && terrainDiscriminated;
            final boolean stableSignalsConfirmed = profiles.size() == 1
                && hasIdentitySignalDiscriminator(best, scores, observation);
            final ClientWorldResolution.CandidateOutcome outcome = terrainConfirmed || stableSignalsConfirmed
                ? ClientWorldResolution.CandidateOutcome.AUTO_RESOLVED
                : ClientWorldResolution.CandidateOutcome.PROVISIONAL;
            final List<ClientWorldResolution.Candidate> display = displayScores(
                scores, best, runnerUp, requiredConfidence, requiredMargin,
                sameSeedCandidates, bestBlockers, outcome
            );
            final ClientWorldResolution.ConfirmationSource source = terrainConfirmed
                ? ClientWorldResolution.ConfirmationSource.TERRAIN
                : ClientWorldResolution.ConfirmationSource.STABLE_SIGNALS;
            return terrainConfirmed || stableSignalsConfirmed
                ? readOnlyAutomaticResolution
                    ? ClientWorldResolution.resolved(best.profile(), display, source)
                    : resolvedAndLearn(serverId, best.profile(), observation, display, source)
                : provisionalAndLearn(serverId, best.profile(), observation, display);
        }
        final List<ClientWorldResolution.Candidate> display = displayScores(
            scores, best, runnerUp, requiredConfidence, requiredMargin, sameSeedCandidates,
            bestBlockers, best.conflicted() ? ClientWorldResolution.CandidateOutcome.CONFLICTED
                : forcedBlocker == null ? ClientWorldResolution.CandidateOutcome.MANUAL_REQUIRED
                    : ClientWorldResolution.CandidateOutcome.BLOCKED
        );
        return ClientWorldResolution.ambiguous(display);
    }

    private static ClientWorldResolution.Factor diagnosticFactor(
        final String key,
        final boolean available,
        final double rawScore,
        final double configuredWeight,
        final double availableAuxiliaryWeight,
        final double auxiliaryScale,
        final boolean veto,
        final java.util.Map<String, String> metrics
    ) {
        final double effectiveWeight = available && availableAuxiliaryWeight > 0.0D
            ? auxiliaryScale * configuredWeight / availableAuxiliaryWeight : 0.0D;
        return new ClientWorldResolution.Factor(
            key, available ? ClientWorldResolution.FactorAvailability.AVAILABLE
                : ClientWorldResolution.FactorAvailability.UNAVAILABLE,
            rawScore, configuredWeight, effectiveWeight, effectiveWeight * rawScore, veto, metrics
        );
    }

    private static double requiredConfidence(final int queue, final boolean strictSingleProfileConfidence) {
        final double queueConfidence = queue == 3 ? QUEUE_THREE_MIN_CONFIDENCE
            : queue == 2 ? QUEUE_TWO_MIN_CONFIDENCE : AUTO_SELECT_MIN_CONFIDENCE;
        return strictSingleProfileConfidence ? Math.max(queueConfidence, SINGLE_PROFILE_MIN_CONFIDENCE)
            : queueConfidence;
    }

    private static double requiredMargin(final CandidateScore candidate) {
        return requiredMargin(candidate, null);
    }

    private static double requiredMargin(
        final CandidateScore candidate,
        final CandidateScore runnerUp
    ) {
        // A radius/queue advantage is meaningful evidence, but never an automatic qualification:
        // the winner must still clear its confidence threshold and strictly beat the runner-up.
        // Requiring the generic 10% auxiliary margin here made an exact endpoint in the Nether
        // indistinguishable from a candidate already outside the six-block band.
        // Queue 1/2 always use the published strict three-point lead. Requiring ten points merely
        // because terrain is absent suppresses the exact saved endpoint: after shared identity,
        // context and game-mode factors are normalized, an exact point can otherwise fail to beat
        // a nearby-but-distinct endpoint. Queue 3 remains deliberately much stricter below.
        final double evidenceMargin = AUTO_SELECT_ERROR_MARGIN;
        return Math.max(
            evidenceMargin,
            candidate.queue() == 3 ? QUEUE_THREE_ERROR_MARGIN : 0.0D
        );
    }

    private static java.util.Map<String, String> trajectoryMetrics(
        final ClientWorldVisit visit,
        final ClientWorldObservation observation,
        final TrajectoryEvidence evidence
    ) {
        final ClientWorldTrajectory trajectory = observation.trajectory();
        final ClientWorldTrajectorySample latest = trajectory == null ? null : trajectory.latest();
        final List<ClientWorldTrajectorySample> savedSamples = visit.trajectorySamples();
        final ClientWorldTrajectorySample savedEndpoint = savedSamples.isEmpty()
            ? null : savedSamples.get(savedSamples.size() - 1);
        final long correctionAge = latest == null
            || latest.serverAckTimeMs() == ClientWorldTrajectorySample.NO_SERVER_ACK
            ? -1L : Math.max(0L, latest.clientTimeMs() - latest.serverAckTimeMs());
        final long localEvidenceAge = savedEndpoint == null
            ? -1L : Math.max(0L, observationTime(observation) - savedEndpoint.clientTimeMs());
        return java.util.Map.ofEntries(
            java.util.Map.entry(
                "distance", evidence.corridorDistance() < 0.0D
                    ? "unavailable" : formatMetric(evidence.corridorDistance())
            ),
            java.util.Map.entry(
                "point_distance", evidence.pointDistance() < 0.0D
                    ? "unavailable" : formatMetric(evidence.pointDistance())
            ),
            java.util.Map.entry(
                "along_distance", evidence.alongDistance() < 0.0D
                    ? "unavailable" : formatMetric(evidence.alongDistance())
            ),
            java.util.Map.entry(
                "lateral_distance", evidence.lateralDistance() < 0.0D
                    ? "unavailable" : formatMetric(evidence.lateralDistance())
            ),
            java.util.Map.entry("predicted_length", formatMetric(evidence.predictedLength())),
            java.util.Map.entry("prediction_ms", Long.toString(evidence.predictionMs())),
            java.util.Map.entry(
                "centerline_confidence", formatMetric(evidence.centerlineConfidence())
            ),
            java.util.Map.entry(
                "lateral_confidence", formatMetric(evidence.lateralConfidence())
            ),
            java.util.Map.entry("freshness", formatMetric(evidence.freshness())),
            java.util.Map.entry(
                "position_correction_age_ms",
                correctionAge < 0L ? "unavailable" : Long.toString(correctionAge)
            ),
            java.util.Map.entry(
                "local_evidence_age_ms",
                localEvidenceAge < 0L ? "unavailable" : Long.toString(localEvidenceAge)
            ),
            java.util.Map.entry("saved_samples", Integer.toString(visit.trajectorySamples().size()))
        );
    }

    private static String formatMetric(final double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static List<ClientWorldResolution.Candidate> displayScores(
        final List<CandidateScore> scores,
        final CandidateScore best,
        final double runnerUp,
        final double requiredConfidence,
        final double requiredMargin,
        final boolean sameSeedCandidates,
        final List<String> bestBlockers,
        final ClientWorldResolution.CandidateOutcome bestOutcome
    ) {
        final List<ClientWorldResolution.Candidate> display = new ArrayList<>();
        for (final CandidateScore candidate : scores) {
            final boolean isBest = candidate == best;
            final List<String> blockers = new ArrayList<>();
            ClientWorldResolution.CandidateOutcome outcome = ClientWorldResolution.CandidateOutcome.MANUAL_REQUIRED;
            if (candidate.conflicted()) {
                blockers.add("candidate_conflicted");
                outcome = ClientWorldResolution.CandidateOutcome.CONFLICTED;
            } else if (isBest) {
                blockers.addAll(bestBlockers);
                outcome = bestOutcome;
            } else {
                blockers.add("lower_ranked_candidate");
            }
            final double otherBest = isBest ? runnerUp : best.score();
            display.add(candidate.display(
                requiredConfidence, otherBest, requiredMargin, sameSeedCandidates, blockers, outcome
            ));
        }
        return List.copyOf(display);
    }

    private static ClientWorldTerrainFingerprint.Match cachedTerrainMatch(
        final List<TerrainCacheEntry> cache,
        final String dimensionId,
        final ClientWorldPosition candidatePosition,
        final ClientWorldTerrainFingerprint observed,
        final ClientWorldTerrainFingerprint candidate
    ) {
        for (final TerrainCacheEntry entry : cache) {
            if (!terrainCacheCompatible(
                entry.dimensionId(), entry.candidatePosition(), entry.observed(), entry.candidate(),
                dimensionId, candidatePosition, observed, candidate
            )) {
                continue;
            }
            return entry.match();
        }
        final ClientWorldTerrainFingerprint.Match match = observed.match(candidate);
        cache.add(new TerrainCacheEntry(dimensionId, candidatePosition, observed, candidate, match));
        return match;
    }

    private static double trajectoryDistance(
        final String profileId,
        final ClientWorldVisit visit,
        final ClientWorldObservation observation
    ) {
        return trajectoryEvidence(profileId, visit, observation).corridorDistance();
    }

    /**
     * A persisted last-stable pointer is a continuity prior, not a license to contradict a fresh
     * local position. Keep it as a tie-breaker while candidates share the same base corridor, but
     * remove it from normalization when another candidate is inside the current corridor and the
     * old stable candidate is outside it. This prevents a stale proxy/default-backend choice from
     * reversing clear current trajectory evidence after an ordinary reconnect.
     */
    private static boolean strongerCurrentTrajectoryExists(
        final List<ClientWorldProfile> profiles,
        final String lastStableProfileId,
        final ClientWorldObservation observation
    ) {
        if (observation.position() == null || observation.dimensionId() == null) {
            return false;
        }
        final ClientWorldProfile stableProfile = profiles.stream()
            .filter(profile -> profile.id().equals(lastStableProfileId))
            .findFirst().orElse(null);
        if (stableProfile == null) {
            return false;
        }
        final ClientWorldVisit stableVisit = stableProfile.lastObservedVisit(observation.dimensionId());
        if (stableVisit == null) {
            return false;
        }
        final double radius = positionRadius(observation.dimensionId());
        final double stableDistance = trajectoryDistance(
            stableProfile.id(), stableVisit, observation
        );
        if (stableDistance < 0.0D || stableDistance <= radius) {
            return false;
        }
        return profiles.stream()
            .filter(profile -> !profile.id().equals(lastStableProfileId))
            .map(profile -> new java.util.AbstractMap.SimpleImmutableEntry<>(
                profile.id(), profile.lastObservedVisit(observation.dimensionId())
            ))
            .filter(entry -> entry.getValue() != null)
            .mapToDouble(entry -> trajectoryDistance(
                entry.getKey(), entry.getValue(), observation
            ))
            .anyMatch(distance -> distance >= 0.0D && distance <= radius);
    }

    /**
     * A provisional profile may leave a dimension before its first confirmed visit is allowed to
     * reach the registry. Its profile-owned local checkpoint is still safe ranking evidence: it
     * cannot be attributed to another profile and it remains non-persistent until confirmation.
     */
    private static ClientWorldVisit transientVisitFromCandidateTrajectory(
        final String profileId,
        final ClientWorldObservation observation
    ) {
        if (observation.dimensionId() == null) {
            return null;
        }
        final ClientWorldTrajectory candidate = observation.candidateTrajectoryFor(profileId);
        final ClientWorldTrajectorySample endpoint = candidate == null ? null : candidate.latest();
        if (endpoint == null || !observation.dimensionId().equals(endpoint.dimensionId())) {
            return null;
        }
        final ClientWorldVisit visit = new ClientWorldVisit(
            observation.dimensionId(), null,
            new ClientWorldPosition(
                (int) Math.floor(endpoint.x()),
                (int) Math.floor(endpoint.y()),
                (int) Math.floor(endpoint.z())
            ),
            endpoint.clientTimeMs(), null, java.util.Map.of()
        );
        visit.rememberTrajectory(candidate);
        return visit;
    }

    private static TrajectoryEvidence trajectoryEvidence(
        final String profileId,
        final ClientWorldVisit visit,
        final ClientWorldObservation observation
    ) {
        if (observation.position() == null || observation.dimensionId() == null) {
            return TrajectoryEvidence.unavailable();
        }
        final long now = observationTime(observation);
        final ClientWorldTrajectory candidateOverride = observation.candidateTrajectoryFor(profileId);
        final ClientWorldTrajectory trajectory = usableTrajectory(profileId, visit, observation);
        if (trajectory == null) {
            final ClientWorldTrajectorySample overrideEndpoint = candidateOverride == null
                ? null : candidateOverride.latest();
            final boolean hasOverrideEndpoint = overrideEndpoint != null
                && overrideEndpoint.dimensionId().equals(observation.dimensionId());
            if (!hasOverrideEndpoint && visit.lastPosition() == null) {
                return TrajectoryEvidence.unavailable();
            }
            final double pointDistance = hasOverrideEndpoint
                ? overrideEndpoint.spatialDistanceTo(observation.position())
                : observation.position().spatialDistanceTo(visit.lastPosition());
            final double pointConfidence = positionConfidence(pointDistance);
            return new TrajectoryEvidence(
                pointConfidence, pointDistance, pointDistance, 0.0D, pointDistance,
                0.0D, 0L, 1.0D, pointConfidence, 1.0D
            );
        }
        final ClientWorldTrajectory current = observation.trajectory();
        final ClientWorldTrajectory.CausalCorridor corridor = current != null && current.latest() != null
            ? trajectory.causalCorridorTo(current, PREDICTION_HORIZON_MS)
            : trajectory.causalCorridorTo(
                observation.position(), now, observation.dimensionId(), PREDICTION_HORIZON_MS
            );
        if (!corridor.available()) {
            return TrajectoryEvidence.unavailable();
        }
        // The user's 0..1024 decay is evaluated twice. Confidence on the predicted centerline
        // falls as it travels away from the saved endpoint; lateral confidence is then multiplied
        // by that local centerline value, never restarted from 100% beside a distant line point.
        final ClientWorldTrajectorySample candidateEndpoint = trajectory.latest();
        final ClientWorldTrajectorySample currentEndpoint = current == null ? null : current.latest();
        final double currentY = currentEndpoint == null
            ? observation.position().y() : currentEndpoint.y();
        final double verticalDistance = candidateEndpoint == null
            ? 0.0D : Math.abs(currentY - candidateEndpoint.y());
        final double spatialPointDistance = Math.hypot(corridor.pointDistance(), verticalDistance);
        final double spatialLateralDistance = Math.hypot(corridor.lateralDistance(), verticalDistance);
        final double centerlineConfidence = positionConfidence(corridor.alongDistance());
        final double lateralConfidence = positionConfidence(spatialLateralDistance);
        // Freshness belongs to the candidate endpoint. Using the current connection here makes
        // the value almost permanently 100% and lets an old departure velocity masquerade as a
        // recent causal prediction.
        final double freshness = confirmationConfidence(trajectory, now);
        final double confidence = centerlineConfidence * lateralConfidence * freshness;
        return new TrajectoryEvidence(
            confidence, spatialLateralDistance, spatialPointDistance,
            corridor.alongDistance(), spatialLateralDistance, corridor.predictedLength(),
            corridor.predictionMs(), centerlineConfidence, lateralConfidence
            , freshness
        );
    }

    static double corridorPositionConfidence(final double distance, final double radius) {
        if (distance >= POSITION_CONFIDENCE_CUTOFF_DISTANCE) {
            return 0.0D;
        }
        // Radius selects the queue only. Confidence follows the documented absolute-distance
        // curve from 0 to 1024, so 0 blocks and 48 blocks are no longer both treated as perfect.
        return positionConfidence(Math.max(0.0D, distance));
    }

    private static ClientWorldTrajectory usableTrajectory(
        final String profileId,
        final ClientWorldVisit visit,
        final ClientWorldObservation observation
    ) {
        final ClientWorldTrajectory candidateOverride = observation.candidateTrajectoryFor(profileId);
        if (candidateOverride != null && candidateOverride.latest() != null
            && candidateOverride.latest().dimensionId().equals(observation.dimensionId())
            && candidateOverride.hasUsableContinuity(
                observation.dimensionId(), observationTime(observation), MAX_TRAJECTORY_AGE_MS
            )) {
            return candidateOverride;
        }
        if (!visit.trajectorySamples().isEmpty()) {
            final ClientWorldTrajectory trajectory = ClientWorldTrajectory.fromHistoricalSamples(
                visit.trajectorySamples(), ClientWorldTrajectory.DEFAULT_CAPACITY
            );
            if (trajectory.latest() != null
                && trajectory.latest().dimensionId().equals(observation.dimensionId())
                && trajectory.hasUsableContinuity(
                    observation.dimensionId(), observationTime(observation), MAX_TRAJECTORY_AGE_MS
                )) {
                return trajectory;
            }
        }
        return null;
    }

    static double confirmationConfidence(
        final ClientWorldTrajectory trajectory,
        final long now
    ) {
        final ClientWorldTrajectorySample latest = trajectory.latest();
        final long localEvidenceAge = latest == null
            ? Long.MAX_VALUE : Math.max(0L, now - latest.clientTimeMs());
        if (localEvidenceAge <= PREDICTION_HORIZON_MS) {
            return 1.0D;
        }
        return Math.max(0.50D, Math.exp(-(double) (localEvidenceAge - PREDICTION_HORIZON_MS)
            / MAX_TRAJECTORY_AGE_MS));
    }

    private static long observationTime(final ClientWorldObservation observation) {
        final ClientWorldTrajectory current = observation.trajectory();
        return current != null && current.latest() != null
            ? current.latest().clientTimeMs() : System.currentTimeMillis();
    }

    private static int identitySignalMatches(
        final ClientWorldProfile profile,
        final ClientWorldObservation observation
    ) {
        return profile.matchingIdentitySignalCount(observation);
    }

    static boolean terrainCacheCompatible(
        final String cachedDimensionId,
        final ClientWorldPosition cachedCandidatePosition,
        final ClientWorldTerrainFingerprint cachedObserved,
        final ClientWorldTerrainFingerprint cachedCandidate,
        final String dimensionId,
        final ClientWorldPosition candidatePosition,
        final ClientWorldTerrainFingerprint observed,
        final ClientWorldTerrainFingerprint candidate
    ) {
        return Objects.equals(cachedDimensionId, dimensionId)
            && cachedCandidatePosition != null && candidatePosition != null
            && cachedCandidatePosition.horizontalDistanceTo(candidatePosition) <= 1.5D
            && cachedObserved.sameEvidence(observed)
            && cachedCandidate.sameEvidence(candidate);
    }

    private static boolean hasTerrainScore(final double score) {
        return !Double.isNaN(score);
    }

    static double positionRadius(final String dimensionId) {
        return "minecraft_the_nether".equals(dimensionId)
            ? NETHER_POSITION_RADIUS : OVERWORLD_POSITION_RADIUS;
    }

    static double positionConfidence(final double distance) {
        if (distance <= 0.0D) {
            return 1.0D;
        }
        if (distance >= POSITION_CONFIDENCE_CUTOFF_DISTANCE) {
            return 0.0D;
        }
        // A steep exponential keeps nearby visits useful while rapidly rejecting stale positions.
        final double boundary = Math.exp(-4.0D);
        return (Math.exp(-4.0D * distance / POSITION_CONFIDENCE_CUTOFF_DISTANCE) - boundary)
            / (1.0D - boundary);
    }

    /**
     * Same-seed proxy children often share all server metadata and can reopen at the same
     * coordinate. Position and game mode only rank those candidates; they cannot establish which
     * child is active. Require a complete terrain sample that clearly rules out every other
     * compatible profile before automatically opening a map namespace.
     */
    private static boolean hasTerrainDiscriminator(
        final CandidateScore best,
        final List<CandidateScore> scores
    ) {
        int compatibleProfiles = 0;
        for (final CandidateScore candidate : scores) {
            if (candidate.conflicted()) {
                continue;
            }
            compatibleProfiles++;
            if (candidate == best) {
                continue;
            }
            if (!best.hasTerrainScore() || !candidate.hasTerrainScore()
                || best.terrainScore() < TERRAIN_MATCH_MIN_SCORE
                || best.terrainScore() - candidate.terrainScore() < TERRAIN_DISCRIMINATOR_MIN_GAP) {
                return false;
            }
        }
        return compatibleProfiles < 2 || best.hasTerrainScore()
            && best.terrainScore() >= TERRAIN_MATCH_MIN_SCORE;
    }

    private static List<ClientWorldResolution.Candidate> displayInsufficientObservation(
        final List<ClientWorldProfile> candidates,
        final ClientWorldObservation observation
    ) {
        return displayInsufficientObservation(candidates, observation, null);
    }

    private static List<ClientWorldResolution.Candidate> displayInsufficientObservation(
        final List<ClientWorldProfile> candidates,
        final ClientWorldObservation observation,
        final String forcedBlocker
    ) {
        final String reason = observation.dimensionId() == null
            ? "dimension_unavailable" : "observation_incomplete";
        return candidates.stream()
            .map(profile -> new ClientWorldResolution.Candidate(
                profile.id(), false, 0, 3, 80, 0, 15, 0, 0,
                observation.seedHash().isEmpty()
                    || profile.matchesSeed(observation.seedHash().getAsLong()),
                observation.seedHash().isPresent() && candidates.size() > 1,
                ClientWorldResolution.CandidateOutcome.UNSCORED,
                List.of(), List.of(reason),
                forcedBlocker == null ? List.of(reason) : List.of(reason, forcedBlocker),
                profile.hasSignalConflict(observation)
            ))
            .toList();
    }

    private ClientWorldResolution resolvedAndLearn(
        final String serverId,
        final ClientWorldProfile profile,
        final ClientWorldObservation observation,
        final List<ClientWorldResolution.Candidate> candidates,
        final ClientWorldResolution.ConfirmationSource source
    ) {
        return resolvedAndLearn(serverId, profile, List.of(), observation, candidates, source);
    }

    private ClientWorldResolution resolvedAndLearn(
        final String serverId,
        final ClientWorldProfile profile,
        final List<ClientWorldObservation> ownedHistory,
        final ClientWorldObservation observation,
        final List<ClientWorldResolution.Candidate> candidates,
        final ClientWorldResolution.ConfirmationSource source
    ) {
        final Mutation<String> mutation = mutate(copy -> {
            final ClientWorldProfile candidate = requireProfile(copy.mutableProfiles(serverId), profile.id());
            rememberOwnedHistory(candidate, ownedHistory);
            // Historical candidate probes are not stable visit evidence. Persist only the
            // current observation, whose terrain center (if any) is player-centered.
            candidate.bind(observation, policy().maxBindingsPerProfile());
            rememberLastStable(copy, serverId, candidate.id(), observation);
            return candidate.id();
        });
        return resolvedMutation(serverId, mutation, candidates, source);
    }

    private static void rememberOwnedHistory(
        final ClientWorldProfile profile,
        final List<ClientWorldObservation> ownedHistory
    ) {
        if (ownedHistory == null) {
            return;
        }
        for (final ClientWorldObservation historical : ownedHistory) {
            if (historical != null && historical.dimensionId() != null) {
                profile.rememberVisit(historical);
            }
        }
    }

    private ClientWorldResolution provisionalAndLearn(
        final String serverId,
        final ClientWorldProfile profile,
        final ClientWorldObservation observation,
        final List<ClientWorldResolution.Candidate> candidates
    ) {
        // Provisional recovery must not persist the current position, trajectory, terrain, or
        // bindings until later evidence validates that this is still the same logical world.
        return ClientWorldResolution.provisional(requireProfile(serverId, profile.id()), candidates);
    }

    private ClientWorldResolution create(
        final String serverId,
        final String storageId,
        final ClientWorldObservation observation
    ) {
        final Mutation<String> mutation = mutate(copy -> {
            final ClientWorldProfile profile = create(copy.mutableProfiles(serverId), storageId, observation);
            rememberLastStable(copy, serverId, profile.id(), observation);
            return profile.id();
        });
        return resolvedMutation(serverId, mutation);
    }

    private ClientWorldProfile create(
        final List<ClientWorldProfile> profiles,
        final String storageId,
        final ClientWorldObservation observation
    ) {
        final ClientWorldProfile profile = new ClientWorldProfile(
            ids.get().toString(), storageId, "World " + (profiles.size() + 1)
        );
        profile.bind(observation, policy().maxBindingsPerProfile());
        profiles.add(profile);
        return profile;
    }

    private String nextStorageId() {
        return "client-" + ids.get();
    }

    private static boolean hasIdentitySignalDiscriminator(
        final CandidateScore best,
        final List<CandidateScore> scores,
        final ClientWorldObservation observation
    ) {
        final int bestMatches = identitySignalMatches(best.profile(), observation);
        if (bestMatches < 2) {
            return false;
        }
        return scores.stream()
            .filter(candidate -> candidate != best && !candidate.conflicted())
            .allMatch(candidate -> identitySignalMatches(candidate.profile(), observation) < bestMatches);
    }

    private static void rememberLastStable(
        final ClientWorldProfileRegistry registry,
        final String serverId,
        final String profileId,
        final ClientWorldObservation observation
    ) {
        final ClientWorldTrajectory trajectory = observation.trajectory();
        final ClientWorldTrajectorySample latest = trajectory == null ? null : trajectory.latest();
        registry.setLastStableProfile(
            serverId, profileId, System.currentTimeMillis(),
            latest == null ? 0L : latest.connectionGeneration(), observation
        );
    }

    private ClientWorldPolicy policy() {
        return Objects.requireNonNull(policy.get(), "client world policy");
    }

    private ClientWorldProfile requireProfile(final String serverId, final String profileId) {
        return requireProfile(registry.mutableProfiles(serverId), profileId);
    }

    private ClientWorldProfile requireProfile(
        final List<ClientWorldProfile> profiles,
        final String profileId
    ) {
        return profiles.stream()
            .filter(profile -> profile.id().equals(profileId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown client world profile " + profileId));
    }

    private ClientWorldResolution resolvedMutation(final String serverId, final Mutation<String> mutation) {
        return resolvedMutation(
            serverId, mutation, List.of(), ClientWorldResolution.ConfirmationSource.AUTOMATIC
        );
    }

    private ClientWorldResolution resolvedMutation(
        final String serverId,
        final Mutation<String> mutation,
        final List<ClientWorldResolution.Candidate> candidates,
        final ClientWorldResolution.ConfirmationSource source
    ) {
        return mutation.applied()
            ? ClientWorldResolution.resolved(
                requireProfile(registry.mutableProfiles(serverId), mutation.value()), candidates, source
            )
            : ClientWorldResolution.persistenceFailed(mutation.result().error(), candidates);
    }

    private <T> Mutation<T> mutate(final Function<ClientWorldProfileRegistry, T> change) {
        synchronized (persistenceLock) {
            if (!registry.available()) {
                return new Mutation<>(null, MutationResult.failure(registry.loadFailure()));
            }
            final ClientWorldProfileRegistry candidate = registry.copy();
            final T value;
            final ClientWorldProfileIo.SaveResult persisted;
            try {
                value = change.apply(candidate);
                persisted = persistence.save(candidate);
            } catch (final RuntimeException error) {
                return new Mutation<>(null, MutationResult.failure(errorMessage(error)));
            }
            if (persisted == null || !persisted.saved()) {
                return new Mutation<>(null, MutationResult.failure(persisted == null ? null : persisted.error()));
            }
            registry.replaceWith(candidate);
            registryGeneration++;
            return new Mutation<>(value, MutationResult.success());
        }
    }

    private static String errorMessage(final RuntimeException error) {
        final String message = error.getMessage();
        return message == null || message.isBlank()
            ? error.getClass().getSimpleName()
            : message;
    }

    private record Mutation<T>(T value, MutationResult result) {
        boolean applied() {
            return result.applied();
        }
    }

    private record CandidateScore(
        ClientWorldProfile profile,
        double score,
        List<String> reasons,
        boolean conflicted,
        double terrainScore,
        int queue,
        int independentFactors,
        boolean continuityEvidence,
        List<ClientWorldResolution.Factor> factors,
        boolean seedCompatible
    ) {
        private CandidateScore {
            reasons = List.copyOf(reasons);
            factors = List.copyOf(factors);
        }

        static CandidateScore legacy(
            final ClientWorldProfile profile,
            final boolean conflicted,
            final boolean seedCompatible
        ) {
            return new CandidateScore(
                profile, 0.0D, List.of("legacy_profile"), conflicted, Double.NaN, 3, 0, false
                , List.of(), seedCompatible
            );
        }

        static CandidateScore dimensionUnavailable(
            final ClientWorldProfile profile,
            final boolean conflicted,
            final boolean seedCompatible
        ) {
            return new CandidateScore(
                profile, 0.0D, List.of("dimension_unavailable"), conflicted, Double.NaN,
                3, 0, false, List.of(), seedCompatible
            );
        }

        static CandidateScore dimensionMismatch(
            final ClientWorldProfile profile,
            final boolean seedCompatible
        ) {
            return new CandidateScore(
                profile, 0.0D, List.of("last_dimension_mismatch"), true, Double.NaN,
                3, 0, false,
                List.of(new ClientWorldResolution.Factor(
                    "latest_dimension", ClientWorldResolution.FactorAvailability.AVAILABLE,
                    0.0D, 0.0D, 0.0D, 0.0D, true, java.util.Map.of()
                )), seedCompatible
            );
        }

        boolean hasTerrainScore() {
            return !Double.isNaN(terrainScore);
        }

        ClientWorldResolution.Candidate display(
            final double requiredConfidence,
            final double runnerUp,
            final double requiredMargin,
            final boolean sameSeedCandidates,
            final List<String> blockers,
            final ClientWorldResolution.CandidateOutcome outcome
        ) {
            return new ClientWorldResolution.Candidate(
                profile.id(), !reasons.contains("legacy_profile")
                    && !reasons.contains("dimension_unavailable"),
                (int) Math.round(score * 100.0D),
                queue, (int) Math.round(requiredConfidence * 100.0D),
                (int) Math.round(runnerUp * 100.0D), (int) Math.round(requiredMargin * 100.0D),
                (int) Math.round((score - runnerUp) * 100.0D), independentFactors,
                seedCompatible, sameSeedCandidates, outcome, factors, reasons, blockers, conflicted
            );
        }
    }

    private record TerrainCacheEntry(
        String dimensionId,
        ClientWorldPosition candidatePosition,
        ClientWorldTerrainFingerprint observed,
        ClientWorldTerrainFingerprint candidate,
        ClientWorldTerrainFingerprint.Match match
    ) { }

    private record TrajectoryEvidence(
        double confidence,
        double corridorDistance,
        double pointDistance,
        double alongDistance,
        double lateralDistance,
        double predictedLength,
        long predictionMs,
        double centerlineConfidence,
        double lateralConfidence,
        double freshness
    ) {
        static TrajectoryEvidence unavailable() {
            return new TrajectoryEvidence(
                -1.0D, -1.0D, -1.0D, -1.0D, -1.0D, 0.0D, 0L, 0.0D, 0.0D, 0.0D
            );
        }
    }
}
