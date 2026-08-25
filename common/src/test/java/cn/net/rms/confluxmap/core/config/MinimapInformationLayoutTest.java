package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MinimapInformationLayoutTest {
    @Test
    void reservesNoSpaceWhenAllInformationLinesAreHidden() {
        assertEquals(0, MinimapInformationLayout.height(false, false, false));
    }

    @Test
    void reservesOneGapAndTheVisibleLineHeights() {
        assertEquals(13, MinimapInformationLayout.height(true, false, false));
        assertEquals(33, MinimapInformationLayout.height(true, true, true));
    }

    @Test
    void reportsInformationAboveAMinimapNearTheBottomEdge() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(508, 228, 128);

        assertEquals(
            new HudRect(508, 195, 636, 356),
            MinimapInformationLayout.visualBounds(minimap, 360, 33)
        );
    }
}
