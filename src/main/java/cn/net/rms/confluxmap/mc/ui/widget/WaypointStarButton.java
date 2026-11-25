package cn.net.rms.confluxmap.mc.ui.widget;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.annotation.AnnotationProjection.ScreenPoint;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.ui.AnnotationRenderer;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.util.math.MatrixStack;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#endif

/** Borderless five-point star whose selected color is independent of button resource packs. */
public final class WaypointStarButton extends ConfluxTextButton {
    private final boolean starred;

    public WaypointStarButton(final int x, final int y, final boolean starred, final PressAction action) {
        this(x, y, 18, 20, starred, action);
    }

    public WaypointStarButton(final int x, final int y, final int width, final int height,
                              final boolean starred, final PressAction action) {
        super(x, y, width, height, Texts.translatable(starred
            ? "confluxmap.screen.waypoints.unstar" : "confluxmap.screen.waypoints.star"), action);
        this.starred = starred;
    }

    @Override
    //#if MC>=260100
    //$$ protected void extractContents(final GuiGraphicsExtractor context, final int mouseX, final int mouseY, final float delta) {
    //$$     drawContents(GuiDraw.of(context));
    //#elseif MC>=12111
    //$$ protected void drawIcon(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
    //$$     drawContents(GuiDraw.of(context));
    //#elseif MC>=12002
    //$$ protected void renderWidget(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
    //$$     drawContents(GuiDraw.of(context));
    //#elseif MC>=12000
    //$$ protected void renderButton(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
    //$$     drawContents(GuiDraw.of(context));
    //#else
    public void renderButton(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
        drawContents(GuiDraw.of(matrices));
    //#endif
    }

    @Override
    protected void drawContents(final GuiDraw draw) {
        final double centerX = Widgets.x(this) + getWidth() / 2.0;
        final double centerY = Widgets.y(this) + getHeight() / 2.0;
        final int color = starred ? 0xFFFFD54F
            : active && (isHovered() || isFocused()) ? 0xFFFFFFFF : 0xFFAAAAAA;
        final double radius = Math.min(7, Math.min(getWidth(), getHeight()) / 2.0 - 1);
        for (int point = 0; point < 10; point++) {
            final ScreenPoint start = point(centerX, centerY, point, radius);
            final ScreenPoint end = point(centerX, centerY, (point + 1) % 10, radius);
            if (starred) {
                GuiShapes.fillTriangle(draw.matrices(), (float) centerX, (float) centerY,
                    (float) start.x(), (float) start.y(), (float) end.x(), (float) end.y(), color);
            } else {
                AnnotationRenderer.stroke(draw.matrices(), start, end, 1.0f, color);
            }
        }
    }

    private static ScreenPoint point(final double x, final double y, final int index, final double outerRadius) {
        final double angle = -Math.PI / 2 + index * Math.PI / 5;
        final double radius = index % 2 == 0 ? outerRadius : outerRadius * 3 / 7;
        return new ScreenPoint(x + Math.cos(angle) * radius, y + Math.sin(angle) * radius);
    }
}
