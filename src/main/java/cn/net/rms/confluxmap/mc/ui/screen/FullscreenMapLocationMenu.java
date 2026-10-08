package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.world.WaypointHighlightState;
import cn.net.rms.confluxmap.mc.world.DimensionLayerPolicy;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/** Layout and captured map target for the fullscreen map's right-click location menu. */
final class FullscreenMapLocationMenu {
    static final int SCREEN_MARGIN = 4;
    static final int PANEL_WIDTH = 140;
    static final int PANEL_PADDING = 3;
    static final int BUTTON_HEIGHT = 20;
    static final int BUTTON_GAP = 2;
    static final int CURSOR_GAP = 2;
    private static final int PANEL_BACKGROUND = 0xF0181822;
    private static final int PANEL_BORDER = 0xFF9A9AA8;

    enum Action {
        SET_WAYPOINT("confluxmap.map.location_menu.set_waypoint"),
        EDIT_WAYPOINT("confluxmap.map.location_menu.edit_waypoint"),
        DELETE_WAYPOINT("confluxmap.map.location_menu.delete_waypoint"),
        SHARE_LOCATION("confluxmap.map.location_menu.share_location"),
        SHARE_WAYPOINT("confluxmap.map.location_menu.share_waypoint"),
        TELEPORT("confluxmap.map.location_menu.teleport"),
        HIGHLIGHT("confluxmap.map.location_menu.highlight"),
        HIGHLIGHT_WAYPOINT("confluxmap.map.location_menu.highlight_waypoint"),
        CLEAR_HIGHLIGHT("confluxmap.map.location_menu.clear_highlight"),
        HIGHLIGHT_PLAYER("confluxmap.map.location_menu.highlight_player"),
        CLEAR_PLAYER_HIGHLIGHT("confluxmap.map.location_menu.clear_player_highlight");

        private final String translationKey;

        Action(final String translationKey) {
            this.translationKey = translationKey;
        }

        String translationKey() {
            return translationKey;
        }
    }

    private FullscreenMapLocationMenu() {
    }

    static List<Action> actions(
        final boolean teleportCommandAvailable,
        final boolean existingWaypoint,
        final boolean currentTargetHighlighted
    ) {
        return actions(
            teleportCommandAvailable, existingWaypoint, currentTargetHighlighted, false
        );
    }

    static List<Action> actions(
        final boolean teleportCommandAvailable,
        final boolean existingWaypoint,
        final boolean currentTargetHighlighted,
        final boolean playerTarget
    ) {
        final Action edit = existingWaypoint ? Action.EDIT_WAYPOINT : Action.SET_WAYPOINT;
        final Action share = existingWaypoint ? Action.SHARE_WAYPOINT : Action.SHARE_LOCATION;
        final Action highlight = playerTarget
            ? currentTargetHighlighted ? Action.CLEAR_PLAYER_HIGHLIGHT : Action.HIGHLIGHT_PLAYER
            : currentTargetHighlighted
                ? Action.CLEAR_HIGHLIGHT
                : existingWaypoint ? Action.HIGHLIGHT_WAYPOINT : Action.HIGHLIGHT;
        if (existingWaypoint) {
            return teleportCommandAvailable
                ? List.of(Action.TELEPORT, Action.EDIT_WAYPOINT, Action.DELETE_WAYPOINT, share, highlight)
                : List.of(Action.EDIT_WAYPOINT, Action.DELETE_WAYPOINT, share, Action.TELEPORT, highlight);
        }
        return teleportCommandAvailable
            ? List.of(Action.TELEPORT, edit, share, highlight)
            : List.of(edit, share, Action.TELEPORT, highlight);
    }

    static boolean isSavedWaypoint(final WaypointRenderEntry waypoint) {
        return waypoint != null
            && !WaypointHighlightState.SELECTED_LOCATION_ID.equals(waypoint.id());
    }

    static boolean actionEnabled(
        final Action action,
        final boolean playerPresent,
        final boolean estimatedHeightKnown,
        final boolean teleportCommandAvailable
    ) {
        if (!playerPresent) {
            return false;
        }
        if (action == Action.DELETE_WAYPOINT) {
            return false;
        }
        if (action == Action.HIGHLIGHT
            || action == Action.HIGHLIGHT_WAYPOINT
            || action == Action.CLEAR_HIGHLIGHT
            || action == Action.HIGHLIGHT_PLAYER
            || action == Action.CLEAR_PLAYER_HIGHLIGHT) {
            return true;
        }
        if (action == Action.SHARE_WAYPOINT) {
            return true;
        }
        return action == Action.TELEPORT ? teleportCommandAvailable : estimatedHeightKnown;
    }

    static boolean actionEnabled(
        final Action action,
        final boolean playerPresent,
        final boolean estimatedHeightKnown,
        final boolean teleportCommandAvailable,
        final boolean waypointEditable
    ) {
        if (action == Action.EDIT_WAYPOINT) {
            return playerPresent && waypointEditable;
        }
        return actionEnabled(action, playerPresent, estimatedHeightKnown, teleportCommandAvailable);
    }

