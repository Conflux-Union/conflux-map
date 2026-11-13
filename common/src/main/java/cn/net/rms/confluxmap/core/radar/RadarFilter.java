package cn.net.rms.confluxmap.core.radar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies per-category on/off toggles, per-entity-type overrides, and a max-entity cap to a
 * freshly classified, unfiltered scan result. Pure data transform over {@link RadarEntry};
 * holds no reference to the game or the live config object, so it can run on any thread and
 * be unit tested directly.
 */
public final class RadarFilter {
    private final boolean showPlayers;
    private final boolean showHostile;
    private final boolean showPassive;
    private final boolean showOther;
    private final int maxEntities;
    private final Map<String, Boolean> typeVisibility;

    public RadarFilter(
        final boolean showPlayers,
        final boolean showHostile,
        final boolean showPassive,
        final boolean showOther,
        final int maxEntities,
        final Map<String, Boolean> typeVisibility
    ) {
        this.showPlayers = showPlayers;
        this.showHostile = showHostile;
        this.showPassive = showPassive;
        this.showOther = showOther;
        this.maxEntities = maxEntities;
        this.typeVisibility = typeVisibility;
    }

    public boolean allows(final RadarCategory category) {
        switch (category) {
            case PLAYER:
                return showPlayers;
            case HOSTILE:
                return showHostile;
            case PASSIVE:
                return showPassive;
            default:
                return showOther;
        }
    }

    /**
     * A per-type override wins over its category toggle, so a forced type stays visible even
     * with the category off (and vice versa); types without an override fall back to the toggle.
     */
    public boolean allows(final RadarCategory category, final String entityType) {
        if (entityType != null) {
            final Boolean override = typeVisibility.get(entityType);
            if (override != null) {
                return override;
            }
        }
        return allows(category);
    }

    /**
     * Drops entries whose effective visibility (override, else category toggle) is off, then —
     * if what remains still exceeds {@code maxEntities} — keeps only the entries nearest to
     * {@code (playerX, playerZ)}.
     */
    public List<RadarEntry> apply(final List<RadarEntry> raw, final double playerX, final double playerZ) {
        final List<RadarEntry> kept = new ArrayList<>(raw.size());
        for (final RadarEntry entry : raw) {
            if (allows(entry.category(), entry.entityType())) {
                kept.add(entry);
            }
        }
        if (kept.size() <= maxEntities) {
            return List.copyOf(kept);
        }
        kept.sort((a, b) -> Double.compare(distanceSq(a, playerX, playerZ), distanceSq(b, playerX, playerZ)));
        return List.copyOf(kept.subList(0, maxEntities));
    }

    private static double distanceSq(final RadarEntry entry, final double playerX, final double playerZ) {
        final double dx = entry.x() - playerX;
        final double dz = entry.z() - playerZ;
        return dx * dx + dz * dz;
    }
}
