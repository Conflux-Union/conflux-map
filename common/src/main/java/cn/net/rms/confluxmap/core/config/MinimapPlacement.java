package cn.net.rms.confluxmap.core.config;

/**
 * Resolution-independent minimap placement shared by config migration, HUD rendering, and the
 * placement screen. Positions describe the map's top-left corner as a fraction of the available
 * travel after applying the normal HUD margin.
 */
public final class MinimapPlacement {
    public static final int MARGIN = 4;

    private MinimapPlacement() {
    }

    /** Resolves a saved relative position into a fully visible screen-space layout. */
    public static Layout resolve(
        final int screenWidth,
        final int screenHeight,
        final int requestedSize,
        final double positionX,
        final double positionY
    ) {
        final int safeWidth = Math.max(1, screenWidth);
        final int safeHeight = Math.max(1, screenHeight);
        final int maxSize = Math.max(1, Math.min(safeWidth, safeHeight) - MARGIN * 2);
        final int size = Math.max(1, Math.min(requestedSize, maxSize));
        final Axis horizontal = axis(safeWidth, size);
        final Axis vertical = axis(safeHeight, size);
        return new Layout(
            horizontal.origin(positionX),
            vertical.origin(positionY),
            size
        );
    }

    /** Converts a dragged top-left origin back into a persistent relative position. */
    public static Position positionForOrigin(
        final int screenWidth,
        final int screenHeight,
        final int requestedSize,
        final double originX,
        final double originY
    ) {
        final Layout layout = resolve(screenWidth, screenHeight, requestedSize, 0.0, 0.0);
        final Axis horizontal = axis(Math.max(1, screenWidth), layout.size());
        final Axis vertical = axis(Math.max(1, screenHeight), layout.size());
        return new Position(
            horizontal.position(originX),
            vertical.position(originY)
        );
    }

    /** Starts a placement drag only when the pointer is over the rendered minimap. */
    public static Drag startDrag(final Layout layout, final double mouseX, final double mouseY) {
        if (!layout.contains(mouseX, mouseY)) {
            return null;
        }
        return new Drag(mouseX - layout.x(), mouseY - layout.y());
    }

    /** Applies a pointer drag while preserving where inside the minimap the user grabbed it. */
    public static Position dragTo(
        final int screenWidth,
        final int screenHeight,
        final int requestedSize,
        final Drag drag,
        final double mouseX,
        final double mouseY
    ) {
        return positionForOrigin(
            screenWidth,
            screenHeight,
            requestedSize,
            mouseX - drag.offsetX(),
            mouseY - drag.offsetY()
        );
    }

    /** Moves a saved position by screen pixels, clamping it to the reachable area. */
    public static Position nudge(
        final int screenWidth,
        final int screenHeight,
        final int requestedSize,
        final Position position,
        final int deltaX,
        final int deltaY
    ) {
        final Layout layout = resolve(screenWidth, screenHeight, requestedSize, position.x(), position.y());
        return positionForOrigin(
            screenWidth,
            screenHeight,
            requestedSize,
            layout.x() + deltaX,
            layout.y() + deltaY
        );
    }

    /** Maps the removed four-corner setting to the exact same initial placement. */
    public static Position fromLegacyCorner(final ConfluxConfig.Corner corner) {
        if (corner == null) {
            return new Position(1.0, 0.0);
        }
        switch (corner) {
            case TOP_LEFT:
                return new Position(0.0, 0.0);
            case BOTTOM_LEFT:
                return new Position(0.0, 1.0);
            case BOTTOM_RIGHT:
                return new Position(1.0, 1.0);
            default:
                return new Position(1.0, 0.0);
        }
    }

    /** Clamps hand-edited or non-finite config values to the persistent coordinate domain. */
    public static Position normalize(final double positionX, final double positionY) {
        return new Position(normalizeCoordinate(positionX, 1.0), normalizeCoordinate(positionY, 0.0));
    }

    private static Axis axis(final int screenExtent, final int size) {
        final int maximumOrigin = Math.max(0, screenExtent - size);
        final int margin = Math.min(MARGIN, maximumOrigin / 2);
        return new Axis(margin, Math.max(0, maximumOrigin - margin * 2));
    }

    private static double normalizeCoordinate(final double value, final double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record Position(double x, double y) {
    }

    public record Drag(double offsetX, double offsetY) {
    }

    public record Layout(int x, int y, int size) {
        public boolean contains(final double screenX, final double screenY) {
            return screenX >= x && screenX < x + size && screenY >= y && screenY < y + size;
        }
    }

    private record Axis(int margin, int travel) {
        private int origin(final double position) {
            return margin + (int) Math.round(normalizeCoordinate(position, 0.0) * travel);
        }

        private double position(final double origin) {
            if (travel == 0) {
                return 0.5;
            }
            return normalizeCoordinate((origin - margin) / travel, 0.0);
        }
    }
}
