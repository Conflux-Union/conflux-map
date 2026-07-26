package cn.net.rms.confluxmap.core.annotation;

import java.util.ArrayList;
import java.util.List;

public record FreehandAnnotationGeometry(List<AnnotationPoint> points)
    implements AnnotationGeometry {
    public static final int MAX_POINTS = 4096;

    public FreehandAnnotationGeometry {
        if (points == null || points.size() < 2 || points.size() > MAX_POINTS) {
            throw new IllegalArgumentException("freehand geometry requires 2.." + MAX_POINTS + " points");
        }
        points = List.copyOf(points);
    }

    @Override
    public AnnotationBounds bounds() {
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (final AnnotationPoint point : points) {
            minX = Math.min(minX, point.x());
            minZ = Math.min(minZ, point.z());
            maxX = Math.max(maxX, point.x());
            maxZ = Math.max(maxZ, point.z());
        }
        return new AnnotationBounds(minX, minZ, maxX, maxZ);
    }

    @Override
    public FreehandAnnotationGeometry translate(final double dx, final double dz) {
        final List<AnnotationPoint> translated = new ArrayList<>(points.size());
        for (final AnnotationPoint point : points) {
            translated.add(point.translate(dx, dz));
        }
        return new FreehandAnnotationGeometry(translated);
    }
}
