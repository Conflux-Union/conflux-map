package cn.net.rms.confluxmap.core.annotation;

/** Shared world-to-screen projection for fullscreen and rotated minimap annotation layers. */
public record AnnotationProjection(
    double worldCenterX,
    double worldCenterZ,
    double screenCenterX,
    double screenCenterY,
    double blocksPerPixel,
    double rotationDegrees,
    double viewportWidth,
    double viewportHeight
) {
    public AnnotationProjection {
        if (!Double.isFinite(worldCenterX) || !Double.isFinite(worldCenterZ)
            || !Double.isFinite(screenCenterX) || !Double.isFinite(screenCenterY)
            || !Double.isFinite(blocksPerPixel) || blocksPerPixel <= 0.0
            || !Double.isFinite(rotationDegrees)
            || !Double.isFinite(viewportWidth) || viewportWidth < 0.0
            || !Double.isFinite(viewportHeight) || viewportHeight < 0.0) {
            throw new IllegalArgumentException("invalid annotation projection");
        }
    }

    public ScreenPoint project(final AnnotationPoint point) {
        final double rawX = (point.x() - worldCenterX) / blocksPerPixel;
        final double rawY = (point.z() - worldCenterZ) / blocksPerPixel;
        final double radians = Math.toRadians(rotationDegrees);
        final double cos = Math.cos(radians);
        final double sin = Math.sin(radians);
        return new ScreenPoint(
            screenCenterX + rawX * cos - rawY * sin,
            screenCenterY + rawX * sin + rawY * cos
        );
    }

    public AnnotationPoint unproject(final double screenX, final double screenY) {
        final double rotatedX = screenX - screenCenterX;
        final double rotatedY = screenY - screenCenterY;
        final double radians = Math.toRadians(-rotationDegrees);
        final double cos = Math.cos(radians);
        final double sin = Math.sin(radians);
        return new AnnotationPoint(
            worldCenterX + (rotatedX * cos - rotatedY * sin) * blocksPerPixel,
            worldCenterZ + (rotatedX * sin + rotatedY * cos) * blocksPerPixel
        );
    }

    public boolean mayBeVisible(final AnnotationBounds bounds, final double pixelMargin) {
        final double radius = Math.hypot(viewportWidth, viewportHeight) / 2.0 * blocksPerPixel;
        final double worldMargin = Math.max(0.0, pixelMargin) * blocksPerPixel;
        return bounds.intersects(
            worldCenterX - radius, worldCenterZ - radius,
            worldCenterX + radius, worldCenterZ + radius,
            worldMargin
        );
    }

    public record ScreenPoint(double x, double y) {
    }
}
