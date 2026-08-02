package cn.net.rms.confluxmap.core.config;

/**
 * Finds a temporary, fully visible minimap position when vanilla's right-aligned status-effect
 * rows would otherwise cover it. The configured minimap position is never changed.
 */
public final class MinimapStatusEffectAvoidance {
    private static final int EFFECT_STEP = 25;
    private static final int EFFECT_ROW_HEIGHT = 24;
    private static final int BENEFICIAL_TOP = 1;
    private static final int HARMFUL_TOP = 27;

    private MinimapStatusEffectAvoidance() {
    }

    /**
     * Resolves the nearest in-bounds position that does not overlap the visible effect rows.
     * Ties retain the user's horizontal alignment where possible by preferring vertical movement.
     */
    public static MinimapPlacement.Layout resolve(
        final int screenWidth,
        final int screenHeight,
        final MinimapPlacement.Layout configuredLayout,
        final int beneficialCount,
        final int harmfulCount
    ) {
        if (configuredLayout == null) {
            throw new IllegalArgumentException("configuredLayout must not be null");
        }
        final MinimapHudAvoidance.Bounds effects = visibleBounds(screenWidth, beneficialCount, harmfulCount);
        if (effects == null) {
            return configuredLayout;
        }
        return MinimapHudAvoidance.resolve(screenWidth, screenHeight, configuredLayout, effects);
    }

    /** Returns the right-aligned vanilla effect area, or {@code null} when no effect icon is visible. */
    public static MinimapHudAvoidance.Bounds visibleBounds(
        final int screenWidth,
        final int beneficialCount,
        final int harmfulCount
    ) {
        final int safeBeneficialCount = Math.max(0, beneficialCount);
        final int safeHarmfulCount = Math.max(0, harmfulCount);
        final int columns = Math.max(safeBeneficialCount, safeHarmfulCount);
        if (columns == 0) {
            return null;
        }
        final int safeWidth = Math.max(1, screenWidth);
        return new MinimapHudAvoidance.Bounds(
            Math.max(0, safeWidth - columns * EFFECT_STEP),
            safeBeneficialCount > 0 ? BENEFICIAL_TOP : HARMFUL_TOP,
            safeWidth,
            safeHarmfulCount > 0 ? HARMFUL_TOP + EFFECT_ROW_HEIGHT : BENEFICIAL_TOP + EFFECT_ROW_HEIGHT
        );
    }
}
