package cn.net.rms.confluxmap.core.multiworld;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;

/** Conservative matcher: strong unique evidence wins; weak or duplicated evidence never guesses. */
public final class ClientWorldProfileResolver {
    public static final int MIN_SUPPORTING_SIGNALS = 3;

    private final ClientWorldProfileRegistry registry;
    private final Supplier<UUID> ids;
    private final Runnable onChange;

    public ClientWorldProfileResolver(
        final ClientWorldProfileRegistry registry,
        final Supplier<UUID> ids
    ) {
        this(registry, ids, () -> { });
    }

    public ClientWorldProfileResolver(
        final ClientWorldProfileRegistry registry,
        final Supplier<UUID> ids,
        final Runnable onChange
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    public ClientWorldResolution resolve(
        final String serverId,
        final ClientWorldObservation observation
    ) {
        final List<ClientWorldProfile> profiles = registry.mutableProfiles(serverId);
        if (profiles.isEmpty()) {
            return ClientWorldResolution.resolved(create(serverId, "world", observation));
        }

        if (observation.seedHash().isPresent()) {
            final long seedHash = observation.seedHash().getAsLong();
            final List<ClientWorldProfile> seedMatches = profiles.stream()
                .filter(profile -> profile.matchesSeed(seedHash))
                .toList();
            if (seedMatches.size() == 1) {
                return resolvedAndLearn(seedMatches.get(0), observation);
            }
            if (seedMatches.isEmpty()) {
                if (profiles.stream().anyMatch(ClientWorldProfile::recognitionDisabled)) {
                    return ClientWorldResolution.ambiguous();
                }
                return ClientWorldResolution.resolved(create(serverId, nextStorageId(), observation));
            }
            return uniqueSupporting(seedMatches, observation);
        }

        return uniqueSupporting(profiles, observation);
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
            legacy.bindVelocityServer(normalized);
            legacy.bind(observation);
            onChange.run();
            return ClientWorldResolution.resolved(legacy);
        }
        final String storageId = profiles.isEmpty() ? "world" : nextStorageId();
        final UUID id = ids.get();
        final ClientWorldProfile profile = new ClientWorldProfile(
            id.toString(), storageId, "World " + (profiles.size() + 1)
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
        final List<ClientWorldProfile> profiles = registry.mutableProfiles(serverId);
        final ClientWorldProfile profile = requireProfile(profiles, profileId);
        if (observation.seedHash().isPresent()) {
            for (final ClientWorldProfile other : profiles) {
                if (other != profile) {
                    other.unbind(observation);
                }
            }
        }
        profile.bind(observation);
        onChange.run();
        return ClientWorldResolution.resolved(profile);
    }

    public ClientWorldResolution createAndSelect(
        final String serverId,
        final String displayName,
        final ClientWorldObservation observation
    ) {
        if (observation.seedHash().isPresent()) {
            for (final ClientWorldProfile profile : registry.mutableProfiles(serverId)) {
                profile.unbind(observation);
            }
        }
        final ClientWorldProfile profile = create(serverId, nextStorageId(), observation);
        profile.rename(displayName);
        onChange.run();
        return ClientWorldResolution.resolved(profile);
    }

    public List<ClientWorldProfile> profiles(final String serverId) {
        return registry.profiles(serverId);
    }

    public void rename(final String serverId, final String profileId, final String displayName) {
        requireProfile(serverId, profileId).rename(displayName);
        onChange.run();
    }

    public void clearBindings(final String serverId, final String profileId) {
        requireProfile(serverId, profileId).clearBindings();
        onChange.run();
    }

    private ClientWorldResolution uniqueSupporting(
        final List<ClientWorldProfile> candidates,
        final ClientWorldObservation observation
    ) {
        int best = MIN_SUPPORTING_SIGNALS - 1;
        ClientWorldProfile winner = null;
        boolean tied = false;
        for (final ClientWorldProfile profile : candidates) {
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
            ? resolvedAndLearn(winner, observation)
            : ClientWorldResolution.ambiguous();
    }

    private ClientWorldResolution resolvedAndLearn(
        final ClientWorldProfile profile,
        final ClientWorldObservation observation
    ) {
        final int before = profile.bindingCount();
        profile.bind(observation);
        if (profile.bindingCount() != before) {
            onChange.run();
        }
        return ClientWorldResolution.resolved(profile);
    }

    private ClientWorldProfile create(
        final String serverId,
        final String storageId,
        final ClientWorldObservation observation
    ) {
        final UUID id = ids.get();
        final List<ClientWorldProfile> profiles = registry.mutableProfiles(serverId);
        final ClientWorldProfile profile = new ClientWorldProfile(
            id.toString(), storageId, "World " + (profiles.size() + 1)
        );
        profile.bind(observation);
        profiles.add(profile);
        onChange.run();
        return profile;
    }

    private String nextStorageId() {
        return "client-" + ids.get();
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
}
