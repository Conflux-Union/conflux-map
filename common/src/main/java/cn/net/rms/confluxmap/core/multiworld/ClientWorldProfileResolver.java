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
    /** Queue 1/2 candidates must lead the runner-up by a strict eight percentage points. */
    private static final double AUTO_SELECT_ERROR_MARGIN = 0.08D;
    private static final double QUEUE_TWO_MIN_CONFIDENCE = 0.70D;
    private static final double QUEUE_THREE_MIN_CONFIDENCE = 0.80D;
    private static final double QUEUE_THREE_ERROR_MARGIN = 0.15D;
    private static final double AUXILIARY_WEIGHT = 0.75D;
    private static final double TERRAIN_WEIGHT = 0.25D;
    private static final double POSITION_CORRIDOR_WEIGHT = 0.60D;
    private static final double TRAJECTORY_WEIGHT = 0.15D;
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
            return resolveCandidates(serverId, seedMatches, observation);
        }

        return resolveCandidates(serverId, profiles, observation);
    }

    /**
     * Rechecks one already selected provisional profile without allowing another profile to win.
     * This is intentionally narrower than {@link #resolve(String, ClientWorldObservation)}: later
     * terrain can confirm or reject the session lock, but ordinary movement cannot re-elect A/B.
     */
    public ClientWorldResolution validateLockedProfile(
        final String serverId,
        final String profileId,
        final ClientWorldObservation observation
    ) {
        if (!registry.available()) {
            return ClientWorldResolution.persistenceFailed(registry.loadFailure());
        }
        final ClientWorldProfile profile = registry.profiles(serverId).stream()
            .filter(candidate -> candidate.id().equals(profileId))
            .findFirst().orElse(null);
        return profile == null
            ? ClientWorldResolution.ambiguous()
            : scoreCandidates(serverId, List.of(profile), observation, null);
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
        if (!registry.available()) {
            return ClientWorldResolution.persistenceFailed(registry.loadFailure());
        }
        if (previousSeedHash.isPresent() && observation.seedHash().isPresent()
            && previousSeedHash.getAsLong() == observation.seedHash().getAsLong()) {
            return diagnoseBlocked(serverId, observation, "same_seed_proxy_transition");
        }
        if (observation.seedHash().isPresent()) {
            final long seedHash = observation.seedHash().getAsLong();
            final long matchingProfiles = registry.mutableProfiles(serverId).stream()
                .filter(profile -> profile.matchesSeed(seedHash))
                .limit(2)
                .count();
            if (matchingProfiles > 1) {
                return diagnoseBlocked(serverId, observation, "same_seed_requires_discriminator");
            }
        }
        return resolve(serverId, observation);
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
            return resolvedAndLearn(exact.get(0), observation);
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
            legacy.rename(normalized);
            legacy.bindVelocityServer(normalized);
            legacy.bind(observation);
            onChange.run();
            return ClientWorldResolution.resolved(legacy);
        }
        final String storageId = profiles.isEmpty() ? "world" : nextStorageId();
        final UUID id = ids.get();
        final ClientWorldProfile profile = new ClientWorldProfile(
            id.toString(), storageId, normalized
        );
        profile.bind(observation);
        profile.bindVelocityServer(normalized);
        profiles.add(profile);
        onChange.run();
        return ClientWorldResolution.resolved(profile);
    }

    public ClientWorldResolution select(
        final String serverId,
        final String profileId,
        final ClientWorldObservation observation
    ) {
        final Mutation<String> mutation = mutate(copy -> {
            final List<ClientWorldProfile> profiles = copy.mutableProfiles(serverId);
            final ClientWorldProfile profile = requireProfile(profiles, profileId);
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
        final String normalized = ClientWorldCommand.normalizeConfigured(command);
        final List<ClientWorldProfile> profiles = registry.mutableProfiles(serverId);
        final ClientWorldProfile target = requireProfile(profiles, profileId);
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
        final String normalized = ClientWorldCommand.normalizeConfigured(command);
        if (!requireProfile(serverId, profileId).hasSwitchCommand(normalized)) {
            return MutationResult.success();
        }
        return mutate(copy -> {
            requireProfile(copy.mutableProfiles(serverId), profileId).removeSwitchCommand(normalized);
            return profileId;
        }).result();
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
        if (registry.available()) {
            return MutationResult.success();
        }
        if (!restored.available()) {
            return MutationResult.failure(restored.loadFailure());
        }
        registry.replaceWith(restored);
        return MutationResult.success();
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

    /** Removes only registry metadata. Callers must relocate the profile's local data first. */
    public MutationResult delete(final String serverId, final String profileId) {
        requireProfile(serverId, profileId);
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

    /** Persists an isolated registry copy. Returning failure prevents publication in memory. */
    @FunctionalInterface
    public interface Persistence {
        ClientWorldProfileIo.SaveResult save(ClientWorldProfileRegistry registry);
    }

    private ClientWorldResolution resolveCandidates(
        final String serverId,
        final List<ClientWorldProfile> candidates,
        final ClientWorldObservation observation
    ) {
        final ClientWorldResolution scored = scoreCandidates(serverId, candidates, observation);
        return scored != null
            ? scored
            : ClientWorldResolution.ambiguous(displayInsufficientObservation(candidates, observation));
    }

    /** Scores continuity first, then uses terrain as optional validation evidence. */
    private ClientWorldResolution scoreCandidates(
        final String serverId,
        final List<ClientWorldProfile> profiles,
        final ClientWorldObservation observation
    ) {
        return scoreCandidates(serverId, profiles, observation, null);
    }

    /** Runs normal diagnostics while a service-level safety rule still forbids auto selection. */
    public ClientWorldResolution diagnoseBlocked(
        final String serverId,
        final ClientWorldObservation observation,
        final String blocker
    ) {
        final List<ClientWorldProfile> all = registry.mutableProfiles(serverId);
        final List<ClientWorldProfile> candidates = observation.seedHash().isPresent()
            ? all.stream().filter(profile -> profile.matchesSeed(observation.seedHash().getAsLong())).toList()
            : List.copyOf(all);
        final List<ClientWorldProfile> visible = candidates.isEmpty() ? List.copyOf(all) : candidates;
        final ClientWorldResolution result = scoreCandidates(serverId, visible, observation, blocker);
        return result == null
            ? ClientWorldResolution.ambiguous(displayInsufficientObservation(visible, observation, blocker))
            : result;
    }

    private ClientWorldResolution scoreCandidates(
        final String serverId,
        final List<ClientWorldProfile> profiles,
        final ClientWorldObservation observation,
        final String forcedBlocker
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
        for (final ClientWorldProfile profile : profiles) {
            final ClientWorldVisit visit = profile.visit(observation.dimensionId());
            final boolean seedCompatible = observation.seedHash().isEmpty()
                || profile.matchesSeed(observation.seedHash().getAsLong());
            if (visit == null) {
                scores.add(CandidateScore.legacy(
                    profile, !seedCompatible || profile.hasSignalConflict(observation), seedCompatible
                ));
                continue;
            }
            final List<String> reasons = new ArrayList<>();
            final List<ClientWorldResolution.Factor> factors = new ArrayList<>();
            double terrainScore = Double.NaN;
            int terrainComparableChunks = 0;
            final ClientWorldVisit.ContextMatch context = visit.contextMatch(observation.signals());
            if (observation.seedHash().isPresent()) {
                reasons.add(seedCompatible ? "seed_match" : "seed_conflict");
            }
            double auxiliary = 0.0D;
            double availableAuxiliaryWeight = 0.0D;
            int independentFactors = 0;
            final double trajectoryScore = trajectoryConfidence(visit, observation);
            final double trajectoryFreshness = trajectoryFreshnessConfidence(visit, observation);
            final boolean continuityEvidence = trajectoryScore > 0.0D;
            if (trajectoryScore >= 0.0D) {
                auxiliary += POSITION_CORRIDOR_WEIGHT * trajectoryScore;
                availableAuxiliaryWeight += POSITION_CORRIDOR_WEIGHT;
                if (trajectoryScore > 0.0D) {
                    independentFactors++;
                }
                reasons.add(trajectoryScore > 0.0D ? "position_corridor" : "position_corridor_stale");
            }
            if (trajectoryFreshness >= 0.0D) {
                auxiliary += TRAJECTORY_WEIGHT * trajectoryFreshness;
                availableAuxiliaryWeight += TRAJECTORY_WEIGHT;
                reasons.add(trajectoryFreshness > 0.0D ? "trajectory_fresh" : "trajectory_stale");
            }
            if (lastStableProfileId != null) {
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
            final double continuityDistance = trajectoryDistance(visit, observation);
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
            } else if (observation.position() != null && visit.lastPosition() != null) {
                final double distance = observation.position().horizontalDistanceTo(visit.lastPosition());
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
                && lastStable != null && lastStable.conflicts(observation);
            final boolean conflicted = !seedCompatible || profile.hasSignalConflict(observation)
                || context.hasStableConflict() || terrainConflict || stablePointerConflict;
            if (conflicted) {
                reasons.add(terrainConflict ? "terrain_conflict"
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
                "trajectory", trajectoryFreshness >= 0.0D,
                Math.max(0.0D, trajectoryFreshness), TRAJECTORY_WEIGHT,
                availableAuxiliaryWeight, auxiliaryScale, false,
                trajectoryMetrics(visit, observation, continuityDistance)
            ));
            factors.add(diagnosticFactor(
                "last_stable", lastStableProfileId != null,
                profile.id().equals(lastStableProfileId) && !stablePointerConflict ? 1.0D : 0.0D,
                LAST_STABLE_WEIGHT, availableAuxiliaryWeight, auxiliaryScale, stablePointerConflict,
                java.util.Map.of("hit", Boolean.toString(profile.id().equals(lastStableProfileId)))
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
            factors.add(diagnosticFactor(
                "position_corridor", trajectoryScore >= 0.0D, Math.max(0.0D, trajectoryScore),
                POSITION_CORRIDOR_WEIGHT, availableAuxiliaryWeight, auxiliaryScale, false,
                corridorMetrics(visit, observation)
            ));
            factors.add(new ClientWorldResolution.Factor(
                "seed_filter",
                observation.seedHash().isPresent() ? ClientWorldResolution.FactorAvailability.AVAILABLE
                    : ClientWorldResolution.FactorAvailability.UNAVAILABLE,
                observation.seedHash().isPresent() ? 1.0D : 0.0D,
                0.0D, 0.0D, 0.0D, !seedCompatible,
                java.util.Map.of("compatible", Boolean.toString(seedCompatible))
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
                scores, first, 0.0D, requiredConfidence(first.queue()), requiredMargin(first),
                observation.seedHash().isPresent() && profiles.size() > 1,
                List.of("candidate_conflicted"), ClientWorldResolution.CandidateOutcome.CONFLICTED
            ));
        }
        final CandidateScore best = eligible.get(0);
        final boolean hasRunnerUp = eligible.size() > 1;
        final double runnerUp = hasRunnerUp ? eligible.get(1).score() : 0.0D;
        final boolean hasTerrain = best.hasTerrainScore();
        final double requiredMargin = requiredMargin(best);
        final double requiredConfidence = requiredConfidence(best.queue());
        final boolean hasEnoughAuxiliary = best.independentFactors() >= 2;
        final boolean hasUnresolvedLegacyCandidate = eligible.stream()
            .anyMatch(candidate -> candidate.reasons().contains("legacy_profile"));
        final boolean higherPriorityConflict = scores.stream()
            .anyMatch(candidate -> candidate.conflicted() && candidate.queue() < best.queue());
        final List<String> bestBlockers = new ArrayList<>();
        if (forcedBlocker != null) bestBlockers.add(forcedBlocker);
        if (best.score() < requiredConfidence) bestBlockers.add("confidence_below_threshold");
        if (hasRunnerUp && best.score() - runnerUp <= requiredMargin) {
            bestBlockers.add("margin_not_strictly_greater");
        }
        if (!best.continuityEvidence()) bestBlockers.add("continuity_required");
        if (!hasTerrain && !hasEnoughAuxiliary) bestBlockers.add("independent_factors_insufficient");
        if (hasUnresolvedLegacyCandidate) bestBlockers.add("legacy_candidate_unresolved");
        if (higherPriorityConflict) bestBlockers.add("higher_priority_conflict");
        final boolean canAutoSelect = forcedBlocker == null && !best.conflicted()
            && best.score() >= requiredConfidence
            && (!hasRunnerUp || best.score() - runnerUp > requiredMargin)
            && best.continuityEvidence()
            && (hasTerrain || hasEnoughAuxiliary)
            && !hasUnresolvedLegacyCandidate && !higherPriorityConflict;
        final boolean sameSeedCandidates = observation.seedHash().isPresent() && profiles.size() > 1;
        if (canAutoSelect) {
            final boolean terrainConfirmed = hasTerrain
                && best.terrainScore() >= TERRAIN_MATCH_MIN_SCORE
                && (!observation.seedHash().isPresent() || profiles.size() == 1
                    || hasTerrainDiscriminator(best, scores));
            final boolean stableSignalsConfirmed = profiles.size() == 1
                && hasIdentitySignalDiscriminator(best, scores, observation);
            final ClientWorldResolution.CandidateOutcome outcome = terrainConfirmed || stableSignalsConfirmed
                ? ClientWorldResolution.CandidateOutcome.AUTO_RESOLVED
                : ClientWorldResolution.CandidateOutcome.PROVISIONAL;
            final List<ClientWorldResolution.Candidate> display = displayScores(
                scores, best, runnerUp, requiredConfidence, requiredMargin,
                sameSeedCandidates, bestBlockers, outcome
            );
            return terrainConfirmed || stableSignalsConfirmed
                ? resolvedAndLearn(
                    serverId, best.profile(), observation, display,
                    terrainConfirmed ? ClientWorldResolution.ConfirmationSource.TERRAIN
                        : ClientWorldResolution.ConfirmationSource.STABLE_SIGNALS
                )
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

    private static double requiredConfidence(final int queue) {
        return queue == 3 ? QUEUE_THREE_MIN_CONFIDENCE
            : queue == 2 ? QUEUE_TWO_MIN_CONFIDENCE : AUTO_SELECT_MIN_CONFIDENCE;
    }

    private static double requiredMargin(final CandidateScore candidate) {
        return candidate.queue() == 3 ? QUEUE_THREE_ERROR_MARGIN : AUTO_SELECT_ERROR_MARGIN;
    }

    private static java.util.Map<String, String> trajectoryMetrics(
        final ClientWorldVisit visit,
        final ClientWorldObservation observation,
        final double distance
    ) {
        final ClientWorldTrajectory trajectory = observation.trajectory();
        final ClientWorldTrajectorySample latest = trajectory == null ? null : trajectory.latest();
        final long ackAge = latest == null || latest.serverAckTimeMs() == ClientWorldTrajectorySample.NO_SERVER_ACK
            ? -1L : Math.max(0L, latest.clientTimeMs() - latest.serverAckTimeMs());
        return java.util.Map.of(
            "distance", distance < 0.0D ? "unavailable" : formatMetric(distance),
            "ack_age_ms", ackAge < 0L ? "unavailable" : Long.toString(ackAge),
            "saved_samples", Integer.toString(visit.trajectorySamples().size())
        );
    }

    private static java.util.Map<String, String> corridorMetrics(
        final ClientWorldVisit visit,
        final ClientWorldObservation observation
    ) {
        final ClientWorldTrajectory.CausalCorridor corridor = causalCorridor(visit, observation);
        if (Double.isInfinite(corridor.endpointDistance())) {
            return java.util.Map.of("endpoint_distance", "unavailable");
        }
        return java.util.Map.of(
            "endpoint_distance_3d", formatMetric(corridor.endpointDistance()),
            "along_distance", formatMetric(corridor.alongDistance()),
            "lateral_distance_3d", formatMetric(corridor.lateralDistance()),
            "predicted_length", formatMetric(corridor.predictedLength()),
            "centerline_confidence", formatMetric(positionConfidence(corridor.alongDistance())),
            "lateral_confidence", formatMetric(positionConfidence(corridor.lateralDistance())),
            "elapsed_ms", Long.toString(corridor.elapsedMs())
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
        final ClientWorldVisit visit,
        final ClientWorldObservation observation
    ) {
        if (observation.position() == null || observation.dimensionId() == null) {
            return -1.0D;
        }
        final ClientWorldTrajectory.CausalCorridor corridor = causalCorridor(visit, observation);
        if (!Double.isInfinite(corridor.endpointDistance())) {
            return corridor.endpointDistance();
        }
        return visit.lastPosition() == null
            ? -1.0D : threeDimensionalDistance(observation.position(), visit.lastPosition());
    }

    private static double trajectoryConfidence(
        final ClientWorldVisit visit,
        final ClientWorldObservation observation
    ) {
        if (observation.position() == null || observation.dimensionId() == null) {
            return -1.0D;
        }
        final ClientWorldTrajectory.CausalCorridor corridor = causalCorridor(visit, observation);
        if (Double.isInfinite(corridor.endpointDistance())) {
            return visit.lastPosition() == null
                ? -1.0D
                : positionConfidence(threeDimensionalDistance(observation.position(), visit.lastPosition()));
        }
        final double freshness = trajectoryFreshnessConfidence(visit, observation);
        return positionConfidence(corridor.alongDistance())
            * positionConfidence(corridor.lateralDistance())
            * (freshness < 0.0D ? 1.0D : freshness);
    }

    private static double trajectoryFreshnessConfidence(
        final ClientWorldVisit visit,
        final ClientWorldObservation observation
    ) {
        final ClientWorldTrajectory trajectory = usableTrajectory(visit, observation);
        return trajectory == null ? -1.0D : confirmationConfidence(trajectory, observationTime(observation));
    }

    private static ClientWorldTrajectory.CausalCorridor causalCorridor(
        final ClientWorldVisit visit,
        final ClientWorldObservation observation
    ) {
        if (observation.position() == null || observation.dimensionId() == null) {
            return new ClientWorldTrajectory.CausalCorridor(
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0D,
                Double.POSITIVE_INFINITY, 0L
            );
        }
        final ClientWorldTrajectory trajectory = usableTrajectory(visit, observation);
        return trajectory == null
            ? new ClientWorldTrajectory.CausalCorridor(
                0.0D,
                visit.lastPosition() == null ? Double.POSITIVE_INFINITY
                    : threeDimensionalDistance(observation.position(), visit.lastPosition()),
                0.0D,
                visit.lastPosition() == null ? Double.POSITIVE_INFINITY
                    : threeDimensionalDistance(observation.position(), visit.lastPosition()),
                0L
            )
            : trajectory.causalCorridor(
                observation.position(), observation.dimensionId(), observationTime(observation),
                PREDICTION_HORIZON_MS
            );
    }

    private static double threeDimensionalDistance(
        final ClientWorldPosition first,
        final ClientWorldPosition second
    ) {
        final long dx = (long) first.x() - second.x();
        final long dy = (long) first.y() - second.y();
        final long dz = (long) first.z() - second.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double corridorPositionConfidence(final double distance, final double radius) {
        if (distance >= POSITION_CONFIDENCE_CUTOFF_DISTANCE) {
            return 0.0D;
        }
        return positionConfidence(Math.max(0.0D, distance - Math.max(0.0D, radius)));
    }

    private static ClientWorldTrajectory usableTrajectory(
        final ClientWorldVisit visit,
        final ClientWorldObservation observation
    ) {
        if (!visit.trajectorySamples().isEmpty()) {
            final ClientWorldTrajectory trajectory = ClientWorldTrajectory.fromHistoricalSamples(
                visit.trajectorySamples(), ClientWorldTrajectory.DEFAULT_CAPACITY
            );
            if (trajectory.latest() != null
                && trajectory.latest().dimensionId().equals(observation.dimensionId())) {
                return trajectory;
            }
        }
        return null;
    }

    static double confirmationConfidence(
        final ClientWorldTrajectory trajectory,
        final long now
    ) {
        final long acknowledgementAge = trajectory.acknowledgementAgeMs(now);
        final ClientWorldTrajectorySample latest = trajectory.latest();
        final long localEvidenceAge = latest == null
            ? Long.MAX_VALUE : Math.max(0L, now - latest.clientTimeMs());
        final long age = acknowledgementAge == Long.MAX_VALUE
            ? localEvidenceAge : acknowledgementAge;
        if (age <= PREDICTION_HORIZON_MS) {
            return 1.0D;
        }
        return Math.max(0.50D, Math.exp(-(double) (age - PREDICTION_HORIZON_MS)
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
        final Mutation<String> mutation = mutate(copy -> {
            final ClientWorldProfile candidate = requireProfile(copy.mutableProfiles(serverId), profile.id());
            // Historical candidate probes are not stable visit evidence. Persist only the
            // current observation, whose terrain center (if any) is player-centered.
            candidate.bind(observation, policy().maxBindingsPerProfile());
            rememberLastStable(copy, serverId, candidate.id(), observation);
            return candidate.id();
        });
        return resolvedMutation(serverId, mutation, candidates, source);
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
        if (!registry.available()) {
            return new Mutation<>(null, MutationResult.failure(registry.loadFailure()));
        }
        final ClientWorldProfileRegistry candidate = registry.copy();
        final T value = change.apply(candidate);
        final ClientWorldProfileIo.SaveResult persisted;
        try {
            persisted = persistence.save(candidate);
        } catch (final RuntimeException error) {
            return new Mutation<>(null, MutationResult.failure(error.toString()));
        }
        if (persisted == null || !persisted.saved()) {
            return new Mutation<>(null, MutationResult.failure(persisted == null ? null : persisted.error()));
        }
        registry.replaceWith(candidate);
        return new Mutation<>(value, MutationResult.success());
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
                profile.id(), !reasons.contains("legacy_profile"), (int) Math.round(score * 100.0D),
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
}
