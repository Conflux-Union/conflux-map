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
    private static final double AUXILIARY_WEIGHT = 0.60D;
    private static final double TERRAIN_WEIGHT = 0.40D;
    private static final double GAME_MODE_WEIGHT = 0.40D;
    private static final double POSITION_WEIGHT = 0.40D;
    private static final double VISIT_CONTEXT_WEIGHT = 0.20D;
    private static final double OVERWORLD_POSITION_RADIUS = 48.0D;
    private static final double NETHER_POSITION_RADIUS = 6.0D;
    private static final double POSITION_CONFIDENCE_CUTOFF_DISTANCE = 1_024.0D;
    private static final double TERRAIN_MATCH_MIN_SCORE = 0.85D;
    private static final double TERRAIN_DISCRIMINATOR_MIN_GAP = 0.10D;

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
                    return ClientWorldResolution.ambiguous();
                }
                if (profiles.size() >= policy().maxProfilesPerServer()) {
                    return ClientWorldResolution.persistenceFailed("client world profile limit reached");
                }
                return create(serverId, nextStorageId(), observation);
            }
            return resolveCandidates(serverId, seedMatches, observation);
        }

        return resolveCandidates(serverId, profiles, observation);
    }

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
            return ClientWorldResolution.ambiguous();
        }
        if (observation.seedHash().isPresent()) {
            final long seedHash = observation.seedHash().getAsLong();
            final long matchingProfiles = registry.mutableProfiles(serverId).stream()
                .filter(profile -> profile.matchesSeed(seedHash))
                .limit(2)
                .count();
            if (matchingProfiles > 1) {
                return ClientWorldResolution.ambiguous();
            }
        }
        return resolve(serverId, observation);
    }

    public ClientWorldResolution select(
        final String serverId,
        final String profileId,
        final ClientWorldObservation observation
    ) {
        final Mutation<String> mutation = mutate(copy -> {
            final List<ClientWorldProfile> profiles = copy.mutableProfiles(serverId);
            final ClientWorldProfile profile = requireProfile(profiles, profileId);
            // An explicit manual correction moves this seeded observation to the selected
            // profile. Unseeded evidence remains additive and can still be ambiguous.
            moveSeedBinding(profiles, profile, observation);
            // Candidate-specific historical terrain is match-only evidence. A manual selection
            // must learn the current player-centered sample, never relabel the visit to a saved
            // historical center.
            profile.bind(observation, policy().maxBindingsPerProfile());
            return profile.id();
        });
        return resolvedMutation(serverId, mutation);
    }

    /**
     * Activates a command-selected profile without learning the current observation. The command
     * is sent before a proxy may replace its upstream world, so binding the old evidence here
     * would corrupt the target profile.
     */
    public ClientWorldResolution activateCommand(final String serverId, final String profileId) {
        return ClientWorldResolution.resolved(requireProfile(serverId, profileId));
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
            return ClientWorldResolution.persistenceFailed("client world profile limit reached");
        }
        final Mutation<String> mutation = mutate(copy -> {
            final List<ClientWorldProfile> profiles = copy.mutableProfiles(serverId);
            final ClientWorldProfile profile = create(profiles, nextStorageId(), observation);
            moveSeedBinding(profiles, profile, observation);
            profile.rename(displayName);
            return profile.id();
        });
        return resolvedMutation(serverId, mutation);
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

    /**
     * Scores per-dimension visit evidence. A null result means the observation cannot enter the
     * dimension/coordinate/terrain queues yet and must remain manual or collecting.
     */
    private ClientWorldResolution scoreCandidates(
        final String serverId,
        final List<ClientWorldProfile> profiles,
        final ClientWorldObservation observation
    ) {
        if (observation.dimensionId() == null
            || observation.gameMode() == null && observation.position() == null
                && observation.terrainFingerprint() == null
                && observation.terrainFingerprintsByProfileId().isEmpty()) {
            return null;
        }
        final List<CandidateScore> scores = new ArrayList<>();
        final List<TerrainCacheEntry> terrainCache = new ArrayList<>();
        for (final ClientWorldProfile profile : profiles) {
            final ClientWorldVisit visit = profile.visit(observation.dimensionId());
            if (visit == null) {
                scores.add(CandidateScore.legacy(profile, profile.hasSignalConflict(observation)));
                continue;
            }
            final List<String> reasons = new ArrayList<>();
            double terrainScore = Double.NaN;
            final ClientWorldVisit.ContextMatch context = visit.contextMatch(observation.signals());
            if (observation.seedHash().isPresent()) {
                reasons.add("seed_match");
            }
            double auxiliary = 0.0D;
            double availableAuxiliaryWeight = 0.0D;
            if (observation.gameMode() != null && visit.gameMode() != null) {
                final boolean matches = observation.gameMode().equals(visit.gameMode());
                auxiliary += matches ? GAME_MODE_WEIGHT : 0.0D;
                availableAuxiliaryWeight += GAME_MODE_WEIGHT;
                reasons.add(matches ? "game_mode_match" : "game_mode_mismatch");
            }
            int queue = 2;
            if (observation.position() != null && visit.lastPosition() != null) {
                final double distance = observation.position().horizontalDistanceTo(visit.lastPosition());
                final double radius = positionRadius(observation.dimensionId());
                if (distance <= radius) {
                    queue = 1;
                    reasons.add("position_near");
                } else {
                    queue = 3;
                    reasons.add("position_far");
                }
                auxiliary += POSITION_WEIGHT * positionConfidence(distance);
                availableAuxiliaryWeight += POSITION_WEIGHT;
            }
            final ClientWorldTerrainFingerprint observedTerrain = observation.terrainFingerprintFor(profile.id());
            if (observedTerrain != null && visit.terrainFingerprint() != null
                && observedTerrain.sameCenter(visit.terrainFingerprint())) {
                final ClientWorldTerrainFingerprint.Match terrain = cachedTerrainMatch(
                    terrainCache, observation.dimensionId(), visit.lastPosition(),
                    observedTerrain, visit.terrainFingerprint()
                );
                if (terrain.available()) {
                    terrainScore = terrain.score();
                    reasons.add("terrain_" + terrain.comparableChunks() + "_of_9");
                    if (terrainScore < TERRAIN_MATCH_MIN_SCORE && queue == 1) {
                        queue = 2;
                        reasons.add("terrain_below_threshold");
                    }
                } else {
                    reasons.add("terrain_unavailable");
                    if (queue == 1) {
                        queue = 2;
                    }
                }
            } else {
                reasons.add("terrain_unavailable");
                if (queue == 1) {
                    queue = 2;
                }
            }
            if (context.shared() > 0) {
                auxiliary += VISIT_CONTEXT_WEIGHT * context.score();
                availableAuxiliaryWeight += VISIT_CONTEXT_WEIGHT;
                reasons.add("visit_context_" + context.matches() + "_of_" + context.shared());
            }
            final boolean conflicted = profile.hasSignalConflict(observation) || context.hasStableConflict();
            if (conflicted) {
                reasons.add(context.hasStableConflict() ? "visit_context_conflict" : "signal_conflict");
            }
            final double auxiliaryScore = availableAuxiliaryWeight == 0.0D
                ? 0.0D : auxiliary / availableAuxiliaryWeight;
            final double score = hasTerrainScore(terrainScore)
                ? AUXILIARY_WEIGHT * auxiliaryScore + TERRAIN_WEIGHT * terrainScore
                : auxiliaryScore;
            scores.add(new CandidateScore(profile, score, reasons, conflicted, terrainScore, queue));
        }
        if (scores.isEmpty()) {
            return ClientWorldResolution.ambiguous();
        }
        scores.sort(Comparator.comparingInt(CandidateScore::queue)
            .thenComparing(Comparator.comparingDouble(CandidateScore::score).reversed()));
        final int bestQueue = scores.stream()
            .filter(candidate -> !candidate.conflicted())
            .mapToInt(CandidateScore::queue)
            .min()
            .orElse(Integer.MAX_VALUE);
        final List<CandidateScore> eligible = scores.stream()
            .filter(candidate -> !candidate.conflicted() && candidate.queue() == bestQueue)
            .sorted(Comparator.comparingDouble(CandidateScore::score).reversed())
            .toList();
        if (eligible.isEmpty()) {
            return ClientWorldResolution.ambiguous(scores.stream().map(CandidateScore::display).toList());
        }
        final CandidateScore best = eligible.get(0);
        final double runnerUp = eligible.size() > 1 ? eligible.get(1).score() : 0.0D;
        final List<ClientWorldResolution.Candidate> display = scores.stream()
            .map(CandidateScore::display)
            .toList();
        if (best.queue() == 1 && !best.conflicted() && best.score() >= AUTO_SELECT_MIN_CONFIDENCE
            && best.score() - runnerUp > AUTO_SELECT_ERROR_MARGIN
            && (!observation.seedHash().isPresent() || profiles.size() == 1
                || hasTerrainDiscriminator(best, scores))) {
            return resolvedAndLearn(serverId, best.profile(), observation);
        }
        return ClientWorldResolution.ambiguous(display);
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

    private static double positionRadius(final String dimensionId) {
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
        final String reason = observation.dimensionId() == null
            ? "dimension_unavailable" : "observation_incomplete";
        return candidates.stream()
            .map(profile -> new ClientWorldResolution.Candidate(
                profile.id(), 0, List.of(reason), profile.hasSignalConflict(observation)
            ))
            .toList();
    }

    private ClientWorldResolution resolvedAndLearn(
        final String serverId,
        final ClientWorldProfile profile,
        final ClientWorldObservation observation
    ) {
        final Mutation<String> mutation = mutate(copy -> {
            final ClientWorldProfile candidate = requireProfile(copy.mutableProfiles(serverId), profile.id());
            // Historical candidate probes are not stable visit evidence. Persist only the
            // current observation, whose terrain center (if any) is player-centered.
            candidate.bind(observation, policy().maxBindingsPerProfile());
            return candidate.id();
        });
        return resolvedMutation(serverId, mutation);
    }

    private ClientWorldResolution create(
        final String serverId,
        final String storageId,
        final ClientWorldObservation observation
    ) {
        final Mutation<String> mutation = mutate(copy -> {
            final ClientWorldProfile profile = create(copy.mutableProfiles(serverId), storageId, observation);
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

    private static void moveSeedBinding(
        final List<ClientWorldProfile> profiles,
        final ClientWorldProfile selected,
        final ClientWorldObservation observation
    ) {
        if (observation.seedHash().isEmpty()) {
            return;
        }
        for (final ClientWorldProfile profile : profiles) {
            if (profile != selected) {
                profile.unbind(observation);
            }
        }
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
        return mutation.applied()
            ? ClientWorldResolution.resolved(requireProfile(registry.mutableProfiles(serverId), mutation.value()))
            : ClientWorldResolution.persistenceFailed(mutation.result().error());
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
        int queue
    ) {
        private CandidateScore {
            reasons = List.copyOf(reasons);
        }

        static CandidateScore legacy(final ClientWorldProfile profile, final boolean conflicted) {
            return new CandidateScore(
                profile, 0.0D, List.of("legacy_profile"), conflicted, Double.NaN, 3
            );
        }

        boolean hasTerrainScore() {
            return !Double.isNaN(terrainScore);
        }

        ClientWorldResolution.Candidate display() {
            return new ClientWorldResolution.Candidate(
                profile.id(), (int) Math.round(score * 100.0D), reasons, conflicted
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
