package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RadarMarkerClustererTest {
    @Test
    void mergesOverlappingMarkersWithTheSameMergeKey() {
        final List<RadarMarkerClusterer.Candidate> candidates = List.of(
            candidate(0, 10f, 10f, RadarCategory.PASSIVE, "minecraft:sheep", 30),
            candidate(1, 12f, 10f, RadarCategory.PASSIVE, "minecraft:sheep", 20),
            candidate(2, 11f, 12f, RadarCategory.PASSIVE, "minecraft:sheep", 10)
        );

        assertEquals(
            List.of(new RadarMarkerClusterer.Cluster(2, 3, 11f, 12f)),
            RadarMarkerClusterer.cluster(candidates, 10f)
        );
    }

    @Test
    void keepsEveryMarkerAtItsProjectedPositionWhenMergingIsDisabled() {
        final List<RadarMarkerClusterer.Candidate> candidates = List.of(
            candidate(0, 10f, 10f, RadarCategory.PASSIVE, "minecraft:sheep", 30),
            candidate(1, 12f, 10f, RadarCategory.PASSIVE, "minecraft:sheep", 20),
            candidate(2, 11f, 12f, RadarCategory.PASSIVE, "minecraft:sheep", 10)
        );

        assertEquals(
            List.of(
                new RadarMarkerClusterer.Cluster(0, 1, 10f, 10f),
                new RadarMarkerClusterer.Cluster(1, 1, 12f, 10f),
                new RadarMarkerClusterer.Cluster(2, 1, 11f, 12f)
            ),
            RadarMarkerClusterer.cluster(candidates, 10f, false)
        );
    }

    @Test
    void keepsDifferentMergeKeysIndependentAndMovesTheirIconsApart() {
        final List<RadarMarkerClusterer.Candidate> candidates = List.of(
            candidate(0, 10f, 10f, RadarCategory.OTHER, "minecraft:item:iron_ingot", 20),
            candidate(1, 11f, 11f, RadarCategory.OTHER, "minecraft:item:diamond", 10)
        );

        final List<RadarMarkerClusterer.Cluster> clusters =
            RadarMarkerClusterer.cluster(candidates, 10f);

        assertEquals(2, clusters.size());
        assertEquals(1, clusters.get(0).representativeIndex());
        assertEquals(0, clusters.get(1).representativeIndex());
        assertNoIconOverlap(clusters.get(0), clusters.get(1), 10f);
        assertTrue(clusters.get(1).x() != 10f || clusters.get(1).y() != 10f);
    }

    @Test
    void laysOutOverlappingMarkersDeterministically() {
        final List<RadarMarkerClusterer.Candidate> candidates = List.of(
            candidate(0, 20f, 20f, RadarCategory.PASSIVE, "minecraft:sheep", 30),
            candidate(1, 20f, 20f, RadarCategory.HOSTILE, "minecraft:zombie", 20),
            candidate(2, 20f, 20f, RadarCategory.OTHER, "minecraft:boat", 10)
        );

        final List<RadarMarkerClusterer.Cluster> first =
            RadarMarkerClusterer.cluster(candidates, 10f);
        final List<RadarMarkerClusterer.Cluster> second =
            RadarMarkerClusterer.cluster(candidates, 10f);

        assertEquals(first, second);
        assertEquals(20f, clusterFor(first, 1).x());
        assertEquals(20f, clusterFor(first, 1).y());
        assertNoIconOverlap(first.get(0), first.get(1), 10f);
        assertNoIconOverlap(first.get(0), first.get(2), 10f);
        assertNoIconOverlap(first.get(1), first.get(2), 10f);
    }

    @Test
    void leavesPlayersAndSeparatedMarkersIndependent() {
        final List<RadarMarkerClusterer.Candidate> candidates = List.of(
            candidate(0, 0f, 0f, RadarCategory.PLAYER, "minecraft:player", 10),
            candidate(1, 1f, 1f, RadarCategory.PLAYER, "minecraft:player", 11),
            candidate(2, 20f, 20f, RadarCategory.PASSIVE, "minecraft:sheep", 12),
            candidate(3, 40f, 40f, RadarCategory.PASSIVE, "minecraft:sheep", 13)
        );

        assertEquals(
            List.of(
                new RadarMarkerClusterer.Cluster(2, 1, 20f, 20f),
                new RadarMarkerClusterer.Cluster(3, 1, 40f, 40f),
                new RadarMarkerClusterer.Cluster(0, 1, 0f, 0f),
                new RadarMarkerClusterer.Cluster(1, 1, 11f, 1f)
            ),
            RadarMarkerClusterer.cluster(candidates, 10f)
        );
    }

    private static RadarMarkerClusterer.Candidate candidate(
        final int index,
        final float x,
        final float y,
        final RadarCategory category,
        final String mergeKey,
        final int entityId
    ) {
        return new RadarMarkerClusterer.Candidate(index, x, y, category, mergeKey, entityId);
    }

    private static RadarMarkerClusterer.Cluster clusterFor(
        final List<RadarMarkerClusterer.Cluster> clusters,
        final int representativeIndex
    ) {
        return clusters.stream()
            .filter(cluster -> cluster.representativeIndex() == representativeIndex)
            .findFirst()
            .orElseThrow();
    }

    private static void assertNoIconOverlap(
        final RadarMarkerClusterer.Cluster first,
        final RadarMarkerClusterer.Cluster second,
        final float iconSize
    ) {
        assertTrue(
            Math.abs(first.x() - second.x()) >= iconSize
                || Math.abs(first.y() - second.y()) >= iconSize,
            "icon footprints overlap"
        );
    }
}
