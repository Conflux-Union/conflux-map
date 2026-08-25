package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.util.Argb;
import org.junit.jupiter.api.Test;

class LightTintTest {
    private static final int BASE = 0xFF806040;

    @Test
    void zeroGammaPreservesBakedCaveLighting() {
        final int baked = Argb.multiply(BASE, LightTint.multiplier(0, 0, false));

        assertEquals(baked, LightTint.applyGammaOverBakedLight(baked, 0, false, 0f));
    }

    @Test
    void tweakerooFullbrightRestoresAnUnlitCaveColor() {
        final int baked = Argb.multiply(BASE, LightTint.multiplier(0, 0, false));
        final int brightened = LightTint.applyGammaOverBakedLight(
            baked, 0, false, 16f
        );

        assertChannelsClose(BASE, brightened, 2);
    }

    private static void assertChannelsClose(
        final int expected,
        final int actual,
        final int tolerance
    ) {
        assertEquals(Argb.alpha(expected), Argb.alpha(actual));
        assertTrue(Math.abs(Argb.red(expected) - Argb.red(actual)) <= tolerance);
        assertTrue(Math.abs(Argb.green(expected) - Argb.green(actual)) <= tolerance);
        assertTrue(Math.abs(Argb.blue(expected) - Argb.blue(actual)) <= tolerance);
    }
}
