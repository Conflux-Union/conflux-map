package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import java.util.List;
import java.util.UUID;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

final class WaypointWorldRendererDistanceTest {
    @Test
    void targetedWaypointBypassesTheConfiguredDistance() {
        final UUID waypointId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final WaypointRenderEntry waypoint = new WaypointRenderEntry(
            waypointId,
            "Distant target",
            DimensionId.OVERWORLD,
            0.0,
            -1.5,
            2_000.0,
            0xFFFFFFFF,
            Waypoint.Type.NORMAL,
            WaypointRenderEntry.Source.LOCAL
        );

        final WaypointWorldRenderer.LabelSelection selection = WaypointWorldRenderer.selectLabels(
            List.of(waypoint),
            0.0f,
            0.0f,
            Vec3d.ZERO,
            0.0,
            0.0,
            0.0,
            1_000.0,
            new WaypointHighlightState(),
            DimensionId.OVERWORLD
        );

        assertEquals(waypointId, selection.targetedWaypointId());
        assertEquals(List.of(waypoint), selection.candidates().stream()
            .map(WaypointWorldRenderer.LabelCandidate::waypoint)
            .toList());
    }

    @Test
    void untargetedWaypointBeyondTheConfiguredDistanceIsNotSelected() {
        final WaypointRenderEntry waypoint = new WaypointRenderEntry(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "Distant off-axis",
            DimensionId.OVERWORLD,
            2_000.0,
            0.0,
            0.0,
            0xFFFFFFFF,
            Waypoint.Type.NORMAL,
            WaypointRenderEntry.Source.LOCAL
        );

        final WaypointWorldRenderer.LabelSelection selection = WaypointWorldRenderer.selectLabels(
            List.of(waypoint),
            0.0f,
            0.0f,
            Vec3d.ZERO,
            0.0,
            0.0,
            0.0,
            1_000.0,
            new WaypointHighlightState(),
            DimensionId.OVERWORLD
        );

        assertNull(selection.targetedWaypointId());
        assertEquals(List.of(), selection.candidates());
    }

    @Test
    void highlightedWaypointBypassesTheConfiguredDistance() {
        final WaypointRenderEntry waypoint = new WaypointRenderEntry(
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            "Distant highlight",
            DimensionId.OVERWORLD,
            2_000.0,
            64.0,
            0.0,
            0xFFFFFFFF,
            Waypoint.Type.NORMAL,
            WaypointRenderEntry.Source.LOCAL
        );
        final WaypointHighlightState highlightState = new WaypointHighlightState();
        highlightState.selectWaypoint(waypoint, DimensionId.OVERWORLD);

        final WaypointWorldRenderer.LabelSelection selection = WaypointWorldRenderer.selectLabels(
            List.of(waypoint),
            0.0f,
            0.0f,
            Vec3d.ZERO,
            0.0,
            0.0,
            0.0,
            1_000.0,
            highlightState,
            DimensionId.OVERWORLD
        );

        assertNull(selection.targetedWaypointId());
        assertTrue(selection.candidates().get(0).selected());
        assertEquals(waypoint, selection.candidates().get(0).waypoint());
    }

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
