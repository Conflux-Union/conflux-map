package cn.net.rms.confluxmap.core.waypoint.image;

import java.util.Objects;

/** Pure integer-box downsampling for ARGB rasters; no platform dependencies. */
public final class ImageScale {
    private ImageScale() {
    }

    public record Raster(int[] argb, int width, int height) {
        public Raster {
            Objects.requireNonNull(argb, "argb");
            if (width < 1 || height < 1 || argb.length != width * height) {
                throw new IllegalArgumentException(
                    "raster dimensions " + width + "x" + height + " do not match "
                        + argb.length + " pixels"
                );
            }
        }
    }

    /**
     * Averages the source box behind every destination pixel, per channel.
     * Integer box edges keep the mapping stable and never sample outside the
     * source; the alpha channel is averaged as-is (screenshots are opaque).
     */
    public static Raster downscale(final Raster source, final int targetWidth, final int targetHeight) {
        if (targetWidth < 1 || targetHeight < 1
            || targetWidth > source.width() || targetHeight > source.height()) {
            throw new IllegalArgumentException(
                "target " + targetWidth + "x" + targetHeight + " must be within the "
                    + source.width() + "x" + source.height() + " source"
            );
        }
        final int[] out = new int[targetWidth * targetHeight];
        for (int y = 0; y < targetHeight; y++) {
            final int y0 = y * source.height() / targetHeight;
            final int y1 = Math.max(y0 + 1, (y + 1) * source.height() / targetHeight);
            for (int x = 0; x < targetWidth; x++) {
                final int x0 = x * source.width() / targetWidth;
                final int x1 = Math.max(x0 + 1, (x + 1) * source.width() / targetWidth);
                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        final int argb = source.argb()[sy * source.width() + sx];
                        a += (argb >>> 24) & 0xFF;
                        r += (argb >>> 16) & 0xFF;
                        g += (argb >>> 8) & 0xFF;
                        b += argb & 0xFF;
                    }
                }
                final int count = (x1 - x0) * (y1 - y0);
                out[y * targetWidth + x] = ((int) (a / count) << 24)
                    | ((int) (r / count) << 16)
                    | ((int) (g / count) << 8)
                    | (int) (b / count);
            }
        }
        return new Raster(out, targetWidth, targetHeight);
    }

    /** Longest edge capped at {@code maxEdge}; aspect ratio preserved; never below 1. */
    public static int[] fitMaxEdge(final int width, final int height, final int maxEdge) {
        final int longest = Math.max(width, height);
        if (longest <= maxEdge) {
            return new int[] {width, height};
        }
        final int scaledWidth = Math.max(1, (int) Math.round((double) width * maxEdge / longest));
        final int scaledHeight = Math.max(1, (int) Math.round((double) height * maxEdge / longest));
        return new int[] {scaledWidth, scaledHeight};
    }
}
