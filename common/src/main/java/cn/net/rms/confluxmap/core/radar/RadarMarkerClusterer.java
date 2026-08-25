package cn.net.rms.confluxmap.core.radar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * When enabled, merges matching radar markers whose screen-space icon footprints overlap, then
 * moves independent icons apart. When disabled, every marker keeps its projected position. The
 * representative is chosen by gameplay relevance before grouping, so a hostile marker keeps its
 * projected position ahead of a friendly mob or dropped item. Players remain independent because
 * merging them would discard names.
 */
public final class RadarMarkerClusterer {
    private static final int[][] DISPLACEMENT_DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
    };

    /**
     * One projected marker; {@code index} points back into the caller's render-data list.
     * {@code mergeKey} is an opaque value that identifies markers with the same visible kind.
     */
    public record Candidate(
        int index,
        float x,
        float y,
        RadarCategory category,
        Object mergeKey,
        int entityId
    ) {
        public Candidate {
            Objects.requireNonNull(mergeKey, "mergeKey");
        }
    }

    /** The marker to draw, its total entity count, and its collision-free screen position. */
    public record Cluster(int representativeIndex, int count, float x, float y) {
    }

    private RadarMarkerClusterer() {
    }

    /**
     * Uses the first high-priority marker as a stable cluster anchor. Fixed anchors prevent a
     * chain of individually-close entities from collapsing into one group spanning a large area.
     */
    public static List<Cluster> cluster(final List<Candidate> candidates, final float mergeDistance) {
        return cluster(candidates, mergeDistance, true);
    }

    /** Applies merging and overlap avoidance only when {@code mergeMatching} is enabled. */
    public static List<Cluster> cluster(
        final List<Candidate> candidates,
        final float mergeDistance,
        final boolean mergeMatching
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (!(mergeDistance > 0f) || !Float.isFinite(mergeDistance)) {
            throw new IllegalArgumentException("mergeDistance must be finite and positive");
        }
        if (!mergeMatching) {
            final List<Cluster> independent = new ArrayList<>(candidates.size());
            for (final Candidate candidate : candidates) {
                independent.add(new Cluster(candidate.index(), 1, candidate.x(), candidate.y()));
            }
            return List.copyOf(independent);
        }

        final List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(
            Comparator.comparingInt((Candidate candidate) -> priority(candidate.category())).reversed()
                .thenComparingInt(Candidate::entityId)
        );

        final List<MutableCluster> merged = new ArrayList<>();
        final List<MutableCluster> players = new ArrayList<>();
        for (final Candidate candidate : ordered) {
            if (candidate.category() == RadarCategory.PLAYER) {
                players.add(new MutableCluster(candidate));
                continue;
            }
            MutableCluster nearest = null;
            float nearestDistanceSq = Float.POSITIVE_INFINITY;
            for (final MutableCluster cluster : merged) {
                if (!candidate.mergeKey().equals(cluster.anchor.mergeKey())) {
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

        final List<MutableCluster> layoutOrder = new ArrayList<>(merged.size() + players.size());
        layoutOrder.addAll(merged);
        layoutOrder.addAll(players);
        layoutOrder.sort(
            Comparator.comparingInt((MutableCluster cluster) -> priority(cluster.anchor.category())).reversed()
                .thenComparingInt(cluster -> cluster.anchor.entityId())
        );
        final PlacementIndex placed = new PlacementIndex(mergeDistance);
        for (final MutableCluster cluster : layoutOrder) {
            placeWithoutOverlap(cluster, placed, mergeDistance);
            placed.add(cluster);
        }

        merged.sort(
            Comparator.comparingInt((MutableCluster cluster) -> priority(cluster.anchor.category()))
                .thenComparingInt(cluster -> cluster.anchor.entityId())
        );
        final List<Cluster> result = new ArrayList<>(merged.size() + players.size());
        for (final MutableCluster cluster : merged) {
            result.add(cluster.toResult());
        }
        for (final MutableCluster player : players) {
            result.add(player.toResult());
        }
        return List.copyOf(result);
    }

    private static void placeWithoutOverlap(
        final MutableCluster cluster,
        final PlacementIndex placed,
        final float iconSize
    ) {
        if (!placed.overlaps(cluster.x, cluster.y)) {
            return;
        }
        for (int ring = 1; ; ring++) {
            final float offset = ring * iconSize;
            for (final int[] direction : DISPLACEMENT_DIRECTIONS) {
                final float x = cluster.anchor.x() + direction[0] * offset;
                final float y = cluster.anchor.y() + direction[1] * offset;
                if (!placed.overlaps(x, y)) {
                    cluster.x = x;
                    cluster.y = y;
                    return;
                }
            }
        }
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
        private float x;
        private float y;

        private MutableCluster(final Candidate anchor) {
            this.anchor = anchor;
            this.x = anchor.x();
            this.y = anchor.y();
        }

        private Cluster toResult() {
            return new Cluster(anchor.index(), count, x, y);
        }
    }

    private static final class PlacementIndex {
        private final float cellSize;
        private final Map<Long, List<MutableCluster>> cells = new HashMap<>();

        private PlacementIndex(final float cellSize) {
            this.cellSize = cellSize;
        }

        private void add(final MutableCluster cluster) {
            cells.computeIfAbsent(key(cell(cluster.x), cell(cluster.y)), ignored -> new ArrayList<>())
                .add(cluster);
        }

        private boolean overlaps(final float x, final float y) {
            final int cellX = cell(x);
            final int cellY = cell(y);
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    final List<MutableCluster> nearby = cells.get(key(
                        cellX + offsetX, cellY + offsetY
                    ));
                    if (nearby == null) {
                        continue;
                    }
                    for (final MutableCluster cluster : nearby) {
                        if (Math.abs(x - cluster.x) < cellSize
                            && Math.abs(y - cluster.y) < cellSize) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private int cell(final float coordinate) {
            return (int) Math.floor(coordinate / cellSize);
        }

        private static long key(final int x, final int y) {
            return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
        }
    }
}
