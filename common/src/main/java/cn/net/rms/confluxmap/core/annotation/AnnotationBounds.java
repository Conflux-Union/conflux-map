package cn.net.rms.confluxmap.core.annotation;

/** Axis-aligned world-coordinate bounds used for culling, labels, and selection. */
public record AnnotationBounds(double minX, double minZ, double maxX, double maxZ) {
    public AnnotationBounds {
        if (!Double.isFinite(minX) || !Double.isFinite(minZ)
            || !Double.isFinite(maxX) || !Double.isFinite(maxZ)
            || minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("invalid annotation bounds");
        }
    }

    public AnnotationPoint center() {
        return new AnnotationPoint((minX + maxX) / 2.0, (minZ + maxZ) / 2.0);
    }

    public boolean intersects(
        final double viewportMinX,
        final double viewportMinZ,
        final double viewportMaxX,
        final double viewportMaxZ,
        final double margin
    ) {
        return maxX + margin >= viewportMinX && minX - margin <= viewportMaxX
            && maxZ + margin >= viewportMinZ && minZ - margin <= viewportMaxZ;
    }
}
