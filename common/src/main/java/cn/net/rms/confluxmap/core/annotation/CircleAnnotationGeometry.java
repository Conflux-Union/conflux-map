package cn.net.rms.confluxmap.core.annotation;

import java.util.Objects;

public record CircleAnnotationGeometry(AnnotationPoint center, double radius)
    implements AnnotationGeometry {
    public CircleAnnotationGeometry {
        Objects.requireNonNull(center, "center");
        if (!Double.isFinite(radius) || radius < 0.0) {
            throw new IllegalArgumentException("circle radius must be finite and non-negative");
        }
    }

    @Override
    public AnnotationBounds bounds() {
        return new AnnotationBounds(
            center.x() - radius, center.z() - radius,
            center.x() + radius, center.z() + radius
        );
    }

    @Override
    public CircleAnnotationGeometry translate(final double dx, final double dz) {
        return new CircleAnnotationGeometry(center.translate(dx, dz), radius);
    }
}
