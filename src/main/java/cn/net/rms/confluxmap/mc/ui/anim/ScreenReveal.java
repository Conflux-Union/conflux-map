package cn.net.rms.confluxmap.mc.ui.anim;

import cn.net.rms.confluxmap.core.util.Easing;

/**
 * Reveal state machine for one screen's open/close transition. Progress runs 0..1 in both
 * phases: OPENING climbs from 0, CLOSING falls back toward 0 from wherever it stands, so a
 * screen closed mid-open shrinks away from its current scale instead of snapping.
 */
public final class ScreenReveal {
    /** OPEN is the steady state; render paths must treat it as the zero-cost fast path. */
    public enum Phase { OPENING, OPEN, CLOSING, CLOSED }

    // Fresh screens are constructed OPEN, so the progress starts fully open too and a
    // never-animated render pass sees eased() == 1.
    private float progress = 1.0f;
    private Phase phase = Phase.OPEN;

    /** Restarts the open transition from the beginning. */
    public void beginOpen() {
        progress = 0.0f;
        phase = Phase.OPENING;
    }

    /** Starts closing from the current progress; a fully open screen closes from 1. */
    public void beginClose() {
        if (phase == Phase.CLOSED) {
            return;
        }
        phase = Phase.CLOSING;
    }

    /** Advances the active transition by {@code deltaSeconds}; no-op while OPEN or CLOSED. */
    public void tick(final float deltaSeconds, final float durationSeconds) {
        switch (phase) {
            case OPENING -> {
                progress = UiMotion.advance(progress, deltaSeconds, durationSeconds);
                if (progress >= 1.0f) {
                    phase = Phase.OPEN;
                }
            }
            case CLOSING -> {
                // Closing counts the remaining openness down, mirroring advance's climb.
                progress = 1.0f - UiMotion.advance(1.0f - progress, deltaSeconds, durationSeconds);
                if (progress <= 0.0f) {
                    progress = 0.0f;
                    phase = Phase.CLOSED;
                }
            }
            default -> {
            }
        }
    }

    /** Eased progress for rendering; use even while OPEN (it is 1 there). */
    public float eased() {
        return Easing.easeOutCubic(progress);
    }

    /** Raw progress in [0, 1]; 1 fully open, 0 fully closed. */
    public float progress() {
        return progress;
    }

    /** Jumps to the terminal state of the current phase, ending any per-frame cost. */
    public void finishNow() {
        if (phase == Phase.CLOSING) {
            progress = 0.0f;
            phase = Phase.CLOSED;
        } else if (phase == Phase.OPENING) {
            progress = 1.0f;
            phase = Phase.OPEN;
        }
    }

    /** True while a transition is in flight and the render path must keep paying for it. */
    public boolean animating() {
        return phase == Phase.OPENING || phase == Phase.CLOSING;
    }

    public Phase phase() {
        return phase;
    }
}
