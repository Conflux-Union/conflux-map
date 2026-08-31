package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WaypointColorSelectionTest {
    @Test
    void presetSelectionPreservesAndCanRestoreTheCustomColor() {
        final WaypointColorSelection selection = new WaypointColorSelection(0xFF3498DB);

        selection.updateCustom(0xFFFF0000);
        selection.selectPreset(0xFF00FF00);

        assertEquals(0xFF00FF00, selection.selected());
        assertEquals(0xFFFF0000, selection.custom());

        selection.selectCustom();

        assertEquals(0xFFFF0000, selection.selected());
    }
}
