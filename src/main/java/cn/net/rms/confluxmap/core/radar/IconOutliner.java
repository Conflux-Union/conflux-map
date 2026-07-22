package cn.net.rms.confluxmap.core.radar;

import cn.net.rms.confluxmap.core.util.Argb;

/**
 * Silhouette outline computation for radar icon sprites: the "first ring of transparent
 * pixels" hugging a sprite's non-transparent content, per the outline-fill pass in
 * docs/reference-specs/radar-icons.md sec 2 (run once here, so the ring is 1px thick).
 * Minecraft-free; the mc adapter feeds one sheet cell at a time and uploads the result.
 */
public final class IconOutliner {
    /** Padding added on every side of the input so the ring fits around edge-touching sprites. */
    public static final int PAD = 1;
    /** ARGB white, chosen so draw-time tinting picks the actual contour color. Identical in ABGR byte order. */
    public static final int OUTLINE = 0xFFFFFFFF;
    /** Pixels at least half visible count as sprite body; fainter ones read as background. */
    private static final int SOLID_ALPHA = 128;

    private IconOutliner() {
    }

    /**
     * Returns a (width + 2*{@link #PAD}) x (height + 2*{@link #PAD}) row-major ARGB mask,
     * {@code pixels} centered: {@link #OUTLINE} on every position that is not itself solid but
     * has a directly-or-diagonally adjacent solid pixel, transparent everywhere else. Positions
     * outside the input bounds are never solid, so a sprite touching the input edge gets its
     * ring in the padding apron instead of losing it.
     */
    public static int[] outlineMask(final int[] pixels, final int width, final int height) {
        final int outWidth = width + 2 * PAD;
        final int outHeight = height + 2 * PAD;
        final int[] mask = new int[outWidth * outHeight];
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                final int srcX = x - PAD;
                final int srcY = y - PAD;
                if (!solid(pixels, width, height, srcX, srcY) && anySolidNeighbor(pixels, width, height, srcX, srcY)) {
                    mask[y * outWidth + x] = OUTLINE;
                }
            }
        }
        return mask;
    }

    private static boolean anySolidNeighbor(final int[] pixels, final int width, final int height, final int x, final int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dy != 0) && solid(pixels, width, height, x + dx, y + dy)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean solid(final int[] pixels, final int width, final int height, final int x, final int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return false;
        }
        return Argb.alpha(pixels[y * width + x]) >= SOLID_ALPHA;
    }
}
