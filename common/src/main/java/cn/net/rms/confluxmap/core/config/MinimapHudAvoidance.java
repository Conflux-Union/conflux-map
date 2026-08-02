package cn.net.rms.confluxmap.core.config;

/**
 * Finds a temporary minimap position that remains visible beside HUD elements. The configured
 * minimap position is never changed.
 */
public final class MinimapHudAvoidance {
    private static final int GAP = 2;

    private MinimapHudAvoidance() {
    }

    /**
     * Resolves the nearest in-bounds position that does not overlap any supplied HUD bounds.
     * Ties retain the user's horizontal alignment where possible by preferring vertical movement.
     */
    public static MinimapPlacement.Layout resolve(
        final int screenWidth,
        final int screenHeight,
        final MinimapPlacement.Layout configuredLayout,
        final Bounds... obstacles
    ) {
        if (configuredLayout == null) {
            throw new IllegalArgumentException("configuredLayout must not be null");
        }
        if (!intersectsAny(bounds(configuredLayout), obstacles)) {
            return configuredLayout;
        }

        final int safeWidth = Math.max(1, screenWidth);
        final int safeHeight = Math.max(1, screenHeight);
        final MinimapPlacement.Layout minimum = MinimapPlacement.resolve(
            safeWidth, safeHeight, configuredLayout.size(), 0.0, 0.0
        );
        final MinimapPlacement.Layout maximum = MinimapPlacement.resolve(
            safeWidth, safeHeight, configuredLayout.size(), 1.0, 1.0
        );
        final int[] xCandidates = candidates(
            configuredLayout.x(), minimum.x(), maximum.x(), configuredLayout.size(), obstacles, true
        );
        final int[] yCandidates = candidates(
            configuredLayout.y(), minimum.y(), maximum.y(), configuredLayout.size(), obstacles, false
        );

        Candidate best = null;
        for (final int y : yCandidates) {
            for (final int x : xCandidates) {
                best = choose(best, configuredLayout, x, y, minimum, maximum, obstacles);
            }
        }
        return best == null ? configuredLayout : best.layout();
    }

    private static int[] candidates(
        final int configured,
        final int minimum,
        final int maximum,
        final int size,
        final Bounds[] obstacles,
        final boolean horizontal
    ) {
        int count = 3;
        if (obstacles != null) {
            for (final Bounds obstacle : obstacles) {
                if (obstacle != null) {
                    count += 2;
                }
            }
        }
        final int[] candidates = new int[count];
        candidates[0] = configured;
        candidates[1] = minimum;
        candidates[2] = maximum;
        int index = 3;
        if (obstacles != null) {
            for (final Bounds obstacle : obstacles) {
                if (obstacle == null) {
                    continue;
                }
                candidates[index++] = (horizontal ? obstacle.left() : obstacle.top()) - GAP - size;
                candidates[index++] = (horizontal ? obstacle.right() : obstacle.bottom()) + GAP;
            }
        }
        return candidates;
    }

    private static Candidate choose(
        final Candidate current,
        final MinimapPlacement.Layout configured,
        final int x,
        final int y,
        final MinimapPlacement.Layout minimum,
        final MinimapPlacement.Layout maximum,
        final Bounds[] obstacles
    ) {
        if (x < minimum.x() || x > maximum.x() || y < minimum.y() || y > maximum.y()) {
            return current;
        }
        final MinimapPlacement.Layout layout = new MinimapPlacement.Layout(x, y, configured.size());
        if (intersectsAny(bounds(layout), obstacles)) {
            return current;
        }
        final long deltaX = (long) x - configured.x();
        final long deltaY = (long) y - configured.y();
        final Candidate candidate = new Candidate(layout, deltaX * deltaX + deltaY * deltaY, Math.abs(deltaX));
        if (current == null || candidate.distanceSquared() < current.distanceSquared()) {
            return candidate;
        }
        if (candidate.distanceSquared() == current.distanceSquared()
            && candidate.horizontalDistance() < current.horizontalDistance()) {
            return candidate;
        }
        return current;
    }

    private static boolean intersectsAny(final Bounds layout, final Bounds[] obstacles) {
        if (obstacles == null) {
            return false;
        }
        for (final Bounds obstacle : obstacles) {
            if (obstacle != null && layout.intersects(obstacle)) {
                return true;
            }
        }
        return false;
    }

    private static Bounds bounds(final MinimapPlacement.Layout layout) {
        return new Bounds(layout.x(), layout.y(), layout.x() + layout.size(), layout.y() + layout.size());
    }

    private record Candidate(MinimapPlacement.Layout layout, long distanceSquared, long horizontalDistance) {
    }

    /** A rectangle in scaled GUI coordinates, with exclusive right and bottom edges. */
    public record Bounds(int left, int top, int right, int bottom) {
        public Bounds {
            if (right < left || bottom < top) {
                throw new IllegalArgumentException("bounds must not have negative dimensions");
            }
        }

        private boolean intersects(final Bounds other) {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
        }
    }
}
