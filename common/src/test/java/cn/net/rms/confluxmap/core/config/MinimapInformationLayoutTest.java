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
}
