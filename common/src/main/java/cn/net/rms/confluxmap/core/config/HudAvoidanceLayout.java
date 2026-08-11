package cn.net.rms.confluxmap.core.config;

/** Coordinates vanilla HUD transforms around the configured minimap position. */
public final class HudAvoidanceLayout {
    public static final int INFORMATION_GAP = 3;
    public static final int INFORMATION_LINE_HEIGHT = 10;

    private HudAvoidanceLayout() {
    }

    /** Keeps the minimap fixed while resolving scoreboard and status-effect transforms. */
    public static Decision resolve(
        final boolean avoidanceEnabled,
        final int screenWidth,
        final int screenHeight,
        final MinimapPlacement.Layout configuredMinimap,
        final int informationHeight,
        final int statusEffectTop,
        final int beneficialEffectCount,
        final int harmfulEffectCount,
        final ScoreboardHudAvoidance.Bounds scoreboard
    ) {
        if (configuredMinimap == null) {
            throw new IllegalArgumentException("configuredMinimap must not be null");
        }
        return new Decision(
            configuredMinimap,
            scoreboardTransform(
                avoidanceEnabled,
                screenHeight,
                configuredMinimap,
                informationHeight,
                scoreboard
            ),
            statusEffectShift(
                avoidanceEnabled,
                screenWidth,
                configuredMinimap,
                statusEffectTop,
                beneficialEffectCount,
                harmfulEffectCount
            )
        );
    }

    public static ScoreboardHudAvoidance.Transform scoreboardTransform(
        final boolean avoidanceEnabled,
        final int screenHeight,
        final MinimapPlacement.Layout minimap,
        final int informationHeight,
        final ScoreboardHudAvoidance.Bounds scoreboard
    ) {
        return avoidanceEnabled
            ? ScoreboardHudAvoidance.resolve(screenHeight, minimap, informationHeight, scoreboard)
            : ScoreboardHudAvoidance.Transform.IDENTITY;
    }

    public static int statusEffectShift(
        final boolean avoidanceEnabled,
        final int screenWidth,
        final MinimapPlacement.Layout minimap,
        final int statusEffectTop,
        final int beneficialEffectCount,
        final int harmfulEffectCount
    ) {
        return avoidanceEnabled
            ? StatusEffectHudAvoidance.horizontalShift(
                screenWidth,
                statusEffectTop,
                beneficialEffectCount,
                harmfulEffectCount,
                minimap
            )
            : 0;
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

    public record Decision(
        MinimapPlacement.Layout minimap,
        ScoreboardHudAvoidance.Transform scoreboardTransform,
        int statusEffectShift
    ) {
    }
}
