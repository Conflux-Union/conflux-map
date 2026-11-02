package cn.net.rms.confluxmap.mc.ui.anim;

import cn.net.rms.confluxmap.core.util.Easing;

/**
 * Show/hide progress for one popup surface, with its own frame clock. Unlike
 * {@link ScreenReveal} this has no phases: the target is a boolean and each tick moves
 * progress toward it, so reversing mid-flight just turns the popup around from where it
 * stands. Hosts keep one instance so widget rebuilds cannot drop the animation state.
 */
public final class PopupReveal {
    private float progress;
    private boolean open;
    private final TransitionClock clock = new TransitionClock();

    /** Sets the target state; progress keeps its current value and ticks toward it. */
    public void setOpen(final boolean open) {
        this.open = open;
    }

    /**
     * Advances one render frame toward the target. Disabled transitions snap to the settled
     * state so the render path stays exactly the pre-animation one.
     */
    public void tick(final boolean transitionsEnabled, final float durationSeconds) {
        if (!transitionsEnabled) {
            finishNow();
        } else if (animating()) {
            tick(clock.tick(), durationSeconds);
        }
    }

    /** Moves progress one {@code deltaSeconds} step toward the current target. */
    public void tick(final float deltaSeconds, final float durationSeconds) {
        progress = UiMotion.advance(
            open ? progress : 1.0f - progress,
            deltaSeconds,
            durationSeconds
        );
        if (!open) {
            progress = 1.0f - progress;
        }
    }

    /** Eased progress in [0, 1]; 1 is fully shown, 0 fully hidden. */
    public float eased() {
        return Easing.easeOutCubic(progress);
    }

    /** Raw progress in [0, 1]; 1 fully shown, 0 fully hidden — for callers slicing sub-progress. */
    public float progress() {
        return progress;
    }

    /** Jumps to the target state, ending any per-frame cost. */
    public void finishNow() {
        progress = open ? 1.0f : 0.0f;
    }

    /** True while progress has not settled on the target. */
    public boolean animating() {
        return open ? progress < 1.0f : progress > 0.0f;
    }

    /** True while a close fade may still draw: transitions on and the reveal in flight. */
    public boolean fadeVisible(final boolean transitionsEnabled) {
        return transitionsEnabled && animating();
    }
}
