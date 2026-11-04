package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * Slider base whose handle follows the widget alpha on 1.17-1.19, where vanilla's
 * handle pass resets the shader color to opaque and would float the knob over rows
 * that have not queued into the stagger fade yet.
 */
abstract class RevealedSlider extends SliderWidget {
    RevealedSlider(final int x, final int y, final int width, final int height, final double position) {
        super(x, y, width, height, Text.of(""), position);
    }

    //#if MC<12000
    @Override
    protected void renderBackground(final MatrixStack matrices, final MinecraftClient client, final int mouseX, final int mouseY) {
        // 1.20 folded the handle into renderButton, which already consumes the
        // widget alpha, so this override is only needed below that.
        final float ambient = GuiShapes.revealAlpha();
        GuiDraw.beginReveal(alpha);
        try {
            // Vanilla draws the 8px knob as two mirrored 4px halves of widgets.png.
            final int vIndex = isHovered() ? 2 : 1;
            final int handleX = Widgets.x(this) + (int) (value * (getWidth() - 8));
            final float v0 = (46f + vIndex * 20f) / 256f;
            final float v1 = (66f + vIndex * 20f) / 256f;
            GuiDraw.drawTexturedQuad(
                matrices, handleX, Widgets.y(this), 4, 20, 0f, v0, 4f / 256f, v1, 0xFFFFFFFF
            );
            GuiDraw.drawTexturedQuad(
                matrices, handleX + 4, Widgets.y(this), 4, 20, 196f / 256f, v0, 200f / 256f, v1, 0xFFFFFFFF
            );
        } finally {
            GuiDraw.beginReveal(ambient);
        }
    }
    //#endif
}
