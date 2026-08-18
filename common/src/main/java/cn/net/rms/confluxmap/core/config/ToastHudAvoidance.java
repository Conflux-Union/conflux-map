package cn.net.rms.confluxmap.core.config;

/** Pure screen-space policy for keeping vanilla toast notifications clear of the minimap. */
public final class ToastHudAvoidance {
    private static final int GAP = 4;

    private ToastHudAvoidance() {
    }

    /**
     * Returns the vertical translation that places the measured toast stack below the minimap.
     *
     * <p>{@code toasts} is the stack as it was actually drawn; toast width and height are
     * per-toast properties in vanilla, so assuming the default size would under-report a wide
     * notification or a multi-line system toast.
     */
    public static int verticalShift(
        final int screenHeight,
        final MinimapPlacement.Layout minimap,
        final int informationHeight,
        final HudRect toasts
    ) {
        if (minimap == null || toasts == null || screenHeight <= 0) {
            return 0;
        }
        final HudRect footprint = MinimapInformationLayout.visualBounds(
            minimap, screenHeight, informationHeight
        );
        if (!toasts.overlaps(footprint)) {
            return 0;
        }
        final int desiredTop = footprint.bottom() + GAP;
        if (desiredTop < 0 || desiredTop + toasts.height() > screenHeight) {
            return 0;
        }
        return desiredTop - toasts.top();
    }
}
