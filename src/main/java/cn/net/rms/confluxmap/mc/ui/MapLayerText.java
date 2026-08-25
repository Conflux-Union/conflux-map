package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.model.MapLayer;

/** Shared localized label for map layers on the minimap and fullscreen map. */
public final class MapLayerText {
    private MapLayerText() {
    }

    public static String label(final MapLayer layer, final int currentY) {
        final String name = Texts.translatable(
            "confluxmap.layer." + layer.type().id()
        ).getString();
        final Integer height = displayHeight(layer, currentY);
        return height == null
            ? name
            : Texts.translatable("confluxmap.layer.with_height", name, height).getString();
    }

    private static Integer displayHeight(final MapLayer layer, final int currentY) {
        return switch (layer.type()) {
            case CAVE_SLICE, NETHER_SLICE -> layer.param();
            case CAVE_AUTO, NETHER_CURRENT -> currentY;
            default -> null;
        };
    }
}
