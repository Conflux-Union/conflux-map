package cn.net.rms.confluxmap.core.export;

import cn.net.rms.confluxmap.core.annotation.Annotation;
import cn.net.rms.confluxmap.core.annotation.AnnotationBounds;
import cn.net.rms.confluxmap.core.annotation.AnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.AnnotationPoint;
import cn.net.rms.confluxmap.core.annotation.AnnotationStyle;
import cn.net.rms.confluxmap.core.annotation.CircleAnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.FreehandAnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.LineAnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.RectangleAnnotationGeometry;
import cn.net.rms.confluxmap.core.util.Argb;
import java.util.ArrayList;
import java.util.List;

/** Rasterizes immutable drawing snapshots into one bounded export-tile overlay. */
final class MapExportAnnotationRasterizer {
    private static final double STROKE_WIDTH = AnnotationStyle.STROKE_WIDTH_PX;
    private static final double HALF_STROKE = STROKE_WIDTH / 2.0;

    private MapExportAnnotationRasterizer() {
    }

    static int[] rasterize(
        final MapExportRequest request,
        final int startX,
        final int startY,
        final int endX,
        final int endY
    ) {
        if (request.annotations().isEmpty()) {
            return null;
        }
        final List<Annotation> visible = visibleAnnotations(
            request, startX, startY, endX, endY
        );
        if (visible.isEmpty()) {
            return null;
        }
        final int width = endX - startX + 1;
        final int[] overlay = new int[Math.multiplyExact(width, endY - startY + 1)];
        for (final Annotation annotation : visible) {
            paintGeometry(
                overlay, width, startX, startY, endX, endY,
                annotation.geometry(), annotation.style().colorArgb(), request
            );
        }
        return overlay;
    }

    private static List<Annotation> visibleAnnotations(
        final MapExportRequest request,
        final int startX,
        final int startY,
        final int endX,
        final int endY
    ) {
        final double blocksPerPixel = request.resolution().blocksPerPixel();
        final double margin = STROKE_WIDTH * blocksPerPixel;
        final double worldMinX = request.bounds().minX() + startX * blocksPerPixel;
        final double worldMinZ = request.bounds().minZ() + startY * blocksPerPixel;
        final double worldMaxX = request.bounds().minX() + (endX + 1.0) * blocksPerPixel;
        final double worldMaxZ = request.bounds().minZ() + (endY + 1.0) * blocksPerPixel;
        final List<Annotation> visible = new ArrayList<>();
        for (final Annotation annotation : request.annotations()) {
            if (annotation.geometry().bounds().intersects(
                worldMinX, worldMinZ, worldMaxX, worldMaxZ, margin
            )) {
                visible.add(annotation);
            }
        }
        return visible;
    }

    private static void paintGeometry(
        final int[] overlay,
        final int width,
        final int startX,
        final int startY,
        final int endX,
        final int endY,
        final AnnotationGeometry geometry,
        final int color,
        final MapExportRequest request
    ) {
        if (Argb.alpha(color) == 0) {
            return;
        }
        if (geometry instanceof final LineAnnotationGeometry line) {
            stroke(overlay, width, startX, startY, endX, endY,
                project(line.start(), request), project(line.end(), request), color);
        } else if (geometry instanceof final CircleAnnotationGeometry circle) {
            circle(overlay, width, startX, startY, endX, endY, circle, color, request);
        } else if (geometry instanceof final RectangleAnnotationGeometry rectangle) {
            rectangle(overlay, width, startX, startY, endX, endY, rectangle, color, request);
        } else if (geometry instanceof final FreehandAnnotationGeometry freehand) {
            for (int index = 1; index < freehand.points().size(); index++) {
                stroke(overlay, width, startX, startY, endX, endY,
                    project(freehand.points().get(index - 1), request),
                    project(freehand.points().get(index), request), color);
            }
        }
    }

    private static void rectangle(
        final int[] overlay,
        final int width,
        final int startX,
        final int startY,
        final int endX,
        final int endY,
        final RectangleAnnotationGeometry rectangle,
        final int color,
        final MapExportRequest request
    ) {
        final AnnotationPoint min = rectangle.min();
        final AnnotationPoint max = rectangle.max();
        final PixelPoint topLeft = project(min, request);
        final PixelPoint topRight = project(new AnnotationPoint(max.x(), min.z()), request);
        final PixelPoint bottomRight = project(max, request);
        final PixelPoint bottomLeft = project(new AnnotationPoint(min.x(), max.z()), request);
        stroke(overlay, width, startX, startY, endX, endY, topLeft, topRight, color);
        stroke(overlay, width, startX, startY, endX, endY, topRight, bottomRight, color);
        stroke(overlay, width, startX, startY, endX, endY, bottomRight, bottomLeft, color);
        stroke(overlay, width, startX, startY, endX, endY, bottomLeft, topLeft, color);
    }

