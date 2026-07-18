package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;

/** Compact diamond + badge renderer for seed structure candidates. */
public final class StructureMarkerRenderer {
    private StructureMarkerRenderer() {
    }

    public static void draw(
        final MatrixStack matrices,
        final TextRenderer textRenderer,
        final StructureIndex.Marker marker,
        final float x,
        final float y,
        final boolean hovered
    ) {
        final int alpha = marker.state() == StructureIndex.State.VERIFIED ? 0xFF : 0x8C;
        final int color = marker.state() == StructureIndex.State.VERIFIED ? 0xFF55D6A5 : 0xFF4FA3FF;
        final int outer = (alpha << 24) | 0x00101010;
        final int fill = (alpha << 24) | (color & 0x00FFFFFF);
        final float size = hovered ? 6f : 5f;
        RenderUtil.fillTriangle(matrices, x, y - size, x - size, y, x + size, y, outer);
        RenderUtil.fillTriangle(matrices, x, y + size, x - size, y, x + size, y, outer);
        final float inner = size - 1.5f;
        RenderUtil.fillTriangle(matrices, x, y - inner, x - inner, y, x + inner, y, fill);
        RenderUtil.fillTriangle(matrices, x, y + inner, x - inner, y, x + inner, y, fill);
        final String badge = marker.type().badge();
        textRenderer.drawWithShadow(matrices, badge, x - textRenderer.getWidth(badge) / 2f, y - 4f, 0xFFFFFFFF);
    }
}
