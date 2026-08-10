package cn.net.rms.confluxmap.core.multiworld;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/** Serializable collection of client-owned logical worlds grouped by sanitized server id. */
public final class ClientWorldProfileRegistry {
    public static final int SCHEMA_VERSION = 3;

    private int schemaVersion = SCHEMA_VERSION;
    private Map<String, List<ClientWorldProfile>> servers = new LinkedHashMap<>();
    private Map<String, LastStableProfile> lastStableProfiles = new LinkedHashMap<>();
    private transient List<ProfileIssue> invalidProfiles = List.of();
    private transient String loadFailure;

    static ClientWorldProfileRegistry unavailable(final String error) {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        registry.loadFailure = error == null || error.isBlank() ? "client world registry unavailable" : error;
        return registry;
    }

    public boolean available() {
        return loadFailure == null;
    }

    public String loadFailure() {
        return loadFailure;
    }

    public List<ClientWorldProfile> profiles(final String serverId) {
        if (!available()) {
            return List.of();
        }
        return List.copyOf(mutableProfiles(serverId));
    }

    String lastStableProfileId(final String serverId) {
        final LastStableProfile stable = lastStableProfile(serverId);
        return stable == null ? null : stable.profileId();
    }

    LastStableProfile lastStableProfile(final String serverId) {
        return lastStableProfiles == null ? null : lastStableProfiles.get(serverId);
    }

    void setLastStableProfileId(final String serverId, final String profileId) {
        if (lastStableProfiles == null) {
            lastStableProfiles = new LinkedHashMap<>();
        }
        if (profileId == null || profileId.isBlank()) {
            lastStableProfiles.remove(serverId);
        } else {
            final LastStableProfile previous = lastStableProfiles.get(serverId);
            final long generation = previous == null ? 0L : previous.connectionGeneration();
            lastStableProfiles.put(serverId, new LastStableProfile(
                profileId, System.currentTimeMillis(), generation, false, 0L, Map.of()
            ));
        }
    }

    void setLastStableProfile(
        final String serverId,
        final String profileId,
        final long confirmedAtEpochMs,
        final long connectionGeneration,
        final ClientWorldObservation observation
    ) {
        if (profileId == null || profileId.isBlank()) {
            setLastStableProfileId(serverId, null);
            return;
        }
        if (lastStableProfiles == null) {
            lastStableProfiles = new LinkedHashMap<>();
        }
        final OptionalLong seed = observation == null ? OptionalLong.empty() : observation.seedHash();
        lastStableProfiles.put(serverId, new LastStableProfile(
            profileId, Math.max(0L, confirmedAtEpochMs), Math.max(0L, connectionGeneration),
            seed.isPresent(), seed.orElse(0L), stableSignals(observation)
        ));
    }

    void setLastStableProfile(
        final String serverId,
        final String profileId,
        final long confirmedAtEpochMs,
        final long connectionGeneration
    ) {
        setLastStableProfile(
            serverId, profileId, confirmedAtEpochMs, connectionGeneration, null
        );
    }

    /** Creates an independent registry image for persist-before-publish mutations. */
    ClientWorldProfileRegistry copy() {
        final ClientWorldProfileRegistry copy = new ClientWorldProfileRegistry();
        copy.schemaVersion = schemaVersion;
        copy.servers = new LinkedHashMap<>();
        copy.lastStableProfiles = lastStableProfiles == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(lastStableProfiles);
        if (servers != null) {
            for (final Map.Entry<String, List<ClientWorldProfile>> entry : servers.entrySet()) {
                final List<ClientWorldProfile> profiles = new ArrayList<>();
                if (entry.getValue() != null) {
                    for (final ClientWorldProfile profile : entry.getValue()) {
                        if (profile != null) {
                            profiles.add(profile.copy());
                        }
                    }
                }
                copy.servers.put(entry.getKey(), profiles);
            }
        }
        copy.invalidProfiles = invalidProfiles;
        copy.loadFailure = loadFailure;
        return copy;
    }

