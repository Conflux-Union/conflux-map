package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.List;
import java.util.OptionalInt;

/** Layout and captured map target for the fullscreen map's right-click location menu. */
final class FullscreenMapLocationMenu {
    static final int SCREEN_MARGIN = 4;
    static final int PANEL_WIDTH = 140;
    static final int PANEL_PADDING = 3;
    static final int BUTTON_HEIGHT = 20;
    static final int BUTTON_GAP = 2;
    static final int CURSOR_GAP = 2;

    enum Action {
        SET_WAYPOINT("confluxmap.map.location_menu.set_waypoint"),
        EDIT_WAYPOINT("confluxmap.map.location_menu.edit_waypoint"),
        SHARE_LOCATION("confluxmap.map.location_menu.share_location"),
        TELEPORT("confluxmap.map.location_menu.teleport"),
        HIGHLIGHT("confluxmap.map.location_menu.highlight"),
        CLEAR_HIGHLIGHT("confluxmap.map.location_menu.clear_highlight");

        private final String translationKey;

        Action(final String translationKey) {
            this.translationKey = translationKey;
        }

        String translationKey() {
            return translationKey;
        }
    }

    private static final List<Action> DEFAULT_ACTIONS = List.of(
        Action.SET_WAYPOINT,
        Action.SHARE_LOCATION,
        Action.TELEPORT,
        Action.HIGHLIGHT,
        Action.CLEAR_HIGHLIGHT
    );
    private static final List<Action> TELEPORT_FIRST_ACTIONS = List.of(
        Action.TELEPORT,
        Action.SET_WAYPOINT,
        Action.SHARE_LOCATION,
        Action.HIGHLIGHT,
        Action.CLEAR_HIGHLIGHT
    );
    private static final List<Action> EDIT_ACTIONS = List.of(
        Action.EDIT_WAYPOINT,
        Action.SHARE_LOCATION,
        Action.TELEPORT,
        Action.HIGHLIGHT,
        Action.CLEAR_HIGHLIGHT
    );
    private static final List<Action> EDIT_TELEPORT_FIRST_ACTIONS = List.of(
        Action.TELEPORT,
        Action.EDIT_WAYPOINT,
        Action.SHARE_LOCATION,
        Action.HIGHLIGHT,
        Action.CLEAR_HIGHLIGHT
    );
    private static final int PANEL_HEIGHT = PANEL_PADDING * 2
        + DEFAULT_ACTIONS.size() * BUTTON_HEIGHT
        + (DEFAULT_ACTIONS.size() - 1) * BUTTON_GAP;

    private FullscreenMapLocationMenu() {
    }

    static List<Action> actions(final boolean teleportCommandAvailable) {
        return teleportCommandAvailable ? TELEPORT_FIRST_ACTIONS : DEFAULT_ACTIONS;
    }

    static List<Action> actions(final boolean teleportCommandAvailable, final boolean existingWaypoint) {
        if (!existingWaypoint) {
            return actions(teleportCommandAvailable);
        }
        return teleportCommandAvailable ? EDIT_TELEPORT_FIRST_ACTIONS : EDIT_ACTIONS;
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
        if (action == Action.HIGHLIGHT || action == Action.CLEAR_HIGHLIGHT) {
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

    static Bounds place(
        final int cursorX,
        final int cursorY,
        final int viewportWidth,
        final int viewportHeight
    ) {
        final int availableWidth = Math.max(1, viewportWidth - SCREEN_MARGIN * 2);
        final int availableHeight = Math.max(1, viewportHeight - SCREEN_MARGIN * 2);
        final int panelWidth = Math.min(PANEL_WIDTH, availableWidth);
        final int panelHeight = Math.min(PANEL_HEIGHT, availableHeight);
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
        return new Target((int) Math.floor(worldX), surfaceY, (int) Math.floor(worldZ));
    }

    static MapLayer topSurfaceLayer(final LayerSelector.DimensionKind dimensionKind) {
        return switch (dimensionKind) {
            case SKY_LIT -> MapLayer.SURFACE;
            case NO_SKY_NO_CEILING -> MapLayer.END_SURFACE;
            case HAS_CEILING -> MapLayer.NETHER_CEILING;
        };
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

    record Target(int blockX, OptionalInt surfaceY, int blockZ) {
        Target {
            surfaceY = surfaceY == null ? OptionalInt.empty() : surfaceY;
        }

        OptionalInt blockY() {
            return surfaceY.isPresent() ? OptionalInt.of(surfaceY.getAsInt() + 1) : OptionalInt.empty();
        }
    }
}
