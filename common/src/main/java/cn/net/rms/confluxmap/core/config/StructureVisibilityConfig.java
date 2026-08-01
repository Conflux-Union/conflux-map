package cn.net.rms.confluxmap.core.config;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Per-Minecraft-version, per-dimension visibility choices for predicted structure markers. */
public final class StructureVisibilityConfig {
    private Map<String, Set<String>> hiddenByProfile = new TreeMap<>();

    public boolean isVisible(
        final int mcVersion,
        final DimensionId dimension,
        final StructureIndex.StructureType type
    ) {
        final Set<String> hidden = hiddenByProfile.get(profileKey(mcVersion, dimension));
        return hidden == null || !hidden.contains(type.id());
    }

    public void setVisible(
        final int mcVersion,
        final DimensionId dimension,
        final StructureIndex.StructureType type,
        final boolean visible
    ) {
        final String profile = profileKey(mcVersion, dimension);
        if (visible) {
            final Set<String> hidden = hiddenByProfile.get(profile);
            if (hidden == null) {
                return;
            }
            hidden.remove(type.id());
            if (hidden.isEmpty()) {
                hiddenByProfile.remove(profile);
            }
            return;
        }
        hiddenByProfile.computeIfAbsent(profile, ignored -> new TreeSet<>()).add(type.id());
    }

    public EnumSet<StructureIndex.StructureType> visibleTypes(
        final int mcVersion,
        final DimensionId dimension,
        final Set<StructureIndex.StructureType> available
    ) {
        final EnumSet<StructureIndex.StructureType> visible =
            EnumSet.noneOf(StructureIndex.StructureType.class);
        for (final StructureIndex.StructureType type : available) {
            if (isVisible(mcVersion, dimension, type)) {
                visible.add(type);
            }
        }
        return visible;
    }

    public StructureVisibilityConfig copy() {
        final StructureVisibilityConfig copy = new StructureVisibilityConfig();
        copy.hiddenByProfile.clear();
        for (final Map.Entry<String, Set<String>> entry : hiddenByProfile.entrySet()) {
            copy.hiddenByProfile.put(entry.getKey(), new TreeSet<>(entry.getValue()));
        }
        return copy;
    }

    /** Repairs hand-edited JSON without discarding unknown structure ids needed by newer builds. */
    public void normalize() {
        if (hiddenByProfile == null) {
            hiddenByProfile = new TreeMap<>();
            return;
        }
        final Map<String, Set<String>> normalized = new TreeMap<>();
        for (final Map.Entry<String, Set<String>> entry : hiddenByProfile.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            final Set<String> ids = new TreeSet<>();
            for (final String id : entry.getValue()) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                normalized.put(entry.getKey(), ids);
            }
        }
        hiddenByProfile = normalized;
    }

    private static String profileKey(final int mcVersion, final DimensionId dimension) {
        return mcVersion + "|" + dimension;
    }
}
