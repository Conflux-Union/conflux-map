package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.radar.RadarCategory;
import cn.net.rms.confluxmap.core.radar.RadarMarkerClusterer;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RadarMarkerMergePolicyTest {
    @Test
    void enabledMergePolicyCollapsesMatchingMarkers() {
        final ConfluxConfig config = new ConfluxConfig();
        final List<RadarMarkerClusterer.Candidate> candidates = List.of(
            candidate(0, 10f, 10f, 2),
            candidate(1, 10f, 10f, 1)
        );

        assertEquals(
            List.of(new RadarMarkerClusterer.Cluster(1, 2, 10f, 10f)),
            RadarMarkerRenderer.clusterCandidates(candidates, config)
        );
    }

    @Test
    void disabledMergePolicyKeepsMatchingMarkersIndependent() {
        final ConfluxConfig config = new ConfluxConfig();
        config.radarMergeEnabled = false;
        final List<RadarMarkerClusterer.Candidate> candidates = List.of(
            candidate(0, 10f, 10f, 2),
            candidate(1, 10f, 10f, 1)
        );

        final List<RadarMarkerClusterer.Cluster> clusters =
            RadarMarkerRenderer.clusterCandidates(candidates, config);

        assertEquals(
            List.of(
                new RadarMarkerClusterer.Cluster(0, 1, 10f, 10f),
                new RadarMarkerClusterer.Cluster(1, 1, 10f, 10f)
            ),
            clusters
        );
    }

    private static RadarMarkerClusterer.Candidate candidate(
        final int index,
        final float x,
        final float y,
        final int entityId
    ) {
        return new RadarMarkerClusterer.Candidate(
            index, x, y, RadarCategory.PASSIVE, "minecraft:sheep", entityId
        );
    }
}
