package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ConfigTabLayoutTest {
    @Test
    void wrapsCategoryTabsBeforeLabelsBecomeTooNarrow() {
        final ConfigTabLayout narrow = ConfigTabLayout.fit(320, 6, 8, 4, 24, 20);

        assertEquals(3, narrow.columns());
        assertEquals(2, narrow.rows());
        assertEquals(76, narrow.contentTop());
    }

    @Test
    void keepsOneRowWhenTheScreenIsWideEnough() {
        final ConfigTabLayout wide = ConfigTabLayout.fit(854, 6, 8, 4, 24, 20);

        assertEquals(6, wide.columns());
        assertEquals(1, wide.rows());
        assertEquals(52, wide.contentTop());
    }
}
