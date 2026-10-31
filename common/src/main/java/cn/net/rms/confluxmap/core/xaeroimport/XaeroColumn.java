package cn.net.rms.confluxmap.core.xaeroimport;

import java.util.List;

/**
 * One decoded block column (one map pixel). The surface state is the column's
 * topmost <b>opaque</b> block: for water columns that is the floor, and the
 * water itself only appears as an overlay.
 *
 * @param surface topmost opaque block state, or null for a grass surface (the
 *     format deliberately omits the grass state; it is reconstructed as the
 *     default grass block on load)
 * @param height Y of the opaque surface block (signed; world-bottom sentinel
 *     columns carry an explicit {@code minecraft:air} state instead)
 * @param topHeight Y of the highest translucent block in the column, or
 *     {@link #NO_TOP_HEIGHT} when the format recorded none. Only the low 8
 *     bits are stored on disk, so this is lossy above Y 255 on 1.18+ worlds
 * @param light block light sampled just above the surface
 * @param biomeId biome registry id, or null when the stream carried none
 * @param overlays translucent layers above the surface, topmost first
 */
public record XaeroColumn(
    XaeroBlockState surface,
    int height,
    int topHeight,
    int light,
    String biomeId,
    List<XaeroOverlay> overlays
) {
    /** Sentinel for "no translucent stack recorded". */
    public static final int NO_TOP_HEIGHT = Integer.MIN_VALUE;

    public XaeroColumn {
        overlays = overlays == null ? List.of() : List.copyOf(overlays);
    }

    public boolean isGrassSurface() {
        return surface == null;
    }

    /** Whether the stream recorded a top-of-translucent-stack height. */
    public boolean hasTopHeight() {
        return topHeight != NO_TOP_HEIGHT;
    }

    /**
     * Thickness of the translucent stack above the surface, e.g. the water
     * depth of a water column. Only meaningful when {@link #hasTopHeight()}.
     * The stored top height is the surface Y modulo 256 on 1.18+ worlds, so the
     * difference is recovered modulo 256; exact for every depth below 256.
     */
    public int translucentDepth() {
        if (topHeight == NO_TOP_HEIGHT) {
            return 0;
        }
        return Math.floorMod(topHeight - height, 256);
    }

    public boolean hasWater() {
        return overlays.stream().anyMatch(XaeroOverlay::isWater);
    }
}
