package cn.net.rms.confluxmap.core.annotation;

import java.util.ArrayList;
import java.util.List;

/** Deterministic geometry operations shared by input handling and tests. */
public final class AnnotationGeometryOps {
    private AnnotationGeometryOps() {
    }

    public static boolean hit(
        final AnnotationGeometry geometry,
        final AnnotationPoint point,
        final double tolerance
    ) {
        if (!Double.isFinite(tolerance) || tolerance < 0.0) {
            throw new IllegalArgumentException("hit tolerance must be finite and non-negative");
        }
        final AnnotationBounds bounds = geometry.bounds();
        if (!bounds.intersects(point.x(), point.z(), point.x(), point.z(), tolerance)) {
            return false;
        }
        if (geometry instanceof final LineAnnotationGeometry line) {
            return segmentDistance(point, line.start(), line.end()) <= tolerance;
        }
        if (geometry instanceof final CircleAnnotationGeometry circle) {
            return Math.abs(distance(point, circle.center()) - circle.radius()) <= tolerance;
        }
        if (geometry instanceof final RectangleAnnotationGeometry rectangle) {
            final AnnotationPoint min = rectangle.min();
            final AnnotationPoint max = rectangle.max();
            return segmentDistance(point, min, new AnnotationPoint(max.x(), min.z())) <= tolerance
                || segmentDistance(point, new AnnotationPoint(max.x(), min.z()), max) <= tolerance
                || segmentDistance(point, max, new AnnotationPoint(min.x(), max.z())) <= tolerance
                || segmentDistance(point, new AnnotationPoint(min.x(), max.z()), min) <= tolerance;
        }
        if (geometry instanceof final FreehandAnnotationGeometry freehand) {
            for (int index = 1; index < freehand.points().size(); index++) {
                if (segmentDistance(point, freehand.points().get(index - 1), freehand.points().get(index))
                    <= tolerance) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Douglas-Peucker simplification, retaining first and last points. */
    public static List<AnnotationPoint> simplify(
        final List<AnnotationPoint> points,
        final double tolerance
    ) {
        if (points == null || points.size() <= 2 || tolerance <= 0.0) {
            return points == null ? List.of() : List.copyOf(points);
        }
        final boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        simplify(points, 0, points.size() - 1, tolerance, keep);
        final List<AnnotationPoint> result = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            if (keep[index]) {
                result.add(points.get(index));
            }
        }
        return List.copyOf(result);
    }

    private static void simplify(
        final List<AnnotationPoint> points,
        final int first,
        final int last,
        final double tolerance,
        final boolean[] keep
    ) {
        double furthestDistance = -1.0;
        int furthestIndex = -1;
        for (int index = first + 1; index < last; index++) {
            final double candidate = segmentDistance(points.get(index), points.get(first), points.get(last));
            if (candidate > furthestDistance) {
                furthestDistance = candidate;
                furthestIndex = index;
            }
        }
        if (furthestIndex >= 0 && furthestDistance > tolerance) {
            keep[furthestIndex] = true;
            simplify(points, first, furthestIndex, tolerance, keep);
            simplify(points, furthestIndex, last, tolerance, keep);
        }
    }

    private static double segmentDistance(
        final AnnotationPoint point,
        final AnnotationPoint start,
        final AnnotationPoint end
    ) {
        final double dx = end.x() - start.x();
        final double dz = end.z() - start.z();
        final double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared == 0.0) {
            return distance(point, start);
        }
        final double projection = Math.max(0.0, Math.min(1.0,
            ((point.x() - start.x()) * dx + (point.z() - start.z()) * dz) / lengthSquared
        ));
        return Math.hypot(
            point.x() - (start.x() + projection * dx),
            point.z() - (start.z() + projection * dz)
        );
    }

    private static double distance(final AnnotationPoint first, final AnnotationPoint second) {
        return Math.hypot(first.x() - second.x(), first.z() - second.z());
    }
}
