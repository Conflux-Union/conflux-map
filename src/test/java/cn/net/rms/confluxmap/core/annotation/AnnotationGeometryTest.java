package cn.net.rms.confluxmap.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnnotationGeometryTest {
    @Test
    void hitTestingUsesShapeOutlinesInsteadOfFilledBounds() {
        final RectangleAnnotationGeometry rectangle = RectangleAnnotationGeometry.between(
            new AnnotationPoint(0, 0), new AnnotationPoint(10, 20)
        );
        final CircleAnnotationGeometry circle = new CircleAnnotationGeometry(new AnnotationPoint(5, 5), 5);

        assertTrue(AnnotationGeometryOps.hit(rectangle, new AnnotationPoint(0.4, 10), 0.5));
        assertFalse(AnnotationGeometryOps.hit(rectangle, new AnnotationPoint(5, 10), 0.5));
        assertTrue(AnnotationGeometryOps.hit(circle, new AnnotationPoint(10.2, 5), 0.25));
        assertFalse(AnnotationGeometryOps.hit(circle, new AnnotationPoint(5, 5), 0.25));
    }

    @Test
    void translationMovesEveryGeometryPointAndLabelAnchor() {
        final FreehandAnnotationGeometry path = new FreehandAnnotationGeometry(List.of(
            new AnnotationPoint(-2, 3), new AnnotationPoint(4, 9), new AnnotationPoint(7, -1)
        ));

        final FreehandAnnotationGeometry moved = path.translate(10, -5);

        assertEquals(new AnnotationPoint(8, -2), moved.points().get(0));
        assertEquals(new AnnotationPoint(17, -6), moved.points().get(2));
        assertEquals(path.bounds().center().translate(10, -5), moved.bounds().center());
    }

    @Test
    void simplificationRemovesCollinearSamplesButKeepsCorners() {
        final List<AnnotationPoint> simplified = AnnotationGeometryOps.simplify(List.of(
            new AnnotationPoint(0, 0),
            new AnnotationPoint(1, 0.01),
            new AnnotationPoint(2, 0),
            new AnnotationPoint(2, 2)
        ), 0.05);

        assertEquals(List.of(
            new AnnotationPoint(0, 0), new AnnotationPoint(2, 0), new AnnotationPoint(2, 2)
        ), simplified);
    }

    @Test
    void projectionRoundTripsRotatedHudCoordinates() {
        final AnnotationProjection projection = new AnnotationProjection(
            100, -50, 64, 64, 2, 90, 128, 128
        );
        final AnnotationPoint world = new AnnotationPoint(120, -40);
        final AnnotationProjection.ScreenPoint screen = projection.project(world);

        assertEquals(59, screen.x(), 1.0e-9);
        assertEquals(74, screen.y(), 1.0e-9);
        assertEquals(world.x(), projection.unproject(screen.x(), screen.y()).x(), 1.0e-9);
        assertEquals(world.z(), projection.unproject(screen.x(), screen.y()).z(), 1.0e-9);
    }
}
