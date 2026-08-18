package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HudAmbientTest {
    @Test
    void mapsAPaintedRectangleToRenderedPixels() {
        final HudAmbient halved = new HudAmbient(320f, 0f, 0.5f, 0.5f);

        assertEquals(
            new HudRect(570, 20, 640, 80),
            halved.apply(new HudRect(500, 40, 640, 160))
        );
        assertEquals(null, halved.apply(null));
    }

    @Test
    void composesTheInnerStackWithTheOneWrappingIt() {
        final HudAmbient inner = new HudAmbient(10f, 20f, 2f, 2f);
        final HudAmbient outer = new HudAmbient(5f, 5f, 3f, 3f);

        final HudAmbient combined = inner.then(outer);

        assertEquals(outer.applyX(inner.applyX(7f)), combined.applyX(7f), 1e-3f);
        assertEquals(outer.applyY(inner.applyY(7f)), combined.applyY(7f), 1e-3f);
        assertEquals(inner, inner.then(HudAmbient.IDENTITY));
        assertEquals(inner, inner.then(null));
    }
}
