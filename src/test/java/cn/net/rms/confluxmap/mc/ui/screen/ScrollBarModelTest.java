package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ScrollBarModelTest {
    @Test
    void thumbSizeAndPositionRepresentVisibleRowsAndOffset() {
        final ScrollBarModel model = ScrollBarModel.of(100, 120, 10, 4, 3);

        assertTrue(model.visible());
        assertEquals(48, model.thumbHeight());
        assertEquals(136, model.thumbTop());
        assertEquals(3, model.offsetForThumbTop(136));
        assertEquals(6, model.offsetForThumbTop(999));
    }

    @Test
    void scrollbarIsHiddenWhenEveryRowFits() {
        assertFalse(ScrollBarModel.of(100, 120, 4, 4, 0).visible());
    }
}
