package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.predict.StructureIndex;

/** Vanilla-texture structure icon with state-specific framing. */
public final class StructureMarkerRenderer {
    static final int CANDIDATE_BORDER = 0xB34FA3FF;
    static final int VERIFIED_BORDER = 0xFF55D6A5;

    private StructureMarkerRenderer() {
    }

    public static void draw(
        final GuiDraw draw,
        final StructureIndex.Marker marker,
        final float x,
        final float y,
        final boolean hovered
    ) {
        final int size = hovered ? 20 : 18;
        final int left = Math.round(x - size / 2f);
        final int top = Math.round(y - size / 2f);
        final int border = borderColor(marker.state());
        draw.fill(left, top, left + size, top + size, 0xE0101010);
        draw.fill(left + 1, top + 1, left + size - 1, top + size - 1, border);
        draw.fill(left + 3, top + 3, left + size - 3, top + size - 3, 0xE0101010);
        final int iconSize = size - 6;
        StructureIconCatalog.draw(
            draw,
            marker.type(),
            marker.variant(),
            left + 3,
            top + 3,
            iconSize,
            marker.state() == StructureIndex.State.VERIFIED ? 0xFFFFFFFF : 0xD9FFFFFF
        );
    }

    static int borderColor(final StructureIndex.State state) {
        return state == StructureIndex.State.VERIFIED ? VERIFIED_BORDER : CANDIDATE_BORDER;
    }
}
