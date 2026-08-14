package cn.net.rms.confluxmap.core.multiworld;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Serializable collection of client-owned logical worlds grouped by sanitized server id, plus the
 * names the player gave worlds a companion server owns. Those worlds are not profiles — the server
 * decides their identity — but their display name is a client-side record like every other name
 * here, so it belongs with them rather than in the server-identity registry.
 */
public final class ClientWorldProfileRegistry {
    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    private Map<String, List<ClientWorldProfile>> servers = new LinkedHashMap<>();
    private Map<String, Map<String, String>> serverWorldNames = new LinkedHashMap<>();

    public List<ClientWorldProfile> profiles(final String serverId) {
        return List.copyOf(mutableProfiles(serverId));
    }

    List<ClientWorldProfile> mutableProfiles(final String serverId) {
        if (servers == null) {
            servers = new LinkedHashMap<>();
        }
        return servers.computeIfAbsent(serverId, ignored -> new ArrayList<>());
    }

    /** Name the player gave the companion-owned world {@code worldId} on {@code serverId}. */
    public Optional<String> serverWorldName(final String serverId, final String worldId) {
        final Map<String, String> names = serverWorldNames().get(serverId);
        return names == null ? Optional.empty() : Optional.ofNullable(names.get(worldId));
    }

    /** Names a companion-owned world, or clears the name when {@code name} is null or blank. */
    public void nameServerWorld(final String serverId, final String worldId, final String name) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(worldId, "worldId");
        if (name == null || name.isBlank()) {
            final Map<String, String> names = serverWorldNames().get(serverId);
            if (names != null) {
                names.remove(worldId);
                if (names.isEmpty()) {
                    serverWorldNames().remove(serverId);
                }
            }
            return;
        }
        serverWorldNames()
            .computeIfAbsent(serverId, ignored -> new LinkedHashMap<>())
            .put(worldId, name.trim());
    }

    private Map<String, Map<String, String>> serverWorldNames() {
        if (serverWorldNames == null) {
            serverWorldNames = new LinkedHashMap<>();
        }
        return serverWorldNames;
    }

    public void normalize() {
        if (schemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported client-world profile schema " + schemaVersion);
        }
        schemaVersion = SCHEMA_VERSION;
        final Map<String, List<ClientWorldProfile>> normalized = new LinkedHashMap<>();
        if (servers != null) {
            for (final Map.Entry<String, List<ClientWorldProfile>> entry : servers.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    continue;
                }
                final List<ClientWorldProfile> profiles = new ArrayList<>();
                for (final ClientWorldProfile profile : entry.getValue()) {
                    if (profile != null) {
                        profile.normalize();
                        profiles.add(profile);
                    }
                }
                normalized.put(entry.getKey(), profiles);
            }
        }
        servers = normalized;

        final Map<String, Map<String, String>> normalizedNames = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, String>> server : serverWorldNames().entrySet()) {
            if (server.getKey() == null || server.getKey().isBlank() || server.getValue() == null) {
                continue;
            }
            final Map<String, String> names = new LinkedHashMap<>();
            for (final Map.Entry<String, String> name : server.getValue().entrySet()) {
                if (name.getKey() != null && !name.getKey().isBlank()
                    && name.getValue() != null && !name.getValue().isBlank()) {
                    names.put(name.getKey(), name.getValue());
                }
            }
            if (!names.isEmpty()) {
                normalizedNames.put(server.getKey(), names);
            }
        }
        serverWorldNames = normalizedNames;
    }
}
