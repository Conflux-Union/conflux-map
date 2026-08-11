package cn.net.rms.confluxmap.core.config;

/** Pure screen-space policy for keeping vanilla toast notifications clear of the minimap. */
public final class ToastHudAvoidance {
    private static final int TOAST_WIDTH = 160;
    private static final int TOAST_HEIGHT = 32;
    private static final int GAP = 4;

    private ToastHudAvoidance() {
    }

    /** Returns the vertical translation that places the vanilla toast stack below the minimap. */
    public static int verticalShift(
        final int screenWidth,
        final int screenHeight,
        final MinimapPlacement.Layout minimap
    ) {
        return verticalShift(screenWidth, screenHeight, minimap, 0);
    }

    /** Returns the translation below the complete minimap footprint, including information text. */
    public static int verticalShift(
        final int screenWidth,
        final int screenHeight,
        final MinimapPlacement.Layout minimap,
        final int informationHeight
    ) {
        if (minimap == null || screenWidth <= 0 || screenHeight <= 0) {
            return 0;
        }
        final long toastLeft = (long) screenWidth - TOAST_WIDTH;
        final long minimapRight = (long) minimap.x() + minimap.size();
        if (minimapRight <= toastLeft || minimap.x() >= screenWidth) {
            return 0;
        }
        final MinimapHudAvoidance.Bounds footprint = MinimapHudAvoidance.visualBounds(
            minimap, screenHeight, informationHeight
        );
        final long desiredTop = (long) footprint.bottom() + GAP;
        if (desiredTop < 0 || desiredTop + TOAST_HEIGHT > screenHeight) {
            return 0;
        }
        return (int) desiredTop;
    }
}
