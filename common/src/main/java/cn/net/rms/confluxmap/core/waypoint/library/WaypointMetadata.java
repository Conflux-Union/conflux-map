package cn.net.rms.confluxmap.core.waypoint.library;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Private client annotations, independent of the waypoint's original set. */
public record WaypointMetadata(String note, Set<String> tags, boolean starred, Set<UUID> collections,
                               Set<String> resourceItems, Map<String, Integer> resourceAmounts) {
    public static final int NOTE_LIMIT = 4096;
    public static final int TAG_LIMIT = 64;
    public static final WaypointMetadata EMPTY = new WaypointMetadata("", Set.of(), false, Set.of());

    public WaypointMetadata(final String note, final Set<String> tags, final boolean starred,
                            final Set<UUID> collections) {
        this(note, tags, starred, collections, Set.of());
    }

    public WaypointMetadata(String note, Set<String> tags, boolean starred, Set<UUID> collections,
                            Set<String> resourceItems) {
        this(note, tags, starred, collections, resourceItems, Map.of());
    }

    public WaypointMetadata {
        note = note == null ? "" : note;
        if (note.length() > NOTE_LIMIT) {
            throw new IllegalArgumentException("Note exceeds " + NOTE_LIMIT + " characters");
        }
        final Set<String> normalized = new LinkedHashSet<>();
        for (final String value : tags == null ? Set.<String>of() : tags) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            final String tag = value.trim();
            if (tag.length() > TAG_LIMIT || tag.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid tag");
            }
            normalized.add(WaypointTag.fromStored(tag).map(WaypointTag::id).orElse(tag));
        }
        final Set<String> resources = new LinkedHashSet<>();
        for (final String item : resourceItems == null ? Set.<String>of() : resourceItems) {
            if (item == null || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Invalid resource item id");
            }
            resources.add(item);
        }
        resourceItems = Collections.unmodifiableSet(resources);
        final Map<String, Integer> amounts = new LinkedHashMap<>();
        if (resourceAmounts != null) {
            for (String item : resources) {
                Integer amount = resourceAmounts.get(item);
                if (amount != null && amount >= 0) amounts.put(item, amount);
            }
        }
        resourceAmounts = Collections.unmodifiableMap(amounts);
        if (!resources.isEmpty()) {
            normalized.add(WaypointTag.RESOURCE.id());
        }
        tags = Collections.unmodifiableSet(normalized);
        collections = Collections.unmodifiableSet(new LinkedHashSet<>(collections == null ? Set.of() : collections));
        if (collections.contains(null)) {
            throw new IllegalArgumentException("Null collection id");
        }
    }

    public boolean matches(final String query) {
        final String normalized = query.trim().toLowerCase(java.util.Locale.ROOT);
        return note.toLowerCase(java.util.Locale.ROOT).contains(normalized)
            || tags.stream().anyMatch(tag -> WaypointTag.fromStored(tag).map(preset -> preset.matches(normalized))
                .orElseGet(() -> tag.toLowerCase(java.util.Locale.ROOT).contains(normalized)))
            || resourceItems.stream().anyMatch(item -> item.contains(normalized));
    }
}
