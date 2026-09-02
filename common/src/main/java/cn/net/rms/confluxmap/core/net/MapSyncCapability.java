package cn.net.rms.confluxmap.core.net;

import java.util.EnumMap;
import java.util.Map;

/** Independently negotiable map-sync contracts. Capability versions are backward-compatible. */
public enum MapSyncCapability {
    FLAT_BASELINE(1, 1),
    LOAD_STATE(2, 1),
    MAP_INVALIDATION(3, 1),
    REGION_CORRECTION(4, 1),
    REGION_INVALIDATION(5, 1),
    SERVER_VIEW_DISTANCE(6, 1),
    SERVER_INSTANCE(7, 1),
    PLAYER_POSITIONS(8, 1);

    private final int id;
    private final int version;

    MapSyncCapability(final int id, final int version) {
        this.id = id;
        this.version = version;
    }

    public int id() {
        return id;
    }

    public int version() {
        return version;
    }

    public static MapSyncCapability fromId(final int id) {
        for (final MapSyncCapability capability : values()) {
            if (capability.id == id) {
                return capability;
            }
        }
        return null;
    }

    public static Map<MapSyncCapability, Integer> all() {
        final EnumMap<MapSyncCapability, Integer> result =
            new EnumMap<>(MapSyncCapability.class);
        for (final MapSyncCapability capability : values()) {
            result.put(capability, capability.version);
        }
        return result;
    }
}
