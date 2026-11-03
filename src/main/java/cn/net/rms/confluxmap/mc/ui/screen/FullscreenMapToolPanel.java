package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.mc.ui.anim.PopupReveal;
import java.util.List;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Exclusive primary-tool group state for the fullscreen map. */
final class FullscreenMapToolPanel {
    enum Group {
        VIEW,
        WAYPOINTS,
        DRAWING,
        ACTIONS
    }

    /**
     * One flying expansion button: the widget plus the slot it settles into. A plain
     * {@link ButtonWidget}, because the drawing grid's color swatch is an anonymous
     * subclass rather than a map icon button.
     */
    record FlyingButton(ButtonWidget button, int settleX, int settleY) {
    }

    /** A collapsed group's buttons still flying back into their group button. */
    record RetiringFlight(List<FlyingButton> buttons, int originX, int originY, PopupReveal reveal) {
    }

    private Group openGroup;
    /** Reveal of the expansion; lives here so widget-list rebuilds cannot drop it. */
    private PopupReveal reveal = new PopupReveal();
    /** The collapsing group's fly-back; survives rebuilds so interrupted layouts keep it. */
    private RetiringFlight retiring;

    Group openGroup() {
        return openGroup;
    }

    boolean isOpen(final Group group) {
        return openGroup == group;
    }

    void toggle(final Group group) {
        openGroup = openGroup == group ? null : group;
        restartReveal(openGroup != null);
    }

    boolean close() {
        if (openGroup == null) {
            return false;
        }
        openGroup = null;
        restartReveal(false);
        return true;
    }

    /**
     * Starts the fly-back of a collapsing group from {@code fromProgress}, the expansion
     * progress its buttons stood at, so a group closed mid-open returns from where it
     * stands. Like the reveal, the flight lives here so widget-list rebuilds cannot drop
     * it; the screen re-adds the buttons after each rebuild keeps them rendering.
     */
    void beginRetire(
        final List<FlyingButton> buttons,
        final int originX,
        final int originY,
        final float fromProgress,
        final float durationSeconds
    ) {
        final PopupReveal flight = PopupReveal.fallingFrom(fromProgress, durationSeconds);
        retiring = new RetiringFlight(List.copyOf(buttons), originX, originY, flight);
    }

    RetiringFlight retiring() {
        return retiring;
    }

    /**
     * Advances the retiring flight one render frame. Returns its buttons exactly once when
     * the flight lands — disabled transitions snap it to landed — else null; the caller
     * dismisses them only then.
     */
    List<FlyingButton> tickRetiring(final boolean transitionsEnabled, final float durationSeconds) {
        if (retiring == null) {
            return null;
        }
        retiring.reveal().tick(transitionsEnabled, durationSeconds);
        if (retiring.reveal().animating()) {
            return null;
        }
        final List<FlyingButton> landed = retiring.buttons();
        retiring = null;
        return landed;
    }

    /**
     * Advances the expansion reveal one render frame. Groups open only through user input,
     * always after the screen's own open reveal has settled, so this never overlaps the
     * reveal alpha ConfluxScreen installs.
     */
    void tickReveal(final boolean transitionsEnabled, final float durationSeconds) {
        reveal.tick(transitionsEnabled, durationSeconds);
    }

    /** True while the expansion flight runs; a settled panel pays no per-frame cost. */
    boolean revealAnimating() {
        return reveal.animating();
    }

    /** Raw expansion progress in [0, 1], for slicing staggered per-button progress. */
    float revealProgress() {
        return reveal.progress();
    }

    /** Raw fly-back progress in [0, 1], for slicing staggered per-button progress. */
    float retiringProgress() {
        return retiring == null ? 0.0f : retiring.reveal().progress();
    }

    /**
     * Swaps in a fresh reveal for the new target. The fly-back of the old group is carried
     * by its own retiring flight, so the reveal always restarts at the target's start —
     * opening a group while another is still collapsing flies the new buttons out too.
     */
    private void restartReveal(final boolean opening) {
        reveal = new PopupReveal();
        reveal.setOpen(opening);
    }
}
