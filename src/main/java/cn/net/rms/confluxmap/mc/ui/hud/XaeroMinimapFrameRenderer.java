package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.mc.render.RenderUtil;
import net.minecraft.client.util.math.MatrixStack;

/** Draws the supported Xaero square-frame atlas layout after its texture is bound. */
final class XaeroMinimapFrameRenderer {
    private static final float ATLAS_SIZE = 256f;

    private XaeroMinimapFrameRenderer() {
    }

    static void drawSquare(
        final MatrixStack matrices,
        final int x,
        final int y,
        final int size
    ) {
        final float corner = Math.min(16f, size / 2f);
        final float middle = Math.max(0f, size - corner * 2f);
        drawPart(matrices, x, y, corner, corner, 192, 97, 16, 16);
        drawPart(matrices, x + corner, y, middle, corner, 0, 0, 226, 16);
        drawPart(matrices, x + size - corner, y, corner, corner, 192, 113, 16, 16);
        drawPart(matrices, x, y + corner, corner, middle, 0, 97, 16, 113);
        drawPart(matrices, x + size - corner, y + corner, corner, middle, 16, 97, 16, 113);
        drawPart(matrices, x, y + size - corner, corner, corner, 192, 129, 16, 16);
        drawPart(matrices, x + corner, y + size - corner, middle, corner, 0, 16, 226, 16);
        drawPart(
            matrices, x + size - corner, y + size - corner,
            corner, corner, 192, 145, 16, 16
        );
    }

    private static void drawPart(
        final MatrixStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final int sourceX,
        final int sourceY,
        final int sourceWidth,
        final int sourceHeight
    ) {
        if (width <= 0f || height <= 0f) {
            return;
        }
        RenderUtil.drawTintedQuad(
            matrices, x, y, width, height,
            sourceX / ATLAS_SIZE, sourceY / ATLAS_SIZE,
            (sourceX + sourceWidth) / ATLAS_SIZE,
            (sourceY + sourceHeight) / ATLAS_SIZE,
            0xFFFFFFFF
        );
    }
}
