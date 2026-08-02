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
    private static final int GAP = 2;

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
        final int safeBeneficialCount = Math.max(0, beneficialCount);
        final int safeHarmfulCount = Math.max(0, harmfulCount);
        final int columns = Math.max(safeBeneficialCount, safeHarmfulCount);
        if (columns == 0) {
            return configuredLayout;
        }

        final int safeWidth = Math.max(1, screenWidth);
        final int safeHeight = Math.max(1, screenHeight);
        final Bounds effects = new Bounds(
            Math.max(0, safeWidth - columns * EFFECT_STEP),
            safeBeneficialCount > 0 ? BENEFICIAL_TOP : HARMFUL_TOP,
            safeWidth,
            safeHarmfulCount > 0 ? HARMFUL_TOP + EFFECT_ROW_HEIGHT : BENEFICIAL_TOP + EFFECT_ROW_HEIGHT
        );
        final Bounds configured = bounds(configuredLayout);
        if (!configured.intersects(effects)) {
            return configuredLayout;
        }

        final MinimapPlacement.Layout minimum = MinimapPlacement.resolve(
            safeWidth, safeHeight, configuredLayout.size(), 0.0, 0.0
        );
        final MinimapPlacement.Layout maximum = MinimapPlacement.resolve(
            safeWidth, safeHeight, configuredLayout.size(), 1.0, 1.0
        );
        Candidate best = null;
        best = choose(best, configuredLayout, configuredLayout.x(), effects.bottom() + GAP, minimum, maximum);
        best = choose(best, configuredLayout, effects.left() - GAP - configuredLayout.size(), configuredLayout.y(), minimum, maximum);
        best = choose(best, configuredLayout, configuredLayout.x(), effects.top() - GAP - configuredLayout.size(), minimum, maximum);
        best = choose(best, configuredLayout, effects.right() + GAP, configuredLayout.y(), minimum, maximum);
        return best == null ? configuredLayout : best.layout();
    }

    private static Candidate choose(
        final Candidate current,
        final MinimapPlacement.Layout configured,
        final int x,
        final int y,
        final MinimapPlacement.Layout minimum,
        final MinimapPlacement.Layout maximum
    ) {
        if (x < minimum.x() || x > maximum.x() || y < minimum.y() || y > maximum.y()) {
            return current;
        }
        final long deltaX = (long) x - configured.x();
        final long deltaY = (long) y - configured.y();
        final Candidate candidate = new Candidate(
            new MinimapPlacement.Layout(x, y, configured.size()), deltaX * deltaX + deltaY * deltaY
        );
        return current == null || candidate.distanceSquared() < current.distanceSquared() ? candidate : current;
    }

    private static Bounds bounds(final MinimapPlacement.Layout layout) {
        return new Bounds(layout.x(), layout.y(), layout.x() + layout.size(), layout.y() + layout.size());
    }

    private record Candidate(MinimapPlacement.Layout layout, long distanceSquared) {
    }

    private record Bounds(int left, int top, int right, int bottom) {
        private boolean intersects(final Bounds other) {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
        }
    }
}
