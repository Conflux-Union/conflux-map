package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class RadarMarkerClustererTest {
    @Test
    void mergesOverlappingMarkersAndKeepsTheHighestPriorityRepresentative() {
        final List<RadarMarkerClusterer.Candidate> candidates = List.of(
            candidate(0, 10f, 10f, RadarCategory.PASSIVE, 30),
            candidate(1, 12f, 10f, RadarCategory.OTHER, 20),
            candidate(2, 11f, 12f, RadarCategory.HOSTILE, 10)
        );

        assertEquals(
            List.of(new RadarMarkerClusterer.Cluster(2, 3)),
            RadarMarkerClusterer.cluster(candidates, 10f)
        );
    }

    @Test
    void leavesPlayersAndSeparatedMarkersIndependent() {
        final List<RadarMarkerClusterer.Candidate> candidates = List.of(
            candidate(0, 0f, 0f, RadarCategory.PLAYER, 10),
            candidate(1, 1f, 1f, RadarCategory.PLAYER, 11),
            candidate(2, 20f, 20f, RadarCategory.PASSIVE, 12),
            candidate(3, 40f, 40f, RadarCategory.PASSIVE, 13)
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
        final int entityId
    ) {
        return new RadarMarkerClusterer.Candidate(index, x, y, category, entityId);
    }
}
