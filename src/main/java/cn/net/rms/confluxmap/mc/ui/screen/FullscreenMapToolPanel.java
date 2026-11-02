package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.mc.ui.anim.PopupReveal;

/** Exclusive primary-tool group state for the fullscreen map. */
final class FullscreenMapToolPanel {
    enum Group {
        VIEW,
        WAYPOINTS,
        DRAWING,
        ACTIONS
    }

    private Group openGroup;
    /** Reveal of the expansion; lives here so widget-list rebuilds cannot drop it. */
    private final PopupReveal reveal = new PopupReveal();

    Group openGroup() {
        return openGroup;
    }

    boolean isOpen(final Group group) {
        return openGroup == group;
    }

    void toggle(final Group group) {
        openGroup = openGroup == group ? null : group;
        reveal.setOpen(openGroup != null);
    }

    boolean close() {
        if (openGroup == null) {
            return false;
        }
        openGroup = null;
        reveal.setOpen(false);
        return true;
    }

    /**
     * Advances the expansion reveal one render frame. Groups open only through user input,
     * always after the screen's own open reveal has settled, so this never overlaps the
     * reveal alpha ConfluxScreen installs.
     */
    void tickReveal(final boolean transitionsEnabled, final float durationSeconds) {
        reveal.tick(transitionsEnabled, durationSeconds);
    }

    /** True while the expansion fade is in flight; a settled panel pays no per-frame cost. */
    boolean revealAnimating() {
        return reveal.animating();
    }

    /** Eased reveal progress in [0, 1]; only meaningful while {@link #revealAnimating()}. */
    float revealEased() {
        return reveal.eased();
    }
}
