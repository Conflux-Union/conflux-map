package cn.net.rms.confluxmap.core.trail;

/** World-to-screen projection shared by the minimap and fullscreen player-trail overlays. */
public final class PlayerTrailProjection {
    private final double worldCenterX;
    private final double worldCenterZ;
    private final double screenCenterX;
    private final double screenCenterY;
    private final double blocksPerPixel;
    private final double viewportWidth;
    private final double viewportHeight;
    private final double rotationCos;
    private final double rotationSin;

    public PlayerTrailProjection(
        final double worldCenterX,
        final double worldCenterZ,
        final double screenCenterX,
        final double screenCenterY,
        final double blocksPerPixel,
        final double rotationDegrees,
        final double viewportWidth,
        final double viewportHeight
    ) {
        if (!Double.isFinite(worldCenterX) || !Double.isFinite(worldCenterZ)
            || !Double.isFinite(screenCenterX) || !Double.isFinite(screenCenterY)
            || !Double.isFinite(blocksPerPixel) || blocksPerPixel <= 0.0
            || !Double.isFinite(rotationDegrees)
            || !Double.isFinite(viewportWidth) || viewportWidth < 0.0
            || !Double.isFinite(viewportHeight) || viewportHeight < 0.0) {
            throw new IllegalArgumentException("invalid player trail projection");
        }
        this.worldCenterX = worldCenterX;
        this.worldCenterZ = worldCenterZ;
        this.screenCenterX = screenCenterX;
        this.screenCenterY = screenCenterY;
        this.blocksPerPixel = blocksPerPixel;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        final double radians = Math.toRadians(rotationDegrees);
        this.rotationCos = Math.cos(radians);
        this.rotationSin = Math.sin(radians);
    }

    public ScreenPoint project(final PlayerTrail.Sample sample) {
        final double rawX = (sample.x() - worldCenterX) / blocksPerPixel;
        final double rawY = (sample.z() - worldCenterZ) / blocksPerPixel;
        return new ScreenPoint(
            screenCenterX + rawX * rotationCos - rawY * rotationSin,
            screenCenterY + rawX * rotationSin + rawY * rotationCos
        );
    }

    public boolean visible(final ScreenPoint point, final double pixelMargin) {
        final double margin = Math.max(0.0, pixelMargin);
        return point.x() >= screenCenterX - viewportWidth / 2.0 - margin
            && point.x() <= screenCenterX + viewportWidth / 2.0 + margin
            && point.y() >= screenCenterY - viewportHeight / 2.0 - margin
            && point.y() <= screenCenterY + viewportHeight / 2.0 + margin;
    }

    public record ScreenPoint(double x, double y) {
    }
}