    /** Publishes an already-persisted registry image without replacing this shared object. */
    void replaceWith(final ClientWorldProfileRegistry persisted) {
        schemaVersion = persisted.schemaVersion;
        final Map<String, List<ClientWorldProfile>> published = new LinkedHashMap<>();
        if (persisted.servers != null) {
            for (final Map.Entry<String, List<ClientWorldProfile>> entry : persisted.servers.entrySet()) {
                final Map<String, ClientWorldProfile> existing = new LinkedHashMap<>();
                final List<ClientWorldProfile> current = servers == null ? null : servers.get(entry.getKey());
                if (current != null) {
                    for (final ClientWorldProfile profile : current) {
                        if (profile != null) {
                            existing.put(profile.id(), profile);
                        }
                    }
                }
                final List<ClientWorldProfile> profiles = new ArrayList<>();
                if (entry.getValue() != null) {
                    for (final ClientWorldProfile next : entry.getValue()) {
                        if (next == null) {
                            continue;
                        }
                        final ClientWorldProfile prior = existing.get(next.id());
                        if (prior != null) {
                            prior.replaceWith(next);
                            profiles.add(prior);
                        } else {
                            profiles.add(next.copy());
                        }
                    }
                }
                published.put(entry.getKey(), profiles);
            }
        }
        servers = published;
        lastStableProfiles = persisted.lastStableProfiles == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(persisted.lastStableProfiles);
        invalidProfiles = persisted.invalidProfiles;
        loadFailure = persisted.loadFailure;
    }

    List<ClientWorldProfile> mutableProfiles(final String serverId) {
        if (servers == null) {
            servers = new LinkedHashMap<>();
        }
        return servers.computeIfAbsent(serverId, ignored -> new ArrayList<>());
    }

    /**
     * Merges a pre-default-port-canonicalization key into its logical server. Physical map
     * ownership stays on the source profile, so two populated keys are never overwritten.
     */
    AliasMerge mergeServerAlias(final String canonicalServerId, final String legacyServerId) {
        if (Objects.equals(canonicalServerId, legacyServerId) || servers == null) {
            return new AliasMerge(false, List.of());
        }
        final List<ClientWorldProfile> source = servers.get(legacyServerId);
        if (source == null || source.isEmpty()) {
            return new AliasMerge(false, List.of());
        }
        final List<ClientWorldProfile> target = mutableProfiles(canonicalServerId);
        final Set<String> ids = new HashSet<>();
        target.stream().map(ClientWorldProfile::id).forEach(ids::add);
        final List<String> conflicts = new ArrayList<>();
        final Map<String, String> remappedIds = new LinkedHashMap<>();
        for (final ClientWorldProfile profile : source) {
            if (profile == null) {
                continue;
            }
            profile.retainStorageServer(legacyServerId);
            final String originalId = profile.id();
            if (!ids.add(originalId)) {
                String replacement = originalId + "@" + Integer.toUnsignedString(legacyServerId.hashCode(), 36);
                int suffix = 2;
                while (!ids.add(replacement)) {
                    replacement = originalId + "@" + Integer.toUnsignedString(legacyServerId.hashCode(), 36)
                        + "-" + suffix++;
                }
                profile.reidentify(replacement);
                remappedIds.put(originalId, replacement);
                conflicts.add("duplicate profile id " + originalId + " retained as " + replacement);
            }
            target.add(profile);
        }
        final LastStableProfile legacyStable = lastStableProfiles == null
            ? null : lastStableProfiles.remove(legacyServerId);
        if (legacyStable != null) {
            final String stableId = remappedIds.getOrDefault(legacyStable.profileId(), legacyStable.profileId());
            final LastStableProfile moved = new LastStableProfile(
                stableId, legacyStable.confirmedAtEpochMs(), legacyStable.connectionGeneration(),
                legacyStable.hasSeed(), legacyStable.seedHash(), legacyStable.stableSignals()
            );
            final LastStableProfile canonicalStable = lastStableProfiles.get(canonicalServerId);
            if (canonicalStable == null || moved.confirmedAtEpochMs() > canonicalStable.confirmedAtEpochMs()) {
                lastStableProfiles.put(canonicalServerId, moved);
            } else {
                conflicts.add("both aliases contained a last-stable profile; retained the newer canonical pointer");
            }
        }
        servers.remove(legacyServerId);
        return new AliasMerge(true, List.copyOf(conflicts));
    }

    record AliasMerge(boolean changed, List<String> conflicts) { }

