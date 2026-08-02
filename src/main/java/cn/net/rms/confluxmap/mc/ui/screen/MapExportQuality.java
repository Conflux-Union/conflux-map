package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.export.MapExportResolution;

/** User-facing quality fractions for map export resolutions. */
final class MapExportQuality {
    private MapExportQuality() {
    }

    static String fraction(final MapExportResolution resolution) {
        final int divisor = resolution.blocksPerPixel();
        return divisor == 1 ? "1" : "1/" + divisor;
    }
}
