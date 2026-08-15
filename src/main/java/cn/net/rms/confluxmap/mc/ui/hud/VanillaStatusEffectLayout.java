package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.core.config.HudRect;

/**
 * Vanilla's own status-effect overlay geometry, kept next to the code that depends on it.
 *
 * <p>Unlike the scoreboard and the toast stack, the effect icons expose no size API and are
 * painted through a draw call whose signature changes almost every release, so the row rectangles
 * are derived from vanilla's layout rules rather than measured. {@code
 * VanillaStatusEffectLayoutTest} reads these constants back out of the vanilla bytecode on every
 * supported version, so a Mojang change fails the build instead of quietly misplacing the rows.
 *
 * <p>Rows derived here are still in the coordinates vanilla paints with; the caller maps them
 * through the live GUI matrix to get actual screen pixels.
 */
public final class VanillaStatusEffectLayout {
    public static final int ICON_SIZE = 24;
    public static final int ICON_STEP = 25;
    public static final int ROW_STEP = 26;
    public static final int TOP = 1;
    public static final int DEMO_TOP_OFFSET = 15;

    private VanillaStatusEffectLayout() {
    }

    /** The top edge of the beneficial row; the demo banner pushes the overlay down. */
    public static int beneficialTop(final boolean demo) {
        return demo ? TOP + DEMO_TOP_OFFSET : TOP;
    }

    /** Harmful effects occupy a second row below the beneficial one. */
    public static int harmfulTop(final boolean demo) {
        return beneficialTop(demo) + ROW_STEP;
    }

    /** Returns the right-aligned row rectangle, or null when the row holds no icons. */
    public static HudRect row(final int screenWidth, final int top, final int iconCount) {
        if (iconCount <= 0 || screenWidth <= 0) {
            return null;
        }
        final int left = screenWidth - ICON_STEP * iconCount;
        return new HudRect(Math.min(left, screenWidth), top, screenWidth, top + ICON_SIZE);
    }
}