    /**
     * Normalizes optional fields from schema v1 files. Duplicate commands remain assigned to the
     * first profile in configuration order, keeping load deterministic and non-destructive.
     */
    public List<CommandConflict> normalize() {
        if (schemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported client-world profile schema " + schemaVersion);
        }
        schemaVersion = SCHEMA_VERSION;
        final Map<String, List<ClientWorldProfile>> normalized = new LinkedHashMap<>();
        final Map<String, LastStableProfile> normalizedStable = new LinkedHashMap<>();
        final List<CommandConflict> commandConflicts = new ArrayList<>();
        final List<ProfileIssue> profileIssues = new ArrayList<>();
        if (servers != null) {
            for (final Map.Entry<String, List<ClientWorldProfile>> entry : servers.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    continue;
                }
                final List<ClientWorldProfile> profiles = new ArrayList<>();
                final Set<String> claimedCommands = new HashSet<>();
                final Set<String> claimedProfileIds = new HashSet<>();
                final Set<String> claimedStorageIds = new HashSet<>();
                for (final ClientWorldProfile profile : entry.getValue()) {
                    if (profile != null) {
                        try {
                            profile.normalize();
                        } catch (final IllegalArgumentException error) {
                            profileIssues.add(new ProfileIssue(entry.getKey(), profile.id(), error.getMessage()));
                            continue;
                        }
                        if (!claimedProfileIds.add(profile.id())
                            || !claimedStorageIds.add(
                                profile.storageServerId(entry.getKey()).toLowerCase(Locale.ROOT)
                                    + "/" + profile.storageId().toLowerCase(Locale.ROOT)
                            )) {
                            profileIssues.add(new ProfileIssue(
                                entry.getKey(), profile.id(), "duplicate profile id or storageId"
                            ));
                            continue;
                        }
                        for (final String discarded : profile.retainUnclaimedSwitchCommands(claimedCommands)) {
                            commandConflicts.add(new CommandConflict(entry.getKey(), profile.id(), discarded));
                        }
                        profiles.add(profile);
                    }
                }
                normalized.put(entry.getKey(), profiles);
            }
        }
        servers = normalized;
        if (lastStableProfiles != null) {
            for (final Map.Entry<String, LastStableProfile> entry : lastStableProfiles.entrySet()) {
                final LastStableProfile stable = entry.getValue();
                if (entry.getKey() == null || entry.getKey().isBlank()
                    || stable == null || stable.profileId() == null || stable.profileId().isBlank()
                    || stable.confirmedAtEpochMs() < 0L || stable.connectionGeneration() < 0L) {
                    continue;
                }
                if (normalized.getOrDefault(entry.getKey(), List.of()).stream()
                    .anyMatch(profile -> profile.id().equals(stable.profileId()))) {
                    normalizedStable.put(entry.getKey(), stable.normalized());
                }
            }
        }
        lastStableProfiles = normalizedStable;
        invalidProfiles = List.copyOf(profileIssues);
        return List.copyOf(commandConflicts);
    }

    /** A duplicate command removed from a later profile while loading one server's configuration. */
    public record CommandConflict(String serverId, String profileId, String command) {
    }

    public List<ProfileIssue> invalidProfiles() {
        return invalidProfiles;
    }

    /** One malformed profile dropped while preserving valid profiles in the same registry. */
    public record ProfileIssue(String serverId, String profileId, String reason) {
    }

    /** Persisted continuity pointer for normal reconnects. */
    record LastStableProfile(
        String profileId,
        long confirmedAtEpochMs,
        long connectionGeneration,
        boolean hasSeed,
        long seedHash,
        Map<String, String> stableSignals
    ) {
        LastStableProfile normalized() {
            return new LastStableProfile(
                profileId, confirmedAtEpochMs, connectionGeneration, hasSeed, seedHash,
                stableSignals == null ? Map.of() : Map.copyOf(stableSignals)
            );
        }

        boolean conflicts(final ClientWorldObservation observation) {
            if (observation == null) {
                return false;
            }
            if (hasSeed && observation.seedHash().isPresent()
                && seedHash != observation.seedHash().getAsLong()) {
                return true;
            }
            final Map<String, String> persistedSignals = stableSignals == null
                ? Map.of() : stableSignals;
            for (final Map.Entry<String, String> signal : persistedSignals.entrySet()) {
                final String observed = observation.signals().get(signal.getKey());
                if (observed != null && !Objects.equals(signal.getValue(), observed)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Map<String, String> stableSignals(final ClientWorldObservation observation) {
        if (observation == null) {
            return Map.of();
        }
        final Map<String, String> stable = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : observation.signals().entrySet()) {
            if (!isVisitSignal(entry.getKey())) {
                stable.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(stable);
    }

    private static boolean isVisitSignal(final String key) {
        return "dimension".equals(key) || "dimension_type".equals(key)
            || "world_shape".equals(key) || "difficulty".equals(key)
            || "spawn".equals(key) || "world_border".equals(key);
    }
}
