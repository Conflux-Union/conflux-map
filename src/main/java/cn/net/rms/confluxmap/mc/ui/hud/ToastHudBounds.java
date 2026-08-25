package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.core.config.HudAmbient;
import cn.net.rms.confluxmap.core.config.HudRect;
import cn.net.rms.confluxmap.core.config.HudTransform;

/**
 * Captures the vanilla toast stack's on-screen bounds, one frame at a time.
 *
 * <p>Each toast reports its own width and height in vanilla, and the manager positions it through
 * the GUI matrix, so the stack is measured per toast at the point where that positioning has
 * already been applied.
 */
public final class ToastHudBounds {
    private static final HudElementBounds STACK = new HudElementBounds();

    private ToastHudBounds() {
    }

    public static void beginFrame(final int screenWidth, final int screenHeight) {
        STACK.beginFrame(screenWidth, screenHeight);
    }

    /**
     * Accumulates one toast, given its own size and the pose the manager positioned it with.
     *
     * <p>The pose maps the toast's local origin, so its rectangle is simply {@code 0,0,w,h}.
     */
    public static void include(final int width, final int height, final HudAmbient pose) {
        STACK.include(
            pose.applyX(0f), pose.applyY(0f), pose.applyX(width), pose.applyY(height)
        );
    }

    public static HudRect previousFrame(final int screenWidth, final int screenHeight) {
        return STACK.previousFrame(screenWidth, screenHeight);
    }

    public static void recordAppliedTransform(final HudTransform transform) {
        STACK.recordAppliedTransform(transform);
    }

    public static HudTransform previousAppliedTransform(
        final int screenWidth,
        final int screenHeight
    ) {
        return STACK.previousAppliedTransform(screenWidth, screenHeight);
    }
}
