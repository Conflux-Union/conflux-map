package cn.net.rms.confluxmap.core.annotation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Mutable pointer gesture that produces immutable geometry only after validation. */
public final class AnnotationDraft {
    private static final double MIN_SIZE_PX = 2.0;
    private static final double FREEHAND_SAMPLE_PX = 0.5;

    private final AnnotationTool tool;
    private final AnnotationPoint start;
    private final List<AnnotationPoint> freehandPoints = new ArrayList<>();
    private AnnotationPoint current;

    public AnnotationDraft(final AnnotationTool tool, final AnnotationPoint start) {
        if (tool == AnnotationTool.SELECT || tool == AnnotationTool.ERASER) {
            throw new IllegalArgumentException("tool does not create annotation geometry");
        }
        this.tool = tool;
        this.start = start;
        this.current = start;
        if (tool == AnnotationTool.FREEHAND) {
            freehandPoints.add(start);
        }
    }

    public void dragTo(final AnnotationPoint point, final double blocksPerPixel) {
        current = point;
        if (tool == AnnotationTool.FREEHAND
            && Math.hypot(
                point.x() - freehandPoints.get(freehandPoints.size() - 1).x(),
                point.z() - freehandPoints.get(freehandPoints.size() - 1).z()
            ) >= FREEHAND_SAMPLE_PX * blocksPerPixel) {
            if (freehandPoints.size() < FreehandAnnotationGeometry.MAX_POINTS) {
                freehandPoints.add(point);
            } else {
                freehandPoints.set(freehandPoints.size() - 1, point);
            }
        }
    }

    public Optional<AnnotationGeometry> geometry(final double blocksPerPixel, final boolean finalGeometry) {
        final double minimumWorldSize = MIN_SIZE_PX * blocksPerPixel;
        return switch (tool) {
            case LINE -> distance(start, current) < minimumWorldSize
                ? Optional.empty()
                : Optional.of(new LineAnnotationGeometry(start, current));
            case CIRCLE -> distance(start, current) < minimumWorldSize
                ? Optional.empty()
                : Optional.of(new CircleAnnotationGeometry(start, distance(start, current)));
            case RECTANGLE -> Math.min(
                Math.abs(current.x() - start.x()), Math.abs(current.z() - start.z())
            ) < minimumWorldSize
                ? Optional.empty()
                : Optional.of(RectangleAnnotationGeometry.between(start, current));
            case FREEHAND -> freehandGeometry(blocksPerPixel, finalGeometry);
            case SELECT, ERASER -> Optional.empty();
        };
    }

    private Optional<AnnotationGeometry> freehandGeometry(
        final double blocksPerPixel,
        final boolean finalGeometry
    ) {
        final List<AnnotationPoint> points = new ArrayList<>(freehandPoints);
        if (points.isEmpty() || !points.get(points.size() - 1).equals(current)) {
            points.add(current);
        }
        if (points.size() < 2 || maximumSpan(points) < MIN_SIZE_PX * blocksPerPixel) {
            return Optional.empty();
        }
        final List<AnnotationPoint> result = finalGeometry
            ? AnnotationGeometryOps.simplify(points, Math.max(0.25, FREEHAND_SAMPLE_PX * blocksPerPixel))
            : List.copyOf(points);
        return result.size() < 2
            ? Optional.empty()
            : Optional.of(new FreehandAnnotationGeometry(result));
    }

    private static double distance(final AnnotationPoint first, final AnnotationPoint second) {
        return Math.hypot(first.x() - second.x(), first.z() - second.z());
    }

    private static double maximumSpan(final List<AnnotationPoint> points) {
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
        return Math.max(maxX - minX, maxZ - minZ);
    }
}
