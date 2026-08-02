package cn.net.rms.confluxmap.core.multiworld;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Serializable collection of client-owned logical worlds grouped by sanitized server id. */
public final class ClientWorldProfileRegistry {
    public static final int SCHEMA_VERSION = 2;

    private int schemaVersion = SCHEMA_VERSION;
    private Map<String, List<ClientWorldProfile>> servers = new LinkedHashMap<>();
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

    /** Creates an independent registry image for persist-before-publish mutations. */
    ClientWorldProfileRegistry copy() {
        final ClientWorldProfileRegistry copy = new ClientWorldProfileRegistry();
        copy.schemaVersion = schemaVersion;
        copy.servers = new LinkedHashMap<>();
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
     * Normalizes optional fields from schema v1 files. Duplicate commands remain assigned to the
     * first profile in configuration order, keeping load deterministic and non-destructive.
     */
    public List<CommandConflict> normalize() {
        if (schemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported client-world profile schema " + schemaVersion);
        }
        schemaVersion = SCHEMA_VERSION;
        final Map<String, List<ClientWorldProfile>> normalized = new LinkedHashMap<>();
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
                            || !claimedStorageIds.add(profile.storageId().toLowerCase(Locale.ROOT))) {
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
}
