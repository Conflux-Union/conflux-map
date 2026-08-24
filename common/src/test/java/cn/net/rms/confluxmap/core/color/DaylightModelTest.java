package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DaylightModelTest {
    @Test
    void gammaParticipatesInTheRelightState() {
        final DaylightModel model = new DaylightModel();

        assertFalse(model.update(1f, 0f));
        assertTrue(model.update(1f, 16f));
        assertEquals(16f, model.gamma());
        assertFalse(model.update(1f, 16f));
    }

    @Test
    void invalidGammaFallsBackToNeutralLighting() {
        final DaylightModel model = new DaylightModel();

        model.update(1f, Float.NaN);

        assertEquals(0f, model.gamma());
    }
}
