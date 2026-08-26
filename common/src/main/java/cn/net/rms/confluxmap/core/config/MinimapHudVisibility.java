package cn.net.rms.confluxmap.core.config;

/** Pure visibility policy for the minimap HUD; Minecraft screen classification stays in mc/compat. */
public final class MinimapHudVisibility {
    private MinimapHudVisibility() {
    }

    public static boolean shouldRender(
        final boolean minimapEnabled,
        final boolean sessionActive,
        final boolean fullscreenOpen,
        final boolean containerOpen,
        final boolean fullDebugOverlayVisible
    ) {
        return minimapEnabled && sessionActive && !fullscreenOpen && !containerOpen
            && !fullDebugOverlayVisible;
    }
}
