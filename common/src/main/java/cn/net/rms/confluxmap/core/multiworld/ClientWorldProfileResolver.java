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
    public static final int MIN_SUPPORTING_SIGNALS = 3;
    public static final int MAX_PROFILES_PER_SERVER = ClientWorldPolicy.DEFAULT_MAX_PROFILES_PER_SERVER;
    private static final double AUTO_SELECT_MIN_CONFIDENCE = 0.60D;
    /** Scores within three percentage points are indistinguishable and require a manual choice. */
    private static final double AUTO_SELECT_ERROR_MARGIN = 0.03D;
    private static final double GAME_MODE_WEIGHT = 0.40D;
    private static final double POSITION_WEIGHT = 0.40D;
    private static final double TERRAIN_WEIGHT = 0.20D;
    private static final double VISIT_CONTEXT_WEIGHT = 0.20D;
    private static final double POSITION_NO_MATCH_DISTANCE = 2_048.0D;
    private static final double TERRAIN_DISCRIMINATOR_MIN_SCORE = 0.85D;
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
            if (seedMatches.size() == 1) {
                final ClientWorldProfile only = seedMatches.get(0);
                // A unique seed identifies the logical world across dimensions. Dimension
                // metadata is retained as visit evidence and is intentionally not a conflict.
                return only.hasSignalConflict(observation)
                    ? ClientWorldResolution.ambiguous()
                    : resolvedAndLearn(serverId, only, observation);
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
            // An explicit manual correction moves this seeded observation to the selected
            // profile. Unseeded evidence remains additive and can still be ambiguous.
            moveSeedBinding(profiles, profile, observation);
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

    private ClientWorldResolution uniqueSupporting(
        final String serverId,
        final List<ClientWorldProfile> candidates,
        final ClientWorldObservation observation
    ) {
        int best = MIN_SUPPORTING_SIGNALS - 1;
        ClientWorldProfile winner = null;
        boolean tied = false;
        for (final ClientWorldProfile profile : candidates) {
            if (profile.hasSignalConflict(observation)) {
                continue;
            }
            final int score = profile.bestSignalMatch(observation);
            if (score > best) {
                best = score;
                winner = profile;
                tied = false;
            } else if (score == best && score >= MIN_SUPPORTING_SIGNALS) {
                tied = true;
            }
        }
        return winner != null && !tied
            ? resolvedAndLearn(serverId, winner, observation)
            : ClientWorldResolution.ambiguous();
    }

    private ClientWorldResolution resolveCandidates(
        final String serverId,
        final List<ClientWorldProfile> candidates,
        final ClientWorldObservation observation
    ) {
        if (candidates.size() == 1 && observation.dimensionId() != null
            && candidates.get(0).visit(observation.dimensionId()) == null
            && !candidates.get(0).hasSignalConflict(observation)
            && candidates.get(0).bestSignalMatch(observation) >= MIN_SUPPORTING_SIGNALS) {
            return resolvedAndLearn(serverId, candidates.get(0), observation);
        }
        final ClientWorldResolution scored = scoreCandidates(serverId, candidates, observation);
        return scored != null ? scored : uniqueSupporting(serverId, candidates, observation);
    }

    /**
     * Scores v2 per-dimension visit evidence. Null means this is a legacy observation and should
     * retain the signal-only resolver path; an empty candidate list is still an explicit result.
     */
    private ClientWorldResolution scoreCandidates(
        final String serverId,
        final List<ClientWorldProfile> profiles,
        final ClientWorldObservation observation
    ) {
        if (observation.dimensionId() == null
            || observation.gameMode() == null && observation.position() == null
                && observation.terrainFingerprint() == null) {
            return null;
        }
        final List<CandidateScore> scores = new ArrayList<>();
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
            double weighted = 0.0D;
            double availableWeight = 0.0D;
            if (observation.gameMode() != null && visit.gameMode() != null) {
                final boolean matches = observation.gameMode().equals(visit.gameMode());
                weighted += matches ? GAME_MODE_WEIGHT : 0.0D;
                availableWeight += GAME_MODE_WEIGHT;
                reasons.add(matches ? "game_mode_match" : "game_mode_mismatch");
            }
            if (observation.position() != null && visit.lastPosition() != null) {
                final double distance = observation.position().horizontalDistanceTo(visit.lastPosition());
                weighted += POSITION_WEIGHT * Math.max(0.0D, 1.0D - distance / POSITION_NO_MATCH_DISTANCE);
                availableWeight += POSITION_WEIGHT;
                reasons.add(distance <= 64.0D ? "position_near" : "position_far");
            }
            if (observation.terrainFingerprint() != null && visit.terrainFingerprint() != null) {
                final ClientWorldTerrainFingerprint.Match terrain = observation.terrainFingerprint()
                    .match(visit.terrainFingerprint());
                if (terrain.available()) {
                    weighted += TERRAIN_WEIGHT * terrain.score();
                    availableWeight += TERRAIN_WEIGHT;
                    terrainScore = terrain.score();
                    reasons.add("terrain_" + terrain.comparableChunks() + "_of_9");
                } else {
                    reasons.add("terrain_unavailable");
                }
            }
            if (context.shared() > 0) {
                weighted += VISIT_CONTEXT_WEIGHT * context.score();
                availableWeight += VISIT_CONTEXT_WEIGHT;
                reasons.add("visit_context_" + context.matches() + "_of_" + context.shared());
            }
            final boolean conflicted = profile.hasSignalConflict(observation) || context.hasStableConflict();
            if (conflicted) {
                reasons.add(context.hasStableConflict() ? "visit_context_conflict" : "signal_conflict");
            }
            scores.add(new CandidateScore(
                profile, availableWeight == 0.0D ? 0.0D : weighted / availableWeight,
                reasons, conflicted, terrainScore
            ));
        }
        if (scores.isEmpty()) {
            return ClientWorldResolution.ambiguous();
        }
        scores.sort(Comparator.comparingDouble(CandidateScore::score).reversed());
        final CandidateScore best = scores.get(0);
        final double runnerUp = scores.size() > 1 ? scores.get(1).score() : 0.0D;
        final List<ClientWorldResolution.Candidate> display = scores.stream()
            .map(CandidateScore::display)
            .toList();
        if (!best.conflicted() && best.score() >= AUTO_SELECT_MIN_CONFIDENCE
            && best.score() - runnerUp > AUTO_SELECT_ERROR_MARGIN
            && (!observation.seedHash().isPresent() || profiles.size() == 1
                || hasTerrainDiscriminator(best, scores))) {
            return resolvedAndLearn(serverId, best.profile(), observation);
        }
        return ClientWorldResolution.ambiguous(display);
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
                || best.terrainScore() < TERRAIN_DISCRIMINATOR_MIN_SCORE
                || best.terrainScore() - candidate.terrainScore() < TERRAIN_DISCRIMINATOR_MIN_GAP) {
                return false;
            }
        }
        return compatibleProfiles < 2 || best.hasTerrainScore()
            && best.terrainScore() >= TERRAIN_DISCRIMINATOR_MIN_SCORE;
    }

    private ClientWorldResolution resolvedAndLearn(
        final String serverId,
        final ClientWorldProfile profile,
        final ClientWorldObservation observation
    ) {
        final Mutation<String> mutation = mutate(copy -> {
            final ClientWorldProfile candidate = requireProfile(copy.mutableProfiles(serverId), profile.id());
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
        double terrainScore
    ) {
        private CandidateScore {
            reasons = List.copyOf(reasons);
        }

        static CandidateScore legacy(final ClientWorldProfile profile, final boolean conflicted) {
            return new CandidateScore(profile, 0.0D, List.of("legacy_profile"), conflicted, Double.NaN);
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
}
