package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.ui.world.WaypointHighlightState;
import cn.net.rms.confluxmap.mc.world.DimensionLayerPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FullscreenMapLocationMenuTest {
    @Test
    void locationMenuCoversMapOverlaysWithoutSuppressingThem() throws IOException {
        final String source = Files.readString(projectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
        )).replace("\r\n", "\n");
        final String occlusion = between(
            source,
            "private boolean mapOverlayIntersectsUi(\n        final float left",
            "    private static boolean intersects"
        );

        assertFalse(
            occlusion.contains("locationMenuBounds"),
            "the location menu must cover map overlays instead of suppressing them"
        );
        assertTrue(
            occlusion.contains("locationActionTooltips.containsKey(widget)"),
            "location-menu buttons must be excluded from generic widget avoidance"
        );

        final String render = between(
            source,
            "protected void renderContents(",
            "    private void drawExportSelection"
        );
        final int menu = render.indexOf("drawLocationMenu(draw);");
        assertTrue(render.indexOf("drawStructures(draw, mouseX, mouseY);") < menu);
        assertTrue(render.indexOf("drawRadar(draw, tickDelta, mouseX, mouseY);") < menu);
        assertTrue(render.indexOf("drawWaypoints(draw, mouseX, mouseY, radarObserver);") < menu);
    }

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
            FullscreenMapLocationMenu.Action.DELETE_WAYPOINT,
            FullscreenMapLocationMenu.Action.SHARE_WAYPOINT,
            FullscreenMapLocationMenu.Action.TELEPORT,
            FullscreenMapLocationMenu.Action.HIGHLIGHT_WAYPOINT
        ), FullscreenMapLocationMenu.actions(false, true, false));
        assertEquals(List.of(
            FullscreenMapLocationMenu.Action.TELEPORT,
            FullscreenMapLocationMenu.Action.EDIT_WAYPOINT,
            FullscreenMapLocationMenu.Action.DELETE_WAYPOINT,
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
            FullscreenMapLocationMenu.Action.DELETE_WAYPOINT,
            FullscreenMapLocationMenu.Action.SHARE_WAYPOINT,
            FullscreenMapLocationMenu.Action.TELEPORT,
            FullscreenMapLocationMenu.Action.CLEAR_HIGHLIGHT
        ), FullscreenMapLocationMenu.actions(false, true, true));
    }

    @Test
    void usesPlayerHighlightActionsWhenRightClickingAPlayer() {
        assertEquals(List.of(
            FullscreenMapLocationMenu.Action.SET_WAYPOINT,
            FullscreenMapLocationMenu.Action.SHARE_LOCATION,
            FullscreenMapLocationMenu.Action.TELEPORT,
            FullscreenMapLocationMenu.Action.HIGHLIGHT_PLAYER
        ), FullscreenMapLocationMenu.actions(false, false, false, true));
        assertEquals(List.of(
            FullscreenMapLocationMenu.Action.SET_WAYPOINT,
            FullscreenMapLocationMenu.Action.SHARE_LOCATION,
            FullscreenMapLocationMenu.Action.TELEPORT,
            FullscreenMapLocationMenu.Action.CLEAR_PLAYER_HIGHLIGHT
        ), FullscreenMapLocationMenu.actions(false, false, true, true));
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
            FullscreenMapLocationMenu.Action.EDIT_WAYPOINT, true, false, false, true, false
        ));
        assertFalse(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.EDIT_WAYPOINT, true, false, false, false, true
        ));
    }

    @Test
    void deleteActionOnlyDependsOnWaypointPermission() {
        assertTrue(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.DELETE_WAYPOINT, true, false, false, false, true
        ));
        assertFalse(FullscreenMapLocationMenu.actionEnabled(
            FullscreenMapLocationMenu.Action.DELETE_WAYPOINT, true, false, false, true, false
        ));
    }

    @Test
    void opensBesideTheCursorWithoutLeavingTheViewport() {
        final FullscreenMapLocationMenu.Bounds topLeft = FullscreenMapLocationMenu.place(
            20, 20, 320, 240, 4
        );
        final FullscreenMapLocationMenu.Bounds bottomRight = FullscreenMapLocationMenu.place(
            315, 235, 320, 240, 5
        );

        assertTrue(topLeft.x() > 20);
        assertTrue(topLeft.y() > 20);
        assertTrue(bottomRight.x() + bottomRight.width() < 315);
        assertTrue(bottomRight.y() + bottomRight.height() < 235);
        assertInsideViewport(topLeft, 320, 240);
        assertInsideViewport(bottomRight, 320, 240);
    }

    @Test
    void growsThePanelForTheWaypointDeleteAction() {
        final FullscreenMapLocationMenu.Bounds location = FullscreenMapLocationMenu.place(
            20, 20, 320, 240, 4
        );
        final FullscreenMapLocationMenu.Bounds waypoint = FullscreenMapLocationMenu.place(
            20, 20, 320, 240, 5
        );

        assertEquals(
            FullscreenMapLocationMenu.BUTTON_HEIGHT + FullscreenMapLocationMenu.BUTTON_GAP,
            waypoint.height() - location.height()
        );
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
        assertFalse(target.groundKnown());
    }

    @Test
    void knownVoidTargetHasNoYButIsAGroundedAnswer() {
        final FullscreenMapLocationMenu.Target target = FullscreenMapLocationMenu.targetAt(
            10.0, new ColumnStore.SurfaceLookup(true, OptionalInt.empty()), 20.0
        );

        assertTrue(target.blockY().isEmpty());
        assertTrue(target.groundKnown());
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
    void hoveredStructureSuppliesTheExactRightClickCoordinates() {
        final FullscreenMapLocationMenu.Point point = FullscreenMapLocationMenu.pointAt(
            31.75,
            47.25,
            new StructureIndex.Marker(
                StructureIndex.StructureType.VILLAGE,
                32,
                48,
                2,
                StructureIndex.State.CANDIDATE
            )
        );

        assertEquals(32, point.blockX());
        assertEquals(48, point.blockZ());
    }

    @Test
    void emptyMapRightClickKeepsTheCursorCoordinates() {
        final FullscreenMapLocationMenu.Point point = FullscreenMapLocationMenu.pointAt(
            -0.1, 8.97, null
        );

        assertEquals(-1, point.blockX());
        assertEquals(8, point.blockZ());
    }

    @Test
    void heightLookupUsesTheTopSurfaceLayerForEachDimensionKind() {
        assertEquals(
            MapLayer.SURFACE,
            FullscreenMapLocationMenu.topSurfaceLayer(DimensionLayerPolicy.DimensionKind.SKY_LIT)
        );
        assertEquals(
            MapLayer.END_SURFACE,
            FullscreenMapLocationMenu.topSurfaceLayer(DimensionLayerPolicy.DimensionKind.NO_SKY_NO_CEILING)
        );
        assertEquals(
            MapLayer.NETHER_CEILING,
            FullscreenMapLocationMenu.topSurfaceLayer(DimensionLayerPolicy.DimensionKind.HAS_CEILING)
        );
    }

    @Test
    void splitMapScreensHostTheRightClickLocationMenu() throws IOException {
        final Path root = projectRoot();
        final String pane = Files.readString(root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/SplitMapPane.java"
        )).replace("\r\n", "\n");
        assertTrue(
            pane.contains("map.openEmbeddedLocationMenu(mouseX, mouseY, layout)"),
            "the split map pane must capture menu targets through the embedded map"
        );
        assertTrue(
            pane.contains("map.runLocationAction(action, target, waypoint, playerId, host)"),
            "the split map pane must run actions on the embedded map with itself as return screen"
        );
        assertTrue(
            pane.contains("map.locationMenuButtonSpecs("),
            "the split map pane must reuse the fullscreen menu's button specs"
        );

        for (final String name : new String[] {
            "StructureSearchScreen", "StructureCandidateScreen", "BiomeCandidateScreen"
        }) {
            final String source = Files.readString(root.resolve(
                "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/" + name + ".java"
            )).replace("\r\n", "\n");
            assertTrue(
                source.contains("mapPane.addMenuButtons(this)"),
                name + " must re-add the menu buttons when its widgets are rebuilt"
            );
            final int outsideGuard = source.indexOf("mapPane.menuClickedOutside(this,");
            final int widgetDispatch = source.indexOf("super.mouseClicked");
            final int consume = source.indexOf("mapPane.consumePendingMenuAction(this)");
            assertTrue(
                outsideGuard >= 0 && widgetDispatch > outsideGuard && consume > widgetDispatch,
                name + " must route clicks around the open menu before its widget dispatch"
            );
        }
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

    private static String between(final String source, final String start, final String end) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex, "expected source section must exist");
        return source.substring(startIndex, endIndex);
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("common.gradle"))
                && Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Conflux Map project root");
    }
}
