package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.util.math.MathHelper;

/**
 * Colors, sizes, and reveal constants for the in-world waypoint labels, plus the
 * shared color math. Both label flavors consume these: the 3D billboard drawn by
 * {@link WaypointLabelPainter} and the item-icon label drawn by
 * {@link WaypointItemHudRenderer}, so the two read identically at any size.
 */
final class WaypointLabelStyle {
    /** waypoint-ux.md S6 "distance fade-in": alpha ramps 0 -> 1 over the nearest ~5 blocks so the label doesn't pop in right next to the camera. */
    static final double LABEL_NEAR_FADE_BLOCKS = 5.0;
    static final float LABEL_BASE_SCALE = 0.06f;
    static final float LABEL_ICON_COLLAPSED_SIZE = 12.0f;
    static final float LABEL_ICON_EXPANDED_SIZE = 18.0f;
    static final float LABEL_PANEL_HEIGHT = 20.0f;
    static final float LABEL_PANEL_PADDING = 3.0f;
    static final float LABEL_PANEL_GAP = 1.0f;
    static final float LABEL_TEXT_REVEAL_START = 0.72f;
    static final int LABEL_BACKGROUND_COLOR = 0xC0101010;
    static final int LABEL_LOCAL_OUTLINE_COLOR = 0xFF101010;
    static final int LABEL_SHARED_OUTLINE_COLOR = 0xFF55DDE0;
    static final int LABEL_NAME_COLOR = 0xFFFFFFFF;
    static final int LABEL_DISTANCE_COLOR = 0xFFC8C8C8;
    /** Name/distance text rows inside the panel, shared by both label flavors. */
    static final float LABEL_NAME_TEXT_Y = -9f;
    static final float LABEL_DISTANCE_TEXT_Y = 1f;
    /** LightmapTextureManager.pack(15, 15) - always fully lit, like other UI-ish world markers. */
    static final int LABEL_LIGHT = 0xF000F0;

    private WaypointLabelStyle() {
    }

    /**
     * User opacity for the whole in-world waypoint label: icon plates, the expanded panel
     * background, and the panel text. The panel background keeps its own base alpha
     * ({@link #LABEL_BACKGROUND_COLOR}); this factor scales whatever the base produces.
     */
    static float labelOpacityFactor(final ConfluxConfig config) {
        return config.waypointIconOpacity / (float) ConfluxConfig.MAX_WAYPOINT_ICON_OPACITY;
    }

    static int withAlpha(final int argb, final float alpha) {
        final int a = Math.round(Argb.alpha(argb) * MathHelper.clamp(alpha, 0f, 1f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    static int outlineColor(final WaypointRenderEntry waypoint) {
        if (!waypoint.shared()) {
            return LABEL_LOCAL_OUTLINE_COLOR;
        }
        return LABEL_SHARED_OUTLINE_COLOR;
    }

    /**
     * The panel/text reveal layout both label flavors build from the same inputs, so
     * their panels cannot drift apart.
     */
    record Panel(
        String name,
        /** Half-height gray "(temporary)" suffix; empty for non-temporary waypoints. */
        String nameSuffix,
        String distanceText,
        float fullWidth,
        float reveal,
        float textReveal
    ) {
        static Panel of(
            final TextRenderer textRenderer,
            final WaypointRenderEntry waypoint,
            final double distance3d,
            final float easedProgress
        ) {
            final String name = waypoint.name();
            final String nameSuffix = waypoint.type() == Waypoint.Type.TEMPORARY && waypoint.local()
                ? WaypointMarkerRenderer.temporaryTag()
                : "";
            final String distanceText = Math.round(distance3d) + " m";
            final int nameWidth = textRenderer.getWidth(name);
            // The suffix draws at half scale after a one-pixel gap.
            final float suffixWidth = nameSuffix.isEmpty()
                ? 0f
                : textRenderer.getWidth(nameSuffix) / 2f + 1f;
            final int distanceWidth = textRenderer.getWidth(distanceText);
            return new Panel(
                name,
                nameSuffix,
                distanceText,
                Math.max(nameWidth + suffixWidth, distanceWidth) + LABEL_PANEL_PADDING * 2f,
                MathHelper.clamp(easedProgress / LABEL_TEXT_REVEAL_START, 0f, 1f),
                MathHelper.clamp(
                    (easedProgress - LABEL_TEXT_REVEAL_START) / (1f - LABEL_TEXT_REVEAL_START),
                    0f, 1f
                )
            );
        }

        /** The panel width currently revealed; grows with the icon expansion. */
        float width() {
            return fullWidth * reveal;
        }

        /** Left edge of the text runs, {@code panelX} being the panel's left edge. */
        float textX(final float panelX) {
            return panelX + LABEL_PANEL_PADDING + (1f - textReveal) * 4f;
        }

        /** Text alpha: the panel's base alpha scaled by how far the text has revealed. */
        float textAlpha(final float baseAlpha) {
            return baseAlpha * textReveal;
        }
    }
}
