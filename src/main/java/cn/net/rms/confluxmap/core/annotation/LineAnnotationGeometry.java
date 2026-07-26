package cn.net.rms.confluxmap.core.annotation;

import java.util.Objects;

public record LineAnnotationGeometry(AnnotationPoint start, AnnotationPoint end)
    implements AnnotationGeometry {
    public LineAnnotationGeometry {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }

    @Override
    public AnnotationBounds bounds() {
        return new AnnotationBounds(
            Math.min(start.x(), end.x()), Math.min(start.z(), end.z()),
            Math.max(start.x(), end.x()), Math.max(start.z(), end.z())
        );
    }

    @Override
    public LineAnnotationGeometry translate(final double dx, final double dz) {
        return new LineAnnotationGeometry(start.translate(dx, dz), end.translate(dx, dz));
    }
}
