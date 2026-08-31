package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WaypointColorPickerModelTest {
    @Test
    void convertsPrimaryHuesToOpaqueArgb() {
        assertEquals(0xFFFF0000, WaypointColorPickerModel.fromHsv(0.0f, 1.0f, 1.0f));
        assertEquals(0xFF00FF00, WaypointColorPickerModel.fromHsv(1.0f / 3.0f, 1.0f, 1.0f));
        assertEquals(0xFF0000FF, WaypointColorPickerModel.fromHsv(2.0f / 3.0f, 1.0f, 1.0f));
    }

    @Test
    void restoresHsvFromAnExistingWaypointColor() {
        final WaypointColorPickerModel model = new WaypointColorPickerModel(0xFF3498DB);

        assertEquals(0xFF3498DB, model.colorArgb());
        assertEquals("#3498DB", model.hex());
    }

    @Test
    void appliesSixDigitHexAndRejectsInvalidInput() {
        final WaypointColorPickerModel model = new WaypointColorPickerModel(0xFFFFFFFF);

        assertTrue(model.setHex("#123abc"));
        assertEquals(0xFF123ABC, model.colorArgb());
        assertFalse(model.setHex("not-a-color"));
        assertEquals(0xFF123ABC, model.colorArgb());
    }

    @Test
    void clampsPickerCoordinatesToTheirBounds() {
        final WaypointColorPickerModel model = new WaypointColorPickerModel(0xFFFFFFFF);

        model.selectHue(150.0, 100, 100);
        model.selectSaturationValue(-20.0, 130.0, 100, 100);

        assertEquals(0xFF000000, model.colorArgb());
    }
}
