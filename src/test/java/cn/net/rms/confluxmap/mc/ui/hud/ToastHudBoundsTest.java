package cn.net.rms.confluxmap.mc.ui.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.net.rms.confluxmap.core.config.HudAmbient;
import cn.net.rms.confluxmap.core.config.HudRect;
import cn.net.rms.confluxmap.core.config.HudTransform;
import org.junit.jupiter.api.Test;

class ToastHudBoundsTest {
    /** The pose the manager installs for a toast of {@code width} sitting at {@code top}. */
    private static HudAmbient placedAt(final int screenWidth, final int width, final int top) {
        return new HudAmbient(screenWidth - width, top, 1f, 1f);
    }

    @Test
    void unionsEveryToastInTheStack() {
        ToastHudBounds.beginFrame(640, 360);
        ToastHudBounds.include(160, 32, placedAt(640, 160, 0));
        ToastHudBounds.include(200, 44, placedAt(640, 200, 32));

        ToastHudBounds.beginFrame(640, 360);
        assertEquals(
            new HudRect(440, 0, 640, 76),
            ToastHudBounds.previousFrame(640, 360)
        );
    }

    @Test
    void measuresAMultiLineToastAtItsRealHeight() {
        ToastHudBounds.beginFrame(640, 360);
        // SystemToast.getHeight() is 20 + lines * 12, so three lines is 56, not the default 32.
        ToastHudBounds.include(160, 56, placedAt(640, 160, 0));

        ToastHudBounds.beginFrame(640, 360);
        final HudRect measured = ToastHudBounds.previousFrame(640, 360);
        assertEquals(56, measured.height());
        assertEquals(160, measured.width());
    }

    @Test
    void reportsBoundsWithoutTheShiftItAlreadyApplied() {
        ToastHudBounds.beginFrame(640, 360);
        ToastHudBounds.recordAppliedTransform(HudTransform.ofVerticalShift(140f));
        ToastHudBounds.include(160, 32, placedAt(640, 160, 140));

        ToastHudBounds.beginFrame(640, 360);
        assertEquals(
            new HudRect(480, 0, 640, 32),
            ToastHudBounds.previousFrame(640, 360)
        );
    }

    @Test
    void publishesNothingForAFrameThatDrewNoToasts() {
        ToastHudBounds.beginFrame(640, 360);
        ToastHudBounds.beginFrame(640, 360);
        assertNull(ToastHudBounds.previousFrame(640, 360));
    }
}
