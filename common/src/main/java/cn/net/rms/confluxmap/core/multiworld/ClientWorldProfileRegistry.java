package cn.net.rms.confluxmap.core.multiworld;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializable collection of client-owned logical worlds grouped by sanitized server id. */
public final class ClientWorldProfileRegistry {
    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    private Map<String, List<ClientWorldProfile>> servers = new LinkedHashMap<>();

    public List<ClientWorldProfile> profiles(final String serverId) {
        return List.copyOf(mutableProfiles(serverId));
    }

    List<ClientWorldProfile> mutableProfiles(final String serverId) {
        if (servers == null) {
            servers = new LinkedHashMap<>();
        }
        return servers.computeIfAbsent(serverId, ignored -> new ArrayList<>());
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
    }
}
