package cn.net.rms.confluxmap.mc.ui.anim;

/**
 * Show/hide progress for one popup surface. Unlike {@link ScreenReveal} this has no phases:
 * the target is a boolean and each tick moves progress toward it, so reversing mid-flight
 * just turns the popup around from where it stands.
 */
public final class PopupReveal {
    private float progress;
    private boolean open;

    /** Sets the target state; progress keeps its current value and ticks toward it. */
    public void setOpen(final boolean open) {
        this.open = open;
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
        return UiMotion.easeOutCubic(progress);
    }

    /** Jumps to the target state, ending any per-frame cost. */
    public void finishNow() {
        progress = open ? 1.0f : 0.0f;
    }

    /** True while progress has not settled on the target. */
    public boolean animating() {
        return open ? progress < 1.0f : progress > 0.0f;
    }

    public boolean open() {
        return open;
    }
}
