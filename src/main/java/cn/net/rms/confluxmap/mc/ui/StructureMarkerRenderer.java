package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.mc.render.GuiShapes;

/** Vanilla-texture structure icon with state-specific framing. */
public final class StructureMarkerRenderer {
    public static final float SIZE = 18f;
    static final int CANDIDATE_BORDER = 0xB34FA3FF;
    static final int VERIFIED_BORDER = 0xFF55D6A5;
    private static final int HOVER_TINT = 0x80FFFFFF;

    private StructureMarkerRenderer() {
    }

    public static void draw(
        final GuiDraw draw,
        final StructureIndex.Marker marker,
        final float x,
        final float y,
        final boolean hovered
    ) {
        final float left = x - SIZE * 0.5f;
        final float top = y - SIZE * 0.5f;
        final int border = borderColor(marker.state(), hovered);
        GuiShapes.fillRect(draw.matrices(), left, top, SIZE, SIZE, 0xE0101010);
        GuiShapes.fillRect(draw.matrices(), left + 1f, top + 1f, SIZE - 2f, SIZE - 2f, border);
        GuiShapes.fillRect(draw.matrices(), left + 3f, top + 3f, SIZE - 6f, SIZE - 6f, 0xE0101010);
        final float iconSize = SIZE - 6f;
        StructureIconCatalog.draw(
            draw,
            marker.type(),
            marker.variant(),
            left + 3f,
            top + 3f,
            iconSize,
            (hovered || marker.state() == StructureIndex.State.VERIFIED) ? 0xFFFFFFFF : 0xD9FFFFFF
        );
    }

    static int borderColor(final StructureIndex.State state) {
        return state == StructureIndex.State.VERIFIED ? VERIFIED_BORDER : CANDIDATE_BORDER;
    }

    static int borderColor(final StructureIndex.State state, final boolean hovered) {
        final int base = borderColor(state);
        return hovered ? Argb.blendOver(0xFF000000 | base, HOVER_TINT) : base;
    }
}
