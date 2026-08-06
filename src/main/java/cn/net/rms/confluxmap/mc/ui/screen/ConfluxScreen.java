package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.compat.Texts;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#else
import net.minecraft.client.util.math.MatrixStack;
//#endif
//#if MC>=12109
//$$ import net.minecraft.client.input.KeyInput;
//#endif
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Screen base that keeps the MatrixStack-to-DrawContext rewrite at one lifecycle seam. */
public abstract class ConfluxScreen extends Screen {
    private final Map<ClickableWidget, String> disabledTooltipKeys = new IdentityHashMap<>();
    private Runnable enterAction;
    private BooleanSupplier enterActionEnabled = () -> false;
    //#if MC>=12000
    //$$ /**
    //$$  * Screen.render owns the widget loop, but its implicit background must not cover
    //$$  * renderContents. Only 1.21.5 and older reach that path: 1.21.6 moved the background
    //$$  * call up into Screen.renderWithTooltip, which already runs before renderContents.
    //$$  */
    //$$ private boolean renderingVanillaWidgets;
    //#endif

    protected ConfluxScreen(final Text title) {
        super(title);
    }

    //#if MC>=260100
    //$$ @Override
    //$$ public final void extractRenderState(
    //$$     final GuiGraphicsExtractor context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     final GuiDraw draw = GuiDraw.of(context);
    //$$     renderContents(draw, mouseX, mouseY, tickDelta);
    //$$     // 26.1 renamed the retained-mode entry points but kept the ordering: the background is
    //$$     // extracted before the widget list is walked.
    //$$     renderingVanillaWidgets = true;
    //$$     try {
    //$$         super.extractRenderState(context, mouseX, mouseY, tickDelta);
    //$$     } finally {
    //$$         renderingVanillaWidgets = false;
    //$$     }
    //$$     renderAfterWidgets(draw, mouseX, mouseY, tickDelta);
    //$$     renderDisabledTooltip(draw, mouseX, mouseY);
    //$$ }
    //$$
    //$$ @Override
    //$$ public final void extractBackground(
    //$$     final GuiGraphicsExtractor context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     if (!renderingVanillaWidgets) {
    //$$         renderVanillaBackground(context, mouseX, mouseY, tickDelta);
    //$$     }
    //$$ }
    //$$
    //$$ protected void renderVanillaBackground(
    //$$     final GuiGraphicsExtractor context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     super.extractBackground(context, mouseX, mouseY, tickDelta);
    //$$ }
    //#elseif MC>=12000
    //$$ @Override
    //$$ public final void render(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     final GuiDraw draw = GuiDraw.of(context);
    //$$     renderContents(draw, mouseX, mouseY, tickDelta);
    //$$     // Modern Screen.render invokes renderBackground before iterating its private widget list.
    //$$     renderingVanillaWidgets = true;
    //$$     try {
    //$$         super.render(context, mouseX, mouseY, tickDelta);
    //$$     } finally {
    //$$         renderingVanillaWidgets = false;
    //$$     }
    //$$     renderAfterWidgets(draw, mouseX, mouseY, tickDelta);
    //$$     renderDisabledTooltip(draw, mouseX, mouseY);
    //$$ }
    //$$
    //#if MC>=12002
    //$$ @Override
    //$$ public final void renderBackground(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     if (!renderingVanillaWidgets) {
    //$$         renderVanillaBackground(context, mouseX, mouseY, tickDelta);
    //$$     }
    //$$ }
    //$$
    //$$ protected void renderVanillaBackground(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     super.renderBackground(context, mouseX, mouseY, tickDelta);
    //$$ }
    //#else
    //$$ @Override
    //$$ public final void renderBackground(final DrawContext context) {
    //$$     if (!renderingVanillaWidgets) {
    //$$         renderVanillaBackground(context);
    //$$     }
    //$$ }
    //$$
    //$$ protected void renderVanillaBackground(final DrawContext context) {
    //$$     super.renderBackground(context);
    //$$ }
    //#endif
    //#else
    @Override
    public final void render(
        final MatrixStack matrices,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        final GuiDraw draw = GuiDraw.of(matrices);
        renderContents(draw, mouseX, mouseY, tickDelta);
        super.render(matrices, mouseX, mouseY, tickDelta);
        renderAfterWidgets(draw, mouseX, mouseY, tickDelta);
        renderDisabledTooltip(draw, mouseX, mouseY);
    }
    //#endif

    protected abstract void renderContents(GuiDraw draw, int mouseX, int mouseY, float tickDelta);

    /** Makes Enter activate the screen's visible primary action when it is available. */
    protected final void setEnterAction(
        final BooleanSupplier enabled,
        final Runnable action
    ) {
        enterActionEnabled = enabled;
        enterAction = action;
    }

    /** Removes an Enter binding when a rebuilt screen no longer exposes that action. */
    protected final void clearEnterAction() {
        enterActionEnabled = () -> false;
        enterAction = null;
    }

    @Override
    //#if MC>=12109
    //$$ public boolean keyPressed(final KeyInput input) {
    //$$     final int keyCode = input.key();
    //#else
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
    //#endif
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
            && enterAction != null && enterActionEnabled.getAsBoolean()) {
            enterAction.run();
            return true;
        }
        //#if MC>=12109
        //$$ return super.keyPressed(input);
        //#else
        return super.keyPressed(keyCode, scanCode, modifiers);
        //#endif
    }

    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
    }

    /** Draws a stable monochrome scrollbar for a row-based viewport. */
    protected final void drawListScrollbar(
        final GuiDraw draw,
        final int x,
        final int top,
        final int height,
        final int totalRows,
        final int visibleRows,
        final int offset
    ) {
        if (totalRows <= visibleRows || visibleRows <= 0 || height <= 0) {
            return;
        }
        final int thumbHeight = Math.max(10, height * visibleRows / totalRows);
        final int maxOffset = totalRows - visibleRows;
        final int travel = Math.max(1, height - thumbHeight);
        final int thumbY = top + Math.round(travel * (offset / (float) maxOffset));
        draw.fill(x, top, x + 2, top + height, 0xFF3A3A3A);
        draw.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xFFFFFFFF);
    }

    /** Associates one inactive control with the translated reason it cannot be used. */
    protected final void setDisabledTooltip(final ClickableWidget widget, final String translationKey) {
        if (widget == null) {
            return;
        }
        if (translationKey == null) {
            disabledTooltipKeys.remove(widget);
            return;
        }
        disabledTooltipKeys.put(widget, translationKey);
    }

    private void renderDisabledTooltip(final GuiDraw draw, final int mouseX, final int mouseY) {
        disabledTooltipKeys.entrySet().removeIf(entry -> !children().contains(entry.getKey()));
        for (final Map.Entry<ClickableWidget, String> entry : disabledTooltipKeys.entrySet()) {
            final ClickableWidget widget = entry.getKey();
            if (widget.visible && !widget.active && widget.isHovered()) {
                draw.drawTooltip(
                    this,
                    this.textRenderer,
                    Texts.translatable(entry.getValue()),
                    mouseX,
                    mouseY
                );
                return;
            }
        }
    }
}
