package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Cross-LOD fallback mapping: parent coordinates and quadrant UVs used to stretch a coarser tile
 * over a missing finer one, and the four child coordinates used to stitch finer tiles into a
 * missing coarser one. Negative tiles must round toward negative infinity, not toward zero.
 */
class TileMathLodFallbackTest {
    private static final float[] EXPECTED_UV = new float[4];

    @Test
    void parentOfPositiveTileIsFloorHalved() {
        assertEquals(0, TileMath.parentTile(0));
        assertEquals(0, TileMath.parentTile(1));
        assertEquals(1, TileMath.parentTile(2));
        assertEquals(1, TileMath.parentTile(3));
        assertEquals(5, TileMath.parentTile(10));
        assertEquals(5, TileMath.parentTile(11));
    }

    @Test
    void parentOfNegativeTileRoundsTowardNegativeInfinity() {
        assertEquals(-1, TileMath.parentTile(-1));
        assertEquals(-1, TileMath.parentTile(-2));
        assertEquals(-2, TileMath.parentTile(-3));
        assertEquals(-2, TileMath.parentTile(-4));
        assertEquals(-6, TileMath.parentTile(-11));
        assertEquals(-6, TileMath.parentTile(-12));
    }

    @Test
    void quadrantSplitsEachParentIntoWestEastAndNorthSouthHalves() {
        assertEquals(0, TileMath.quadrantInParent(0));
        assertEquals(1, TileMath.quadrantInParent(1));
        assertEquals(0, TileMath.quadrantInParent(2));
        assertEquals(0, TileMath.quadrantInParent(10));
        assertEquals(1, TileMath.quadrantInParent(11));
        // Negative tiles: the odd ones (…,-3,-1) are the east/south half of their parent.
        assertEquals(1, TileMath.quadrantInParent(-1));
        assertEquals(0, TileMath.quadrantInParent(-2));
        assertEquals(1, TileMath.quadrantInParent(-3));
        assertEquals(0, TileMath.quadrantInParent(-4));
    }

    @Test
    void parentUvCoversTheMatchingHalfOnEachAxis() {
        assertUv(0, 0, 0.0f, 0.0f, 0.5f, 0.5f);
        assertUv(1, 0, 0.5f, 0.0f, 1.0f, 0.5f);
        assertUv(0, 1, 0.0f, 0.5f, 0.5f, 1.0f);
        assertUv(1, 1, 0.5f, 0.5f, 1.0f, 1.0f);
        // Same quadrants expressed from the negative side of the number line.
        assertUv(-2, -2, 0.0f, 0.0f, 0.5f, 0.5f);
        assertUv(-1, -2, 0.5f, 0.0f, 1.0f, 0.5f);
        assertUv(-2, -1, 0.0f, 0.5f, 0.5f, 1.0f);
        assertUv(-1, -1, 0.5f, 0.5f, 1.0f, 1.0f);
    }

    @Test
    void childTilesReconstructAllFourQuadrants() {
        assertEquals(4, TileMath.childTile(2, 0));
        assertEquals(5, TileMath.childTile(2, 1));
        // Quadrant values other than 0 collapse to the positive half.
        assertEquals(5, TileMath.childTile(2, 7));
        assertEquals(-4, TileMath.childTile(-2, 0));
        assertEquals(-3, TileMath.childTile(-2, 1));
        assertEquals(-2, TileMath.childTile(-1, 0));
        assertEquals(-1, TileMath.childTile(-1, 1));
    }

    @Test
    void parentAndChildrenRoundTripThroughEveryQuadrant() {
        for (int parent = -5; parent <= 5; parent++) {
            for (int quadrant = 0; quadrant <= 1; quadrant++) {
                final int child = TileMath.childTile(parent, quadrant);
                assertEquals(parent, TileMath.parentTile(child));
                assertEquals(quadrant, TileMath.quadrantInParent(child));
            }
        }
    }

    private static void assertUv(
        final int tileX,
        final int tileZ,
        final float u0,
        final float v0,
        final float u1,
        final float v1
    ) {
        final float[] uv = new float[4];
        TileMath.parentUv(tileX, tileZ, uv);
        EXPECTED_UV[0] = u0;
        EXPECTED_UV[1] = v0;
        EXPECTED_UV[2] = u1;
        EXPECTED_UV[3] = v1;
        assertArrayEquals(EXPECTED_UV, uv);
    }
}
