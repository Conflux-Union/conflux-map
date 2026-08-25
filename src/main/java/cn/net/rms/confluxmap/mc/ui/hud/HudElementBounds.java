package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.core.config.HudRect;
import cn.net.rms.confluxmap.core.config.HudTransform;

/**
 * Accumulates one vanilla HUD element's on-screen bounds, publishing the last complete frame.
 *
 * <p>Corners arrive already mapped through the GUI matrix vanilla drew them with, so a mod that
 * resizes or moves the element is measured at the size it actually occupies. This mod's own
 * transform is divided back out when the frame closes, leaving bounds that describe the element
 * as it would sit with avoidance turned off - the stable input the next frame's transform needs.
 */
public final class HudElementBounds {
    private static final Frame EMPTY = new Frame(-1, -1, null, HudTransform.IDENTITY);

    private volatile Frame previous = EMPTY;
    private int currentScreenWidth = -1;
    private int currentScreenHeight = -1;
    private boolean measured;
    private float left;
    private float top;
    private float right;
    private float bottom;
    private HudTransform currentAppliedTransform = HudTransform.IDENTITY;

    public void beginFrame(final int screenWidth, final int screenHeight) {
        previous = new Frame(
            currentScreenWidth,
            currentScreenHeight,
            untransformedBounds(),
            currentAppliedTransform
        );
        currentScreenWidth = screenWidth;
        currentScreenHeight = screenHeight;
        measured = false;
        currentAppliedTransform = HudTransform.IDENTITY;
    }

    /** Accumulates one painted rectangle, in the screen-space coordinates it was drawn at. */
    public void include(final float x1, final float y1, final float x2, final float y2) {
        final float paintedLeft = Math.min(x1, x2);
        final float paintedTop = Math.min(y1, y2);
        final float paintedRight = Math.max(x1, x2);
        final float paintedBottom = Math.max(y1, y2);
        if (!measured) {
            measured = true;
            left = paintedLeft;
            top = paintedTop;
            right = paintedRight;
            bottom = paintedBottom;
            return;
        }
        left = Math.min(left, paintedLeft);
        top = Math.min(top, paintedTop);
        right = Math.max(right, paintedRight);
        bottom = Math.max(bottom, paintedBottom);
    }

    /** Returns the last complete frame only when it uses the current scaled viewport. */
    public HudRect previousFrame(final int screenWidth, final int screenHeight) {
        final Frame frame = previous;
        return frame.screenWidth() == screenWidth && frame.screenHeight() == screenHeight
            ? frame.bounds()
            : null;
    }

    /** Records the screen-space transform this mod intends the element to end up under. */
    public void recordAppliedTransform(final HudTransform transform) {
        currentAppliedTransform = transform;
    }

    /** Returns the transform applied during the last complete frame for the current viewport. */
    public HudTransform previousAppliedTransform(final int screenWidth, final int screenHeight) {
        final Frame frame = previous;
        return frame.screenWidth() == screenWidth && frame.screenHeight() == screenHeight
            ? frame.transform()
            : HudTransform.IDENTITY;
    }

    private HudRect untransformedBounds() {
        if (!measured) {
            return null;
        }
        final HudTransform applied = currentAppliedTransform;
        return HudRect.enclosing(
            applied.unapplyX(left),
            applied.unapplyY(top),
            applied.unapplyX(right),
            applied.unapplyY(bottom)
        );
    }

    private record Frame(int screenWidth, int screenHeight, HudRect bounds, HudTransform transform) {
    }
}
