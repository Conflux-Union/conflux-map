package cn.net.rms.confluxmap.core.radar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Merges radar markers whose screen-space icon footprints overlap. The representative is chosen
 * by gameplay relevance before grouping, so a hostile marker is never hidden behind a friendly
 * mob or dropped item. Players remain independent because merging them would discard names.
 */
public final class RadarMarkerClusterer {
    /** One projected marker; {@code index} points back into the caller's render-data list. */
    public record Candidate(
        int index,
        float x,
        float y,
        RadarCategory category,
        String entityType,
        int entityId
    ) {
        public Candidate {
            Objects.requireNonNull(entityType, "entityType");
        }
    }

    /** The marker to draw and the total number of entities it represents. */
    public record Cluster(int representativeIndex, int count) {
    }

    private RadarMarkerClusterer() {
    }

    /**
     * Uses the first high-priority marker as a stable cluster anchor. Fixed anchors prevent a
     * chain of individually-close entities from collapsing into one group spanning a large area.
     */
    public static List<Cluster> cluster(final List<Candidate> candidates, final float mergeDistance) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (!(mergeDistance > 0f) || !Float.isFinite(mergeDistance)) {
            throw new IllegalArgumentException("mergeDistance must be finite and positive");
        }

        final List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(
            Comparator.comparingInt((Candidate candidate) -> priority(candidate.category())).reversed()
                .thenComparingInt(Candidate::entityId)
        );

        final List<MutableCluster> merged = new ArrayList<>();
        final List<Cluster> players = new ArrayList<>();
        for (final Candidate candidate : ordered) {
            if (candidate.category() == RadarCategory.PLAYER) {
                players.add(new Cluster(candidate.index(), 1));
                continue;
            }
            MutableCluster nearest = null;
            float nearestDistanceSq = Float.POSITIVE_INFINITY;
            for (final MutableCluster cluster : merged) {
                if (!candidate.entityType().equals(cluster.anchor.entityType())) {
                    continue;
                }
                final float dx = candidate.x() - cluster.anchor.x();
                final float dy = candidate.y() - cluster.anchor.y();
                if (Math.abs(dx) > mergeDistance || Math.abs(dy) > mergeDistance) {
                    continue;
                }
                final float distanceSq = dx * dx + dy * dy;
                if (distanceSq < nearestDistanceSq) {
                    nearest = cluster;
                    nearestDistanceSq = distanceSq;
                }
            }
            if (nearest == null) {
                merged.add(new MutableCluster(candidate));
            } else {
                nearest.count++;
            }
        }

        merged.sort(
            Comparator.comparingInt((MutableCluster cluster) -> priority(cluster.anchor.category()))
                .thenComparingInt(cluster -> cluster.anchor.entityId())
        );
        final List<Cluster> result = new ArrayList<>(merged.size() + players.size());
        for (final MutableCluster cluster : merged) {
            result.add(new Cluster(cluster.anchor.index(), cluster.count));
        }
        result.addAll(players);
        return List.copyOf(result);
    }

    private static int priority(final RadarCategory category) {
        switch (category) {
            case PLAYER:
                return 3;
            case HOSTILE:
                return 2;
            case OTHER:
                return 1;
            default:
                return 0;
        }
    }

    private static final class MutableCluster {
        private final Candidate anchor;
        private int count = 1;

        private MutableCluster(final Candidate anchor) {
            this.anchor = anchor;
        }
    }
}
