package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.ui.world.WaypointHighlightState;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FullscreenMapLocationMenuTest {
    @Test
    void keepsTheExistingDisplayOrderWhenTeleportIsUnavailable() {
        assertEquals(List.of(
            FullscreenMapLocationMenu.Action.SET_WAYPOINT,
            FullscreenMapLocationMenu.Action.SHARE_LOCATION,
            FullscreenMapLocationMenu.Action.TELEPORT,
            FullscreenMapLocationMenu.Action.HIGHLIGHT
        ), FullscreenMapLocationMenu.actions(false, false, false));
    }

    @Test
    void putsTeleportFirstWhenItIsAvailable() {
        assertEquals(List.of(
            FullscreenMapLocationMenu.Action.TELEPORT,
            FullscreenMapLocationMenu.Action.SET_WAYPOINT,
            FullscreenMapLocationMenu.Action.SHARE_LOCATION,
            FullscreenMapLocationMenu.Action.HIGHLIGHT
        ), FullscreenMapLocationMenu.actions(true, false, false));
    }

    @Test
    void replacesLocationActionsWithWaypointActionsForAnExistingWaypoint() {
        assertEquals(List.of(
            FullscreenMapLocationMenu.Action.EDIT_WAYPOINT,
            FullscreenMapLocationMenu.Action.SHARE_WAYPOINT,
            FullscreenMapLocationMenu.Action.TELEPORT,
            FullscreenMapLocationMenu.Action.HIGHLIGHT_WAYPOINT
        ), FullscreenMapLocationMenu.actions(false, true, false));
        assertEquals(List.of(
            FullscreenMapLocationMenu.Action.TELEPORT,
            FullscreenMapLocationMenu.Action.EDIT_WAYPOINT,
            FullscreenMapLocationMenu.Action.SHARE_WAYPOINT,
            FullscreenMapLocationMenu.Action.HIGHLIGHT_WAYPOINT
        ), FullscreenMapLocationMenu.actions(true, true, false));
    }

    @Test
    void replacesHighlightWithClearOnlyForTheCurrentTarget() {
        assertEquals(List.of(
            FullscreenMapLocationMenu.Action.SET_WAYPOINT,
            FullscreenMapLocationMenu.Action.SHARE_LOCATION,
            FullscreenMapLocationMenu.Action.TELEPORT,
            FullscreenMapLocationMenu.Action.CLEAR_HIGHLIGHT
        ), FullscreenMapLocationMenu.actions(false, false, true));
        assertEquals(List.of(
            FullscreenMapLocationMenu.Action.EDIT_WAYPOINT,
            FullscreenMapLocationMenu.Action.SHARE_WAYPOINT,
            FullscreenMapLocationMenu.Action.TELEPORT,
            FullscreenMapLocationMenu.Action.CLEAR_HIGHLIGHT
        ), FullscreenMapLocationMenu.actions(false, true, true));
    }

    @Test
    void syntheticHighlightedLocationIsNotTreatedAsASavedWaypoint() {
        assertFalse(FullscreenMapLocationMenu.isSavedWaypoint(new WaypointRenderEntry(
            WaypointHighlightState.SELECTED_LOCATION_ID,
            "Selected location",
            DimensionId.OVERWORLD,
            10.5,
            64.0,
            20.5,
            0xFFFFFFFF,
            Waypoint.Type.NORMAL,
            WaypointRenderEntry.Source.LOCAL
        )));
        assertTrue(FullscreenMapLocationMenu.isSavedWaypoint(new WaypointRenderEntry(
            UUID.randomUUID(),
            "Saved waypoint",
            DimensionId.OVERWORLD,
            10.5,
            64.0,
            20.5,
            0xFFFFFFFF,
            Waypoint.Type.NORMAL,
            WaypointRenderEntry.Source.LOCAL
        )));
    }

    @Test
    void editActionOnlyDependsOnWaypointPermission() {
        assertTrue(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.EDIT_WAYPOINT, true, false, false, true
        ));
        assertFalse(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.EDIT_WAYPOINT, true, false, false, false
        ));
    }

    @Test
    void opensBesideTheCursorWithoutLeavingTheViewport() {
        final FullscreenMapLocationMenu.Bounds topLeft = FullscreenMapLocationMenu.place(20, 20, 320, 240);
        final FullscreenMapLocationMenu.Bounds bottomRight = FullscreenMapLocationMenu.place(315, 235, 320, 240);

        assertTrue(topLeft.x() > 20);
        assertTrue(topLeft.y() > 20);
        assertTrue(bottomRight.x() + bottomRight.width() < 315);
        assertTrue(bottomRight.y() + bottomRight.height() < 235);
        assertInsideViewport(topLeft, 320, 240);
        assertInsideViewport(bottomRight, 320, 240);
    }

    @Test
    void targetUsesTheAirBlockAboveTheEstimatedSurface() {
        final FullscreenMapLocationMenu.Target target = FullscreenMapLocationMenu.targetAt(
            -11.01, OptionalInt.of(72), 8.97
        );

        assertEquals(-12, target.blockX());
        assertEquals(8, target.blockZ());
        assertEquals(73, target.blockY().orElseThrow());
    }

    @Test
    void targetWithoutSurfaceDataHasNoEstimatedY() {
        final FullscreenMapLocationMenu.Target target = FullscreenMapLocationMenu.targetAt(
            10.0, OptionalInt.empty(), 20.0
        );

        assertTrue(target.blockY().isEmpty());
    }

    @Test
    void teleportAvailabilityDoesNotDependOnEstimatedHeight() {
        assertTrue(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.TELEPORT, true, false, true
        ));
        assertFalse(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.SET_WAYPOINT, true, false, true
        ));
        assertFalse(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.SHARE_LOCATION, true, false, true
        ));
        assertTrue(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.SHARE_WAYPOINT, true, false, true
        ));
    }

    @Test
    void targetFloorsNegativeCoordinatesWithoutCrossingTheOrigin() {
        final FullscreenMapLocationMenu.Target target = FullscreenMapLocationMenu.targetAt(
            -0.1, OptionalInt.of(10), -0.1
        );

        assertEquals(-1, target.blockX());
        assertEquals(-1, target.blockZ());
    }

    @Test
    void heightLookupUsesTheTopSurfaceLayerForEachDimensionKind() {
        assertEquals(
            MapLayer.SURFACE,
            FullscreenMapLocationMenu.topSurfaceLayer(LayerSelector.DimensionKind.SKY_LIT)
        );
        assertEquals(
            MapLayer.END_SURFACE,
            FullscreenMapLocationMenu.topSurfaceLayer(LayerSelector.DimensionKind.NO_SKY_NO_CEILING)
        );
        assertEquals(
            MapLayer.NETHER_CEILING,
            FullscreenMapLocationMenu.topSurfaceLayer(LayerSelector.DimensionKind.HAS_CEILING)
        );
    }

    private static void assertInsideViewport(
        final FullscreenMapLocationMenu.Bounds bounds,
        final int viewportWidth,
        final int viewportHeight
    ) {
        assertTrue(bounds.x() >= FullscreenMapLocationMenu.SCREEN_MARGIN);
        assertTrue(bounds.y() >= FullscreenMapLocationMenu.SCREEN_MARGIN);
        assertTrue(bounds.x() + bounds.width() <= viewportWidth - FullscreenMapLocationMenu.SCREEN_MARGIN);
        assertTrue(bounds.y() + bounds.height() <= viewportHeight - FullscreenMapLocationMenu.SCREEN_MARGIN);
    }
}
