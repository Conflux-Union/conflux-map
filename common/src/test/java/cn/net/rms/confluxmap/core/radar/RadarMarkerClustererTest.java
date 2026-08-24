package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RadarMarkerClustererTest {
    @Test
    void mergesOverlappingMarkersOfTheSameEntityType() {
        final List<RadarMarkerClusterer.Candidate> candidates = List.of(
            candidate(0, 10f, 10f, RadarCategory.PASSIVE, "minecraft:sheep", 30),
            candidate(1, 12f, 10f, RadarCategory.PASSIVE, "minecraft:sheep", 20),
            candidate(2, 11f, 12f, RadarCategory.PASSIVE, "minecraft:sheep", 10)
        );

        assertEquals(
            List.of(new RadarMarkerClusterer.Cluster(2, 3)),
            RadarMarkerClusterer.cluster(candidates, 10f)
        );
    }

    @Test
    void keepsOverlappingMarkersOfDifferentEntityTypesIndependent() {
        final List<RadarMarkerClusterer.Candidate> candidates = List.of(
            candidate(0, 10f, 10f, RadarCategory.PASSIVE, "minecraft:sheep", 20),
            candidate(1, 11f, 11f, RadarCategory.PASSIVE, "minecraft:zombie_horse", 10)
        );

        assertEquals(
            List.of(
                new RadarMarkerClusterer.Cluster(1, 1),
                new RadarMarkerClusterer.Cluster(0, 1)
            ),
            RadarMarkerClusterer.cluster(candidates, 10f)
        );
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
                new RadarMarkerClusterer.Cluster(2, 1),
                new RadarMarkerClusterer.Cluster(3, 1),
                new RadarMarkerClusterer.Cluster(0, 1),
                new RadarMarkerClusterer.Cluster(1, 1)
            ),
            RadarMarkerClusterer.cluster(candidates, 10f)
        );
    }

    private static RadarMarkerClusterer.Candidate candidate(
        final int index,
        final float x,
        final float y,
        final RadarCategory category,
        final String entityType,
        final int entityId
    ) {
        return new RadarMarkerClusterer.Candidate(index, x, y, category, entityType, entityId);
    }
}
