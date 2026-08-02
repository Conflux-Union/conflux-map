package cn.net.rms.confluxmap.mc.ui.screen;

import java.util.Locale;

final class FullscreenZoomLabel {
    private FullscreenZoomLabel() {
    }

    static double multiplier(final double blocksPerPixel) {
        return 1.0 / blocksPerPixel;
    }

    static boolean isAtOrBelow(final double blocksPerPixel, final double threshold) {
        return threshold > 0.0 && multiplier(blocksPerPixel) <= threshold;
    }

    static String format(final double blocksPerPixel) {
        final String fixedPrecision = String.format(
            Locale.ROOT, "%.4f", multiplier(blocksPerPixel)
        );
        final int minimumLength = fixedPrecision.indexOf('.') + 3;
        int length = fixedPrecision.length();
        while (length > minimumLength && fixedPrecision.charAt(length - 1) == '0') {
            length--;
        }
        return fixedPrecision.substring(0, length) + "x";
    }
}
