package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HudRectTest {
    @Test
    void roundsAMeasuredRectangleOutwardToWholePixels() {
        assertEquals(
            new HudRect(219, 19, 301, 101),
            HudRect.enclosing(219.4f, 19.6f, 300.1f, 100.5f)
        );
    }

    @Test
    void keepsWholePixelsExactDespiteTheFloatDivisionThatProducedThem() {
        final HudTransform applied = new HudTransform(17f, 41f, 0.7f);

        assertEquals(
            new HudRect(220, 20, 300, 100),
            HudRect.enclosing(
                applied.unapplyX(applied.scale() * 220f + applied.translateX()),
                applied.unapplyY(applied.scale() * 20f + applied.translateY()),
                applied.unapplyX(applied.scale() * 300f + applied.translateX()),
                applied.unapplyY(applied.scale() * 100f + applied.translateY())
            )
        );
    }

    @Test
    void treatsTouchingEdgesAsSeparate() {
        final HudRect left = new HudRect(0, 0, 100, 100);

        assertFalse(left.overlaps(new HudRect(100, 0, 200, 100)));
        assertFalse(left.overlaps(new HudRect(0, 100, 100, 200)));
        assertFalse(left.overlaps(null));
        assertTrue(left.overlaps(new HudRect(99, 99, 200, 200)));
    }

    @Test
    void unionsWithAnAbsentRectangleAsItself() {
        final HudRect rect = new HudRect(10, 20, 30, 40);

        assertEquals(rect, rect.union(null));
        assertEquals(
            new HudRect(5, 20, 30, 60),
            rect.union(new HudRect(5, 25, 25, 60))
        );
    }
}
