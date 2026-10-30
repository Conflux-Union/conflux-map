package cn.net.rms.confluxmap.core.xaeroimport;

import cn.net.rms.confluxmap.core.model.SurfaceKind;

/**
 * Renders decoded external-map columns into snapshot-ready render inputs using
 * the running game's color model. Implemented on the Minecraft side (the colors
 * come from the live texture atlas and registry); the converter in this package
 * is game-free and testable against fakes.
 */
public interface XaeroColumnBaker {
    /**
     * Render inputs for one column.
     *
     * @param baseArgb surface color with position detail applied but biome tint separate
     * @param xaeroBaseArgb raw texture-average color for Xaero-style rendering
     * @param tintArgb biome tint (white = none); already pre-multiplied into base
     *     for water/ice columns, mirroring capture
     * @param overlayArgb water/ice columns: the pre-composited seafloor color; land: 0
     * @param xaeroOverlayArgb Xaero-filtered variant of {@code overlayArgb}
     * @param kind surface kind; {@link SurfaceKind#VOID} marks empty columns
     * @param surfaceY the Y the map should show for this column (water surface for
     *     water columns, floor + depth)
     */
    record Baked(
        int baseArgb,
        int xaeroBaseArgb,
        int tintArgb,
        int overlayArgb,
        int xaeroOverlayArgb,
        SurfaceKind kind,
        short surfaceY
    ) {
        public static Baked voidColumn() {
            return new Baked(0, 0, 0, 0, 0, SurfaceKind.VOID, (short) 0);
        }
    }

    /**
     * Bakes one column.
     *
     * @param waterDepth recovered translucent-stack thickness (0 when none); water
     *     columns use it for the surface Y and the fluid-depth byte
     * @param biomeId biome resource id, may be null
     * @param ceilingLayer true when importing the nether-ceiling view (bedrock roof
     *     columns classify as BEDROCK_CEILING there)
     */
    Baked bake(XaeroColumn column, int worldX, int worldZ, int waterDepth, String biomeId, boolean ceilingLayer);
}
