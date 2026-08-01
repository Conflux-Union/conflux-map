package cn.net.rms.confluxmap.core.annotation;

import java.util.Objects;

/** Axis-aligned rectangle normalized from any two opposite corners. */
public record RectangleAnnotationGeometry(AnnotationPoint min, AnnotationPoint max)
    implements AnnotationGeometry {
    public RectangleAnnotationGeometry {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        final double minX = Math.min(min.x(), max.x());
        final double minZ = Math.min(min.z(), max.z());
        final double maxX = Math.max(min.x(), max.x());
        final double maxZ = Math.max(min.z(), max.z());
        min = new AnnotationPoint(minX, minZ);
        max = new AnnotationPoint(maxX, maxZ);
    }

    public static RectangleAnnotationGeometry between(
        final AnnotationPoint first,
        final AnnotationPoint second
    ) {
        return new RectangleAnnotationGeometry(first, second);
    }

    @Override
    public AnnotationBounds bounds() {
        return new AnnotationBounds(min.x(), min.z(), max.x(), max.z());
    }

    @Override
    public RectangleAnnotationGeometry translate(final double dx, final double dz) {
        return new RectangleAnnotationGeometry(min.translate(dx, dz), max.translate(dx, dz));
    }
}
