package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FullscreenMapToolPanelTest {
    @Test
    void startsCollapsedAndKeepsOnlyOneGroupOpen() {
        final FullscreenMapToolPanel panel = new FullscreenMapToolPanel();

        assertNull(panel.openGroup());
        panel.toggle(FullscreenMapToolPanel.Group.VIEW);
        assertTrue(panel.isOpen(FullscreenMapToolPanel.Group.VIEW));

        panel.toggle(FullscreenMapToolPanel.Group.DRAWING);
        assertFalse(panel.isOpen(FullscreenMapToolPanel.Group.VIEW));
        assertTrue(panel.isOpen(FullscreenMapToolPanel.Group.DRAWING));
    }

    @Test
    void togglingTheOpenGroupCollapsesIt() {
        final FullscreenMapToolPanel panel = new FullscreenMapToolPanel();
        panel.toggle(FullscreenMapToolPanel.Group.ACTIONS);

        panel.toggle(FullscreenMapToolPanel.Group.ACTIONS);

        assertNull(panel.openGroup());
        assertFalse(panel.close());
    }
}
