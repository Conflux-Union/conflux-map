package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WaypointWorldRendererDistanceTest {
    @Test
    void configuredDistanceIsNotCappedByVanillaViewDistance() {
        assertEquals(512.0, WaypointWorldRenderer.maxLabelDistance(512));
    }

    @Test
    void zeroConfiguredDistanceLeavesLabelsUnlimited() {
        assertEquals(
            Double.POSITIVE_INFINITY,
            WaypointWorldRenderer.maxLabelDistance(0)
        );
    }

    @Test
    void beamDistanceStillFollowsVanillaViewDistance() {
        assertEquals(128.0, WaypointWorldRenderer.beamVisibleDistance(8));
    }

    @Test
    void farLabelIsProjectedInsideTheCameraFarPlane() {
        final double projectionDistance = WaypointWorldRenderer.labelProjectionDistance(8);

        assertEquals(115.2, projectionDistance, 0.0001);
        assertEquals(
            projectionDistance,
            WaypointWorldRenderer.projectedLabelDistance(512.0, projectionDistance)
        );
    }

    @Test
    void nearbyLabelKeepsItsActualPosition() {
        assertEquals(
            64.0,
            WaypointWorldRenderer.projectedLabelDistance(
                64.0,
                WaypointWorldRenderer.labelProjectionDistance(8)
            )
        );
    }
}
