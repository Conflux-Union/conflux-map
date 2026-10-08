package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Keys;
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
import net.minecraft.client.font.TextRenderer;

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
    private static final int HOTKEY_COLOR = 0xFF55FF55;
    private static final int HOTKEY_DISABLED_COLOR = 0xFF707070;
    private static final int HOTKEY_HINT_GAP = 4;

    /** Digit shortcuts for the open menu, one per button in list order. */
    private static final int[] HOTKEY_KEYS = {
        Keys.DIGIT_1, Keys.DIGIT_2, Keys.DIGIT_3, Keys.DIGIT_4, Keys.DIGIT_5
    };

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
        final boolean existingWaypoint,
        final boolean currentTargetHighlighted
    ) {
        return actions(existingWaypoint, currentTargetHighlighted, false);
    }

    /** Teleport stays first whether or not the command is currently usable: it only greys out in place. */
    static List<Action> actions(
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
            return List.of(Action.TELEPORT, Action.EDIT_WAYPOINT, Action.DELETE_WAYPOINT, share, highlight);
        }
        return List.of(Action.TELEPORT, edit, share, highlight);
    }

    /**
     * Whether teleport has a usable landing answer: resolved ground, or a live session whose
     * two-stage teleport resolves the ground (or a void column, landing at the pre-teleport Y)
     * after the target chunk loads. Only browsing a non-live session needs a known height up
     * front, because there is no client chunk to resolve it from.
     */
    static boolean teleportPositionKnown(final Target target, final boolean viewingLiveSession) {
        return target.groundKnown() || viewingLiveSession;
    }

    static boolean isSavedWaypoint(final WaypointRenderEntry waypoint) {
        return waypoint != null
            && !WaypointHighlightState.SELECTED_LOCATION_ID.equals(waypoint.id());
    }

    static boolean actionEnabled(
        final Action action,
        final boolean playerPresent,
        final boolean teleportCommandAvailable
    ) {
        if (!playerPresent || action == Action.DELETE_WAYPOINT) {
            return false;
        }
        return action != Action.TELEPORT || teleportCommandAvailable;
    }

    static boolean actionEnabled(
        final Action action,
        final boolean playerPresent,
        final boolean teleportCommandAvailable,
        final boolean waypointEditable
    ) {
        if (action == Action.EDIT_WAYPOINT) {
            return playerPresent && waypointEditable;
        }
        return actionEnabled(action, playerPresent, teleportCommandAvailable);
    }

    static boolean actionEnabled(
        final Action action,
        final boolean playerPresent,
        final boolean teleportCommandAvailable,
        final boolean waypointEditable,
        final boolean waypointDeletable
    ) {
        if (action == Action.DELETE_WAYPOINT) {
            return playerPresent && waypointDeletable;
        }
        return actionEnabled(action, playerPresent, teleportCommandAvailable, waypointEditable);
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
     * Draws each button's digit shortcut at the panel's right edge, on top of the already
     * rendered buttons: green when the shortcut works, grey when the button is greyed out.
     */
    static void drawHotkeyHints(
        final GuiDraw draw,
        final TextRenderer font,
        final Bounds bounds,
        final List<ButtonSpec> specs
    ) {
        for (int index = 0; index < specs.size(); index++) {
            final String label = hotkeyLabel(index);
            if (label == null) {
                return;
            }
            final ButtonSpec spec = specs.get(index);
            final int x = bounds.x() + bounds.width() - PANEL_PADDING - HOTKEY_HINT_GAP
                - font.getWidth(label);
            final int y = bounds.buttonY(index) + (BUTTON_HEIGHT - font.fontHeight) / 2;
            draw.drawTextWithShadow(
                font, label, x, y, spec.active() ? HOTKEY_COLOR : HOTKEY_DISABLED_COLOR
            );
        }
    }

    /**
     * The action a digit shortcut would trigger, or null when the key is no menu shortcut,
     * addresses no button in the live list, or that button is greyed out. The live list is
     * authoritative: sibling waypoints drop entries and the delete confirmation relabels
     * one, so indexes must never come from the {@link Action} enum.
     */
    static Action actionForHotkey(final List<ButtonSpec> specs, final int keyCode) {
        final int index = hotkeyIndex(keyCode);
        if (index < 0 || index >= specs.size()) {
            return null;
        }
        final ButtonSpec spec = specs.get(index);
        return spec.active() ? spec.action() : null;
    }

    /** Index of the digit shortcut pressed, or -1 when the key is no menu shortcut. */
    static int hotkeyIndex(final int keyCode) {
        for (int index = 0; index < HOTKEY_KEYS.length; index++) {
            if (HOTKEY_KEYS[index] == keyCode) {
                return index;
            }
        }
        return -1;
    }

    /** The digit shown for a button index, or null when the menu has no shortcut that deep. */
    static String hotkeyLabel(final int index) {
        return index >= 0 && index < HOTKEY_KEYS.length ? Integer.toString(index + 1) : null;
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

        /** Waypoints and shared locations always need a Y, so a column without ground borrows the player's. */
        int placementY(final int playerY) {
            return blockY().orElse(playerY);
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
