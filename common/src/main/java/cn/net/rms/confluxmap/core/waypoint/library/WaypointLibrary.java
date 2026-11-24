package cn.net.rms.confluxmap.core.waypoint.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Main-thread aggregate with one authoritative many-to-many membership relation. */
public final class WaypointLibrary {
    private final Map<UUID, WaypointMetadata> metadata = new LinkedHashMap<>();
    private final Map<UUID, WaypointCollection> collections = new LinkedHashMap<>();
    private final Set<UUID> sharedStars = new LinkedHashSet<>();
    private final Map<UUID, WaypointMetadata> sharedMetadata = new LinkedHashMap<>();

    public WaypointMetadata get(final UUID id, final boolean shared) {
        if (!shared) {
            return get(id);
        }
        final WaypointMetadata extra = sharedMetadata.getOrDefault(id, WaypointMetadata.EMPTY);
        return new WaypointMetadata(extra.note(), extra.tags(), sharedStars.contains(id),
            extra.collections(), extra.resourceItems(), extra.resourceAmounts());
    }

    public void put(final UUID id, final boolean shared, final WaypointMetadata value) {
        if (!shared) {
            put(id, value);
            return;
        }
        Objects.requireNonNull(id, "id");
        if (!collections.keySet().containsAll(value.collections())) {
            throw new IllegalArgumentException("Unknown collection");
        }
        // sharedStars remains the sole source for the star flag, including older documents.
        sharedMetadata.put(id, new WaypointMetadata(value.note(), value.tags(), false,
            value.collections(), value.resourceItems(), value.resourceAmounts()));
        setStarred(id, true, value.starred());
    }

    public Map<UUID, WaypointMetadata> sharedMetadata() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(sharedMetadata));
    }

    /** Shared stars stay private and use a separate namespace from local waypoint UUIDs. */
    public boolean isStarred(final UUID id, final boolean shared) {
        return shared ? sharedStars.contains(id) : get(id).starred();
    }

    public void setStarred(final UUID id, final boolean shared, final boolean value) {
        Objects.requireNonNull(id, "id");
        if (shared) {
            if (value) {
                sharedStars.add(id);
            } else {
                sharedStars.remove(id);
            }
            return;
        }
        final WaypointMetadata previous = get(id);
        put(id, new WaypointMetadata(previous.note(), previous.tags(), value, previous.collections(), previous.resourceItems(), previous.resourceAmounts()));
    }

    public Set<UUID> sharedStars() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(sharedStars));
    }

    public WaypointMetadata get(final UUID id) {
        return metadata.getOrDefault(id, WaypointMetadata.EMPTY);
    }

    public void put(final UUID id, final WaypointMetadata value) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(value, "value");
        if (!collections.keySet().containsAll(value.collections())) {
            throw new IllegalArgumentException("Unknown collection");
        }
        metadata.put(id, value);
    }

    public Map<UUID, WaypointMetadata> metadata() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public List<WaypointCollection> collections() {
        return List.copyOf(collections.values());
    }

    public void putCollection(final WaypointCollection value) {
        Objects.requireNonNull(value, "value");
        for (final WaypointCollection existing : collections.values()) {
            if (!existing.id().equals(value.id()) && existing.name().equalsIgnoreCase(value.name())) {
                throw new IllegalArgumentException("Duplicate collection name");
            }
        }
        collections.put(value.id(), value);
    }

    public void deleteCollection(final UUID id) {
        collections.remove(id);
        metadata.replaceAll((waypoint, extra) -> {
            final Set<UUID> next = new LinkedHashSet<>(extra.collections());
            next.remove(id);
            return new WaypointMetadata(extra.note(), extra.tags(), extra.starred(), next, extra.resourceItems(), extra.resourceAmounts());
        });
        sharedMetadata.replaceAll((waypoint, extra) -> {
            final Set<UUID> next = new LinkedHashSet<>(extra.collections());
            next.remove(id);
            return new WaypointMetadata(extra.note(), extra.tags(), false, next, extra.resourceItems(), extra.resourceAmounts());
        });
    }

    public void moveCollection(final UUID id, final int direction) {
        final List<WaypointCollection> ordered = new ArrayList<>(collections.values());
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id().equals(id)) {
                final int target = Math.max(0, Math.min(ordered.size() - 1, i + direction));
                Collections.swap(ordered, i, target);
                collections.clear();
                ordered.forEach(value -> collections.put(value.id(), value));
                return;
            }
        }
    }

    public void retainWaypoints(final Set<UUID> existing) {
        metadata.keySet().retainAll(existing);
    }
}
