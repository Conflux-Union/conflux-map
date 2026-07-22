package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IconOutlinerTest {
    private static final int SOLID = 0xFF000000;
    private static final int O = IconOutliner.OUTLINE;

    @Test
    void singleCenterPixelGetsFullEightNeighborRing() {
        // 3x3 input, only the center solid -> 5x5 mask with a ring in the input's own area.
        final int[] pixels = new int[9];
        pixels[4] = SOLID;
        final int[] mask = IconOutliner.outlineMask(pixels, 3, 3);
        assertArrayEquals(new int[] {
            0, 0, 0, 0, 0,
            0, O, O, O, 0,
            0, O, 0, O, 0,
            0, O, O, O, 0,
            0, 0, 0, 0, 0,
        }, mask);
    }

    @Test
    void edgeTouchingSpriteRingsIntoThePaddingApron() {
        // 2x2 fully solid input: every ring pixel lands in the 1px apron.
        final int[] pixels = {SOLID, SOLID, SOLID, SOLID};
        final int[] mask = IconOutliner.outlineMask(pixels, 2, 2);
        assertArrayEquals(new int[] {
            O, O, O, O,
            O, 0, 0, O,
            O, 0, 0, O,
            O, O, O, O,
        }, mask);
    }

    @Test
    void diagonalOnlyAdjacencyStillOutlines() {
        // Solid at (0,0) of a 2x2: the diagonally-opposite corner (1,1) is ringed too.
        final int[] pixels = {SOLID, 0, 0, 0};
        final int[] mask = IconOutliner.outlineMask(pixels, 2, 2);
        assertEquals(O, mask[2 * 4 + 2]);
    }

    @Test
    void halfVisibleCountsAsBodyButFainterReadsAsBackground() {
        final int[] faint = new int[9];
        faint[4] = 0x7F000000;
        assertArrayEquals(new int[25], IconOutliner.outlineMask(faint, 3, 3));

        final int[] half = new int[9];
        half[4] = 0x80000000;
        assertEquals(O, IconOutliner.outlineMask(half, 3, 3)[1 * 5 + 1]);
    }

    @Test
    void emptyInputProducesEmptyMask() {
        assertArrayEquals(new int[25], IconOutliner.outlineMask(new int[9], 3, 3));
    }

    @Test
    void solidPixelsAreNeverOverwrittenByTheRing() {
        // An L-shape: the concave inner corner is transparent and ringed, solid pixels stay 0.
        final int[] pixels = {
            SOLID, 0,
            SOLID, SOLID,
        };
        final int[] mask = IconOutliner.outlineMask(pixels, 2, 2);
        assertEquals(O, mask[1 * 4 + 2]);
        assertEquals(0, mask[1 * 4 + 1]);
        assertEquals(0, mask[2 * 4 + 1]);
        assertEquals(0, mask[2 * 4 + 2]);
    }
}
