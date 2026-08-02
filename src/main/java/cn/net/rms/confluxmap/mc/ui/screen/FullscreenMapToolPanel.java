package cn.net.rms.confluxmap.mc.ui.screen;

/** Exclusive primary-tool group state for the fullscreen map. */
final class FullscreenMapToolPanel {
    enum Group {
        VIEW,
        WAYPOINTS,
        DRAWING,
        ACTIONS
    }

    private Group openGroup;

    Group openGroup() {
        return openGroup;
    }

    boolean isOpen(final Group group) {
        return openGroup == group;
    }

    void toggle(final Group group) {
        openGroup = openGroup == group ? null : group;
    }

    boolean close() {
        if (openGroup == null) {
            return false;
        }
        openGroup = null;
        return true;
    }
}
