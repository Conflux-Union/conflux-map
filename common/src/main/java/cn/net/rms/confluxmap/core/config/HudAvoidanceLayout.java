package cn.net.rms.confluxmap.core.config;

/** Coordinates the render-only minimap position and status-effect translation. */
public final class HudAvoidanceLayout {
    public static final int INFORMATION_GAP = 3;
    public static final int INFORMATION_LINE_HEIGHT = 10;

    private HudAvoidanceLayout() {
    }

    /** Resolves both HUD elements from the same scoreboard snapshot and configured minimap. */
    public static Decision resolve(
        final boolean avoidanceEnabled,
        final int screenWidth,
        final int screenHeight,
        final MinimapPlacement.Layout configuredMinimap,
        final int informationHeight,
        final int statusEffectTop,
        final int beneficialEffectCount,
        final int harmfulEffectCount,
        final MinimapHudAvoidance.Bounds scoreboard
    ) {
        final MinimapPlacement.Layout minimap = resolveMinimap(
            avoidanceEnabled,
            screenWidth,
            screenHeight,
            configuredMinimap,
            informationHeight,
            scoreboard
        );
        final int statusEffectShift = avoidanceEnabled
            ? StatusEffectHudAvoidance.horizontalShift(
                screenWidth,
                statusEffectTop,
                beneficialEffectCount,
                harmfulEffectCount,
                minimap
            )
            : 0;
        return new Decision(minimap, statusEffectShift);
    }

    /** Resolves the final minimap position shared by the minimap and status-effect policies. */
    public static MinimapPlacement.Layout resolveMinimap(
        final boolean avoidanceEnabled,
        final int screenWidth,
        final int screenHeight,
        final MinimapPlacement.Layout configuredMinimap,
        final int informationHeight,
        final MinimapHudAvoidance.Bounds scoreboard
    ) {
        if (configuredMinimap == null) {
            throw new IllegalArgumentException("configuredMinimap must not be null");
        }
        return avoidanceEnabled
            ? MinimapHudAvoidance.resolve(
                screenWidth,
                screenHeight,
                configuredMinimap,
                informationHeight,
                scoreboard
            )
            : configuredMinimap;
    }

    public static int informationHeight(
        final boolean showCoordinates,
        final boolean showBiome,
        final boolean showLayerIndicator
    ) {
        int lines = 0;
        if (showCoordinates) {
            lines++;
        }
        if (showBiome) {
            lines++;
        }
        if (showLayerIndicator) {
            lines++;
        }
        return lines == 0 ? 0 : INFORMATION_GAP + lines * INFORMATION_LINE_HEIGHT;
    }

    public record Decision(MinimapPlacement.Layout minimap, int statusEffectShift) {
    }
}
