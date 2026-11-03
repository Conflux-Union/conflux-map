package cn.net.rms.confluxmap.mc.ui.anim;

/**
 * Pure decision table for switching a tabbed screen's content through the two-act
 * transition: the old rows fade out in reverse order, the content rebuilds, the new
 * rows fade in staggered. The screen feeds it clicks and landing instants, then
 * executes the returned decisions — every game-facing side effect stays out of here
 * so the state machine remains unit-testable.
 *
 * <p>{@code T} values are compared by identity, so an enum category type is the
 * intended use.
 */
public final class TabSwitchFlow<T> {
    /** Where the two-act switch stands. */
    public enum Phase {
        /** No transition running; the screen shows the selected tab's rows. */
        IDLE,
        /** Act 1: the previous tab's rows are fading out. */
        RETIRING,
        /** Act 2: the current tab's rows are fading in. */
        REVEALING,
    }

    /** What a screen should do after a tab click. */
    public enum ClickDecision {
        /** Nothing: the clicked tab is already the highlighted one. */
        NONE,
        /** Swap the rows in place right away (transitions disabled). */
        REBUILD_NOW,
        /** Start act 1: fade the current rows out for the clicked tab. */
        BEGIN_RETIRE,
        /**
         * A transition is already running: queue the clicked tab and cut the running act
         * short, so the machine absorbs the interruption at the landing.
         */
        FAST_FORWARD,
    }

    /** What a screen should do when the running act's reveal lands. */
    public enum LandAction {
        /** No act was running. */
        NONE,
        /** Act 2 settled with no queued tab: hand the rows back to the inherited path. */
        SETTLE,
        /** An act landed with a queued tab: rebuild for it and replay the reveal. */
        REBUILD_AND_REVEAL,
    }

    /** One landed act: the action to take and the queued tab it resolves, if any. */
    public record Landing<R>(LandAction action, R target) {
    }

    private Phase phase = Phase.IDLE;
    /** The tab a running transition lands on; null once its act's landing consumed it. */
    private T pending;

    /** Where the two-act switch stands. */
    public Phase phase() {
        return phase;
    }

    /**
     * The tab the highlight already shows: the selected one while idle, the queued one
     * while a transition runs toward it.
     */
    public T highlighted(final T selected) {
        return pending != null ? pending : selected;
    }

    /**
     * A click on a tab. While idle it starts act 1 unless the tab is already highlighted
     * or transitions are off; while an act runs it re-queues the target and fast-forwards
     * the running act, so a rapid click never waits out the current act.
     */
    public ClickDecision click(final T target, final T selected, final boolean transitionsEnabled) {
        if (target == highlighted(selected)) {
            return ClickDecision.NONE;
        }
        if (!transitionsEnabled) {
            reset();
            return ClickDecision.REBUILD_NOW;
        }
        if (phase == Phase.IDLE) {
            pending = target;
            phase = Phase.RETIRING;
            return ClickDecision.BEGIN_RETIRE;
        }
        pending = target;
        return ClickDecision.FAST_FORWARD;
    }

    /**
     * Settles the act that just landed (its reveal reached its target, possibly through a
     * fast-forward) and reports what the screen should do about it. A landing with a
     * queued tab always rebuilds for that tab and starts its reveal; a settled act 2 with
     * nothing queued ends the switch.
     */
    public Landing<T> land() {
        final T target = pending;
        pending = null;
        switch (phase) {
            case RETIRING:
                if (target == null) {
                    phase = Phase.IDLE;
                    return new Landing<>(LandAction.NONE, null);
                }
                phase = Phase.REVEALING;
                return new Landing<>(LandAction.REBUILD_AND_REVEAL, target);
            case REVEALING:
                if (target != null) {
                    // The interrupted reveal is replaced in place by the queued tab's own.
                    return new Landing<>(LandAction.REBUILD_AND_REVEAL, target);
                }
                phase = Phase.IDLE;
                return new Landing<>(LandAction.SETTLE, null);
            default:
                return new Landing<>(LandAction.NONE, null);
        }
    }

    /** Drops any running transition, e.g. because the screen is closing or settling by force. */
    public void reset() {
        phase = Phase.IDLE;
        pending = null;
    }
}
