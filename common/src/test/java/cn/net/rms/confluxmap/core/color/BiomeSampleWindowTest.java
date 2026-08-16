package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BiomeSampleWindowTest {
    private static final int LAST = 15;

    @Test
    void aCompleteNeighborhoodSamplesEveryColumnWhereItSits() {
        final BiomeSampleWindow window = BiomeSampleWindow.of(2, true, true, true, true);

        assertTrue(window.full());
        assertEquals(0, window.clampLocalX(0));
        assertEquals(LAST, window.clampLocalZ(LAST));
    }

    @Test
    void blendingTurnedOffNeedsNoWindowEvenWithEveryNeighborMissing() {
        final BiomeSampleWindow window = BiomeSampleWindow.of(0, false, false, false, false);

        assertTrue(window.full());
    }

    @Test
    void aBlockedSideInsetsOnlyThatSide() {
        final BiomeSampleWindow window = BiomeSampleWindow.of(2, false, true, true, true);

        assertFalse(window.full());
        assertEquals(2, window.clampLocalX(0));
        assertEquals(2, window.clampLocalX(1));
        assertEquals(7, window.clampLocalX(7));
        assertEquals(LAST, window.clampLocalX(LAST));
        assertEquals(0, window.clampLocalZ(0));
        assertEquals(LAST, window.clampLocalZ(LAST));
    }

    @Test
    void everySampledColumnKeepsItsBlendSquareInsideTheChunkOnBlockedSides() {
        for (int blendRadius = 1; blendRadius <= 7; blendRadius++) {
            final BiomeSampleWindow window = BiomeSampleWindow.of(blendRadius, false, false, false, false);
            for (int local = 0; local <= LAST; local++) {
                final int x = window.clampLocalX(local);
                final int z = window.clampLocalZ(local);
                assertTrue(x - blendRadius >= 0 && x + blendRadius <= LAST, "x escaped at radius " + blendRadius);
                assertTrue(z - blendRadius >= 0 && z + blendRadius <= LAST, "z escaped at radius " + blendRadius);
            }
        }
    }

    @Test
    void theWidestBlendRadiusLeavesTheTwoCenterColumns() {
        final BiomeSampleWindow window = BiomeSampleWindow.of(7, false, false, false, false);

        assertEquals(7, window.clampLocalX(0));
        assertEquals(8, window.clampLocalX(LAST));
    }
}