    private static void circle(
        final int[] overlay,
        final int width,
        final int startX,
        final int startY,
        final int endX,
        final int endY,
        final CircleAnnotationGeometry circle,
        final int color,
        final MapExportRequest request
    ) {
        final PixelPoint center = project(circle.center(), request);
        final double outerRadius = circle.radius() / request.resolution().blocksPerPixel();
        if (outerRadius <= 0.0) {
            return;
        }
        final double innerRadius = Math.max(0.0, outerRadius - STROKE_WIDTH);
        final double innerSquared = innerRadius * innerRadius;
        final double outerSquared = outerRadius * outerRadius;
        final int minX = Math.max(startX, floorToInt(center.x() - outerRadius - 1.0));
        final int maxX = Math.min(endX, ceilToInt(center.x() + outerRadius));
        final int minY = Math.max(startY, floorToInt(center.y() - outerRadius - 1.0));
        final int maxY = Math.min(endY, ceilToInt(center.y() + outerRadius));
        for (int y = minY; y <= maxY; y++) {
            final double dy = y + 0.5 - center.y();
            for (int x = minX; x <= maxX; x++) {
                final double dx = x + 0.5 - center.x();
                final double distanceSquared = dx * dx + dy * dy;
                if (distanceSquared >= innerSquared && distanceSquared <= outerSquared) {
                    paintPixel(overlay, width, startX, startY, x, y, color);
                }
            }
        }
    }

    private static void stroke(
        final int[] overlay,
        final int width,
        final int startX,
        final int startY,
        final int endX,
        final int endY,
        final PixelPoint first,
        final PixelPoint second,
        final int color
    ) {
        final double dx = second.x() - first.x();
        final double dy = second.y() - first.y();
        final double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0.0) {
            return;
        }
        final int minX = Math.max(startX, floorToInt(Math.min(first.x(), second.x()) - HALF_STROKE - 1.0));
        final int maxX = Math.min(endX, ceilToInt(Math.max(first.x(), second.x()) + HALF_STROKE));
        final int minY = Math.max(startY, floorToInt(Math.min(first.y(), second.y()) - HALF_STROKE - 1.0));
        final int maxY = Math.min(endY, ceilToInt(Math.max(first.y(), second.y()) + HALF_STROKE));
        final double radiusSquared = HALF_STROKE * HALF_STROKE;
        for (int y = minY; y <= maxY; y++) {
            final double py = y + 0.5;
            for (int x = minX; x <= maxX; x++) {
                final double px = x + 0.5;
                final double projection = ((px - first.x()) * dx + (py - first.y()) * dy)
                    / lengthSquared;
                if (projection < 0.0 || projection > 1.0) {
                    continue;
                }
                final double nearestX = first.x() + projection * dx;
                final double nearestY = first.y() + projection * dy;
                final double distanceX = px - nearestX;
                final double distanceY = py - nearestY;
                if (distanceX * distanceX + distanceY * distanceY <= radiusSquared) {
                    paintPixel(overlay, width, startX, startY, x, y, color);
                }
            }
        }
    }

    private static void paintPixel(
        final int[] overlay,
        final int width,
        final int startX,
        final int startY,
        final int x,
        final int y,
        final int color
    ) {
        final int index = (y - startY) * width + x - startX;
        overlay[index] = Argb.over(color, overlay[index]);
    }

    private static PixelPoint project(
        final AnnotationPoint point,
        final MapExportRequest request
    ) {
        final double blocksPerPixel = request.resolution().blocksPerPixel();
        return new PixelPoint(
            (point.x() - request.bounds().minX()) / blocksPerPixel,
            (point.z() - request.bounds().minZ()) / blocksPerPixel
        );
    }

    private static int floorToInt(final double value) {
        return (int) Math.floor(value);
    }

    private static int ceilToInt(final double value) {
        return (int) Math.ceil(value);
    }

    private record PixelPoint(double x, double y) {
    }
}
