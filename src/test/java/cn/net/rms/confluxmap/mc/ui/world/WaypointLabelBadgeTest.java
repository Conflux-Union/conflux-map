package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.waypoint.WaypointVerticalRelation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.util.math.MatrixStack;
import org.junit.jupiter.api.Test;

/**
 * Pins the in-world label's height badge geometry: outline plus fill, triangle tip along
 * the vertical relation, alpha folded into every color.
 */
final class WaypointLabelBadgeTest {
    private static final float HALF_SIZE = 4f;
    private static final float BADGE_X = -HALF_SIZE;
    private static final float BADGE_Y = -HALF_SIZE;
    private static final float EPS = 1e-4f;

    @Test
    void aboveEmitsOutlineAndFillTrianglesPointingUp() {
        final RecordingSink sink = new RecordingSink();

        WaypointLabelPainter.drawHeightBadge(
            WaypointVerticalRelation.ABOVE, HALF_SIZE, 1f, new MatrixStack(), sink
        );

        assertEquals(2, sink.triangles.size(), "outline and fill triangle");
        final float[] outline = sink.triangles.get(0);
        assertEquals(BADGE_X, outline[0], EPS);
        assertEquals(BADGE_Y - 2.5f, outline[1], EPS);
        assertEquals(BADGE_Y + 2f, outline[3], EPS);
        assertEquals(BADGE_Y + 2f, outline[5], EPS);
        final float[] fill = sink.triangles.get(1);
        assertEquals(BADGE_Y - 1.2f, fill[1], EPS);
        assertEquals(BADGE_Y + 1f, fill[3], EPS);
    }

    @Test
    void belowEmitsTrianglesPointingDown() {
        final RecordingSink sink = new RecordingSink();

        WaypointLabelPainter.drawHeightBadge(
            WaypointVerticalRelation.BELOW, HALF_SIZE, 1f, new MatrixStack(), sink
        );

        assertEquals(2, sink.triangles.size());
        final float[] outline = sink.triangles.get(0);
        assertEquals(BADGE_Y + 2.5f, outline[1], EPS);
        assertEquals(BADGE_Y - 2f, outline[3], EPS);
        assertEquals(BADGE_Y - 2f, outline[5], EPS);
    }

    @Test
    void levelEmitsNestedSquares() {
        final RecordingSink sink = new RecordingSink();

        WaypointLabelPainter.drawHeightBadge(
            WaypointVerticalRelation.LEVEL, HALF_SIZE, 1f, new MatrixStack(), sink
        );

        assertEquals(2, sink.rects.size(), "outline and fill square");
        final float[] outline = sink.rects.get(0);
        assertEquals(BADGE_X - 2.5f, outline[0], EPS);
        assertEquals(BADGE_Y - 2.5f, outline[1], EPS);
        assertEquals(5f, outline[2], EPS);
        assertEquals(5f, outline[3], EPS);
        final float[] fill = sink.rects.get(1);
        assertEquals(BADGE_X - 1.5f, fill[0], EPS);
        assertEquals(3f, fill[2], EPS);
    }

    @Test
    void noneEmitsNothing() {
        final RecordingSink sink = new RecordingSink();

        WaypointLabelPainter.drawHeightBadge(
            WaypointVerticalRelation.NONE, HALF_SIZE, 1f, new MatrixStack(), sink
        );

        assertTrue(sink.triangles.isEmpty());
        assertTrue(sink.rects.isEmpty());
    }

    @Test
    void alphaScalesEveryColor() {
        final RecordingSink sink = new RecordingSink();

        WaypointLabelPainter.drawHeightBadge(
            WaypointVerticalRelation.ABOVE, HALF_SIZE, 0.5f, new MatrixStack(), sink
        );

        assertEquals(2, sink.triangleColors.size());
        for (final int color : sink.triangleColors) {
            assertEquals(0x80, (color >>> 24) & 0xFF, "half alpha on triangle colors");
        }
    }

    /** Records badge geometry; colors stay ints - a float would round 0x80FFFFFF to 0x81000000. */
    private static final class RecordingSink implements WaypointLabelPainter.Sink {
        final List<float[]> triangles = new ArrayList<>();
        final List<Integer> triangleColors = new ArrayList<>();
        final List<float[]> rects = new ArrayList<>();
        final List<Integer> rectColors = new ArrayList<>();

        @Override
        public void billboard(final MatrixStack matrices, final float scale) {
        }

        @Override
        public void rect(
            final MatrixStack matrices,
            final float x, final float y, final float width, final float height, final int color
        ) {
            rects.add(new float[] {x, y, width, height});
            rectColors.add(color);
        }

        @Override
        public void triangle(
            final MatrixStack matrices,
            final float x0, final float y0,
            final float x1, final float y1,
            final float x2, final float y2,
            final int color
        ) {
            triangles.add(new float[] {x0, y0, x1, y1, x2, y2});
            triangleColors.add(color);
        }

        @Override
        public void text(
            final MatrixStack matrices, final String text, final float x, final float y, final int color
        ) {
        }
    }
}
