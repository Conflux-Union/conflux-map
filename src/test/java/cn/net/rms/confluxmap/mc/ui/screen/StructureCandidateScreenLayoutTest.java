package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StructureCandidateScreenLayoutTest {
    @Test
    void keepsPreviewAndCandidateListSideBySideWhenGuiScaleLeavesLittleHeight() {
        assertTrue(StructureCandidateScreen.prefersSideBySideLayout(408, 102));
    }

    @Test
    void keepsTheStackedLayoutForNarrowScreensThatHaveEnoughVerticalSpace() {
        assertFalse(StructureCandidateScreen.prefersSideBySideLayout(408, 210));
    }
}
