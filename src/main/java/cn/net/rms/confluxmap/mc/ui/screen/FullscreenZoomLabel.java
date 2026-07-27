package cn.net.rms.confluxmap.mc.ui.screen;

import java.util.Locale;

final class FullscreenZoomLabel {
    private FullscreenZoomLabel() {
    }

    static String format(final double blocksPerPixel) {
        final String fixedPrecision = String.format(
            Locale.ROOT, "%.4f", 1.0 / blocksPerPixel
        );
        final int minimumLength = fixedPrecision.indexOf('.') + 3;
        int length = fixedPrecision.length();
        while (length > minimumLength && fixedPrecision.charAt(length - 1) == '0') {
            length--;
        }
        return fixedPrecision.substring(0, length) + "x";
    }
}
