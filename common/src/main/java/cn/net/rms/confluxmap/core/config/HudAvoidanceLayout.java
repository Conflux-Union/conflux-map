package cn.net.rms.confluxmap.core.config;

/** Coordinates vanilla HUD transforms around the configured minimap position. */
public final class HudAvoidanceLayout {
    private HudAvoidanceLayout() {
    }

    /** Keeps the minimap fixed while resolving scoreboard and status-effect transforms. */
    public static Decision resolve(
        final boolean avoidanceEnabled,
        final int screenHeight,
        final MinimapPlacement.Layout configuredMinimap,
        final int informationHeight,
        final HudRect beneficialEffectRow,
        final HudRect harmfulEffectRow,
        final HudRect scoreboard
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
                configuredMinimap,
                beneficialEffectRow,
                harmfulEffectRow
            )
        );
    }

    public static HudTransform scoreboardTransform(
        final boolean avoidanceEnabled,
        final int screenHeight,
        final MinimapPlacement.Layout minimap,
        final int informationHeight,
        final HudRect scoreboard
    ) {
        return avoidanceEnabled
            ? ScoreboardHudAvoidance.resolve(screenHeight, minimap, informationHeight, scoreboard)
            : HudTransform.IDENTITY;
    }

    public static int statusEffectShift(
        final boolean avoidanceEnabled,
        final MinimapPlacement.Layout minimap,
        final HudRect beneficialRow,
        final HudRect harmfulRow
    ) {
        return avoidanceEnabled
            ? StatusEffectHudAvoidance.horizontalShift(minimap, beneficialRow, harmfulRow)
            : 0;
    }

    public static int toastShift(
        final boolean avoidanceEnabled,
        final int screenHeight,
        final MinimapPlacement.Layout minimap,
        final int informationHeight,
        final HudRect toasts
    ) {
        return avoidanceEnabled
            ? ToastHudAvoidance.verticalShift(screenHeight, minimap, informationHeight, toasts)
            : 0;
    }

    public record Decision(
        MinimapPlacement.Layout minimap,
        HudTransform scoreboardTransform,
        int statusEffectShift
    ) {
    }
}
