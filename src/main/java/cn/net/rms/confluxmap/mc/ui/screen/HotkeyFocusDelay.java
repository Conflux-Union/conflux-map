package cn.net.rms.confluxmap.mc.ui.screen;

/** Controls whether a text field is focused while the opening key event is still draining. */
final class HotkeyFocusDelay {
    private boolean waitingForNextTick;

    void defer() {
        waitingForNextTick = true;
    }

    boolean shouldFocus() {
        return !waitingForNextTick;
    }

    void advanceTick() {
        waitingForNextTick = false;
    }
}
