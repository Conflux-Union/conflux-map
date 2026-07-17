package cn.net.rms.confluxmap.core.radar;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies the per-category on/off toggles and a max-entity cap to a freshly classified,
 * unfiltered scan result. Pure data transform over {@link RadarEntry}; holds no reference to
 * the game or the live config object, so it can run on any thread and be unit tested directly.
 */
public final class RadarFilter {
    private final boolean showPlayers;
    private final boolean showHostile;
    private final boolean showPassive;
    private final boolean showOther;
    private final int maxEntities;

    public RadarFilter(
        final boolean showPlayers,
        final boolean showHostile,
        final boolean showPassive,
        final boolean showOther,
        final int maxEntities
    ) {
        this.showPlayers = showPlayers;
        this.showHostile = showHostile;
        this.showPassive = showPassive;
        this.showOther = showOther;
        this.maxEntities = maxEntities;
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
     * Drops entries whose category is toggled off, then — if what remains still exceeds
     * {@code maxEntities} — keeps only the entries nearest to {@code (playerX, playerZ)}.
     */
    public List<RadarEntry> apply(final List<RadarEntry> raw, final double playerX, final double playerZ) {
        final List<RadarEntry> kept = new ArrayList<>(raw.size());
        for (final RadarEntry entry : raw) {
            if (allows(entry.category())) {
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