    static boolean actionEnabled(
        final Action action,
        final boolean playerPresent,
        final boolean estimatedHeightKnown,
        final boolean teleportCommandAvailable,
        final boolean waypointEditable,
        final boolean waypointDeletable
    ) {
        if (action == Action.DELETE_WAYPOINT) {
            return playerPresent && waypointDeletable;
        }
        return actionEnabled(
            action, playerPresent, estimatedHeightKnown, teleportCommandAvailable, waypointEditable
        );
    }

    static Bounds place(
        final int cursorX,
        final int cursorY,
        final int viewportWidth,
        final int viewportHeight,
        final int actionCount
    ) {
        final int availableWidth = Math.max(1, viewportWidth - SCREEN_MARGIN * 2);
        final int availableHeight = Math.max(1, viewportHeight - SCREEN_MARGIN * 2);
        final int panelWidth = Math.min(PANEL_WIDTH, availableWidth);
        final int desiredHeight = PANEL_PADDING * 2
            + actionCount * BUTTON_HEIGHT
            + Math.max(0, actionCount - 1) * BUTTON_GAP;
        final int panelHeight = Math.min(desiredHeight, availableHeight);
        final int x = placeAxis(cursorX, panelWidth, viewportWidth);
        final int y = placeAxis(cursorY, panelHeight, viewportHeight);
        return new Bounds(x, y, panelWidth, panelHeight);
    }

    private static int placeAxis(final int cursor, final int size, final int viewportSize) {
        final int after = cursor + CURSOR_GAP;
        final int before = cursor - CURSOR_GAP - size;
        final int desired = after + size <= viewportSize - SCREEN_MARGIN ? after : before;
        return Math.max(SCREEN_MARGIN, Math.min(desired, viewportSize - SCREEN_MARGIN - size));
    }

    static Target targetAt(final double worldX, final OptionalInt surfaceY, final double worldZ) {
        return targetAt(
            worldX,
            surfaceY.isPresent()
                ? new ColumnStore.SurfaceLookup(true, surfaceY)
                : ColumnStore.SurfaceLookup.UNKNOWN,
            worldZ
        );
    }

    static Target targetAt(final double worldX, final ColumnStore.SurfaceLookup ground, final double worldZ) {
        return new Target((int) Math.floor(worldX), ground, (int) Math.floor(worldZ));
    }

    static Point pointAt(
        final double worldX,
        final double worldZ,
        final StructureIndex.Marker structure
    ) {
        return structure == null
            ? new Point((int) Math.floor(worldX), (int) Math.floor(worldZ))
            : new Point(structure.blockX(), structure.blockZ());
    }

    static MapLayer topSurfaceLayer(final DimensionLayerPolicy.DimensionKind dimensionKind) {
        return switch (dimensionKind) {
            case SKY_LIT -> MapLayer.SURFACE;
            case NO_SKY_NO_CEILING -> MapLayer.END_SURFACE;
            case HAS_CEILING -> MapLayer.NETHER_CEILING;
        };
    }

    static void drawPanel(final GuiDraw draw, final Bounds bounds) {
        final int x = bounds.x();
        final int y = bounds.y();
        final int right = x + bounds.width();
        final int bottom = y + bounds.height();
        draw.fill(x, y, right, bottom, PANEL_BACKGROUND);
        draw.fill(x, y, right, y + 1, PANEL_BORDER);
        draw.fill(x, bottom - 1, right, bottom, PANEL_BORDER);
        draw.fill(x, y, x + 1, bottom, PANEL_BORDER);
        draw.fill(right - 1, y, right, bottom, PANEL_BORDER);
    }

    /**
     * One menu placement captured at right-click time: resolved panel geometry, the block
     * target, and the hovered waypoint/player the actions apply to. Shared by the fullscreen
     * menu and the embedded split-map menu.
     */
    record Capture(
        Bounds bounds,
        Target target,
        WaypointRenderEntry waypoint,
        UUID playerId
    ) {
    }

    /** One menu button's fully resolved presentation, shared by every hosting screen. */
    record ButtonSpec(Action action, String labelKey, String tooltipKey, boolean active) {
    }

    record Bounds(int x, int y, int width, int height) {
        int buttonX() {
            return x + PANEL_PADDING;
        }

        int buttonY(final int index) {
            return y + PANEL_PADDING + index * (BUTTON_HEIGHT + BUTTON_GAP);
        }

        int buttonWidth() {
            return width - PANEL_PADDING * 2;
        }

        boolean contains(final double mouseX, final double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    record Point(int blockX, int blockZ) {
    }

    record Target(int blockX, ColumnStore.SurfaceLookup ground, int blockZ) {
        Target {
            ground = ground == null ? ColumnStore.SurfaceLookup.UNKNOWN : ground;
        }

        OptionalInt blockY() {
            return ground.surfaceY().isPresent()
                ? OptionalInt.of(ground.surfaceY().getAsInt() + 1)
                : OptionalInt.empty();
        }

        /**
         * Whether the ground answer is final: a surface to land on, or a known void column
         * (which teleports at the player's current Y and must not wait for a prediction).
         */
        boolean groundKnown() {
            return ground.known();
        }
    }
}
