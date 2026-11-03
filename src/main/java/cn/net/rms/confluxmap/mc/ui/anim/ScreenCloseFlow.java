package cn.net.rms.confluxmap.mc.ui.anim;

/**
 * Pure decision table for closing a screen through the transition orchestration.
 * ConfluxScreen feeds it the config flag, its own reveal state and whether the target
 * is a parent screen, then executes the returned action — every Minecraft-facing side
 * effect stays out of here so the decision matrix remains unit-testable.
 */
public final class ScreenCloseFlow {
    /** What a screen should do when a close is requested. */
    public enum Decision {
        /** Fade out in place and defer the real switch until the fade lands. */
        DEFER_FADE,
        /** A fade is already running; cut it short and switch immediately. */
        FINISH_PENDING_FADE,
        /** Switch immediately, then have the target screen replay its open reveal. */
        SWITCH_NOW_REOPEN_TARGET,
        /** Switch immediately with no animation on either side. */
        SWITCH_NOW,
    }

    private ScreenCloseFlow() {
    }

    /**
     * Closing to the world is the only close that fades: the screen sinks away over the
     * live game. Closing to a parent screen switches instantly and lets the parent
     * replay its own open reveal, so round-trip navigation reads as one motion instead
     * of two back-to-back fades. With transitions off every close stays instant.
     */
    public static Decision decideClose(
        final boolean transitionsEnabled,
        final boolean parentTarget,
        final boolean closePending
    ) {
        if (!transitionsEnabled) {
            return Decision.SWITCH_NOW;
        }
        if (parentTarget) {
            return Decision.SWITCH_NOW_REOPEN_TARGET;
        }
        return closePending ? Decision.FINISH_PENDING_FADE : Decision.DEFER_FADE;
    }
}
