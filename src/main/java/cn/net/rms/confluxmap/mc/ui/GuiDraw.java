package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.RenderTargetState;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
//#if MC>=12108
//$$ import org.joml.Matrix3x2fStack;
//$$ import org.joml.Matrix4f;
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//$$ import net.minecraft.client.font.TextRenderer.TextLayerType;
//#endif
//#if MC>=12000 && MC<12108
//$$ import net.minecraft.client.render.RenderLayer;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

/** Version-neutral GUI draw state shared by screens, HUD callbacks, and marker renderers. */
public final class GuiDraw {
    /**
     * Reveal alpha for the interface transitions, applied by {@link #beginReveal} around one
     * screen's render pass. The value lives in {@link GuiShapes} so the shape primitives
     * scale the same colors this class's own context-routed outlets do; 1 keeps every
     * outlet on its original fast path.
     */
    private static final float FULL_ALPHA = 1.0f;
    /** Untinted textured-draw tint; matching it keeps those draws on their fast path. */
    private static final int OPAQUE_TINT = 0xFFFFFFFF;

    private final MatrixStack matrices;
    //#if MC>=12000
    //$$ private final DrawContext context;
    //#endif

    //#if MC>=12000
    //$$ private GuiDraw(final DrawContext context) {
    //$$     this.context = context;
    //#if MC>=12108
    //$$     RenderTargetState.setGuiState(context.state);
    //$$     this.matrices = new MatrixStack();
    //$$     final var source = context.getMatrices();
    //$$     this.matrices.peek().getPositionMatrix()
    //$$         .m00(source.m00()).m01(source.m01())
    //$$         .m10(source.m10()).m11(source.m11())
    //$$         .m30(source.m20()).m31(source.m21());
    //#else
    //$$     this.matrices = context.getMatrices();
    //#endif
    //$$ }
    //#else
    private GuiDraw(final MatrixStack matrices) {
        this.matrices = matrices;
    }
    //#endif

    //#if MC>=12000
    //$$ public static GuiDraw of(final DrawContext context) {
    //$$     return new GuiDraw(context);
    //$$ }
    //#else
    public static GuiDraw of(final MatrixStack matrices) {
        return new GuiDraw(matrices);
    }
    //#endif

    public MatrixStack matrices() {
        return matrices;
    }

    public void pushTransform() {
        matrices.push();
        //#if MC>=12108
        //$$ // popTransform pops the context pose too, so push its level here or the pop runs
        //$$ // off the bottom of the JOML stack; the absolute fold keeps nesting idempotent.
        //$$ context.getMatrices().pushMatrix();
        //$$ foldContextPose();
        //#endif
    }

    public void translate(final float x, final float y) {
        matrices.translate(x, y, 0f);
        //#if MC>=12108
        //$$ // Re-fold: mid-scope transforms must reach context-routed draws too.
        //$$ foldContextPose();
        //#endif
    }

    public void scale(final float x, final float y) {
        matrices.scale(x, y, 1f);
        //#if MC>=12108
        //$$ // Re-fold: mid-scope transforms must reach context-routed draws too.
        //$$ foldContextPose();
        //#endif
    }

    public void popTransform() {
        //#if MC>=12108
        //$$ context.getMatrices().popMatrix();
        //#endif
        matrices.pop();
    }

    /**
     * Scales all subsequently drawn content around the screen center for one reveal frame;
     * pair with {@link #endScreenTransform()}. Explicit dimensions keep this testable and
     * usable from HUD callers too.
     */
    public void beginScreenTransform(
        final float scaleFactor,
        final float screenWidth,
        final float screenHeight
    ) {
        matrices.push();
        translate(screenWidth / 2f, screenHeight / 2f);
        scale(scaleFactor, scaleFactor);
        translate(-screenWidth / 2f, -screenHeight / 2f);
    }

    public void endScreenTransform() {
        matrices.pop();
    }

    /**
     * Installs this GuiDraw's transform as the context's own pose so draws that bypass this
     * class (vanilla's widget pass, {@link #fill} on 1.21.8+) still land transformed. No-op
     * below 1.21.8, where the context and this GuiDraw already share one MatrixStack; on
     * 1.21.8+ this GuiDraw owns its stack, so the 2x3 pose is folded in exactly like
     * {@code withTextPose} does for text. Pair with {@link #restoreContextPose()}.
     */
    public void applyToContextPose() {
        //#if MC>=12108
        //$$ context.getMatrices().pushMatrix();
        //$$ foldContextPose();
        //#endif
    }

    public void restoreContextPose() {
        //#if MC>=12108
        //$$ context.getMatrices().popMatrix();
        //#endif
    }

    //#if MC>=12108
    //$$ /** Overwrites the context pose with this stack's current 2D transform (absolute, not relative). */
    //$$ private void foldContextPose() {
    //$$     final Matrix3x2fStack pose = context.getMatrices();
    //$$     final Matrix4f model = matrices.peek().getPositionMatrix();
    //$$     pose.set(model.m00(), model.m01(), model.m10(), model.m11(), model.m30(), model.m31());
    //$$ }
    //#endif

    /**
     * Multiplies the alpha of every color this class and {@link GuiShapes} emit until
     * {@link #endReveal()}, so a screen reveal fades its own fills, text, and shape draws
     * alike. Item icons are exempt (their model render has no per-call color to scale).
     */
    public static void beginReveal(final float alpha) {
        GuiShapes.setRevealAlpha(Math.max(0.0f, Math.min(FULL_ALPHA, alpha)));
    }

    public static void endReveal() {
        GuiShapes.setRevealAlpha(FULL_ALPHA);
    }

    /**
     * Scales one caller-supplied color by the active reveal; context-routed outlets use this.
     * The scaled alpha never lands in vanilla TextRenderer's forced-opaque zone: 1.17.1's
     * {@code tweakTransparency} rewrites any color whose alpha top bits are zero back to fully
     * opaque, which would flash scoped text at full brightness exactly when its fade sits at
     * zero. The 4/255 floor stays invisible but keeps the rewrite from firing.
     */
    private static final int MIN_REVEALED_ALPHA = 4 << 24;

    private static int revealed(final int color) {
        if (GuiShapes.revealAlpha() == FULL_ALPHA) {
            return color;
        }
        final int scaled = Argb.scaleAlpha(color, GuiShapes.revealAlpha());
        return (scaled & 0xFC000000) == 0 ? scaled | MIN_REVEALED_ALPHA : scaled;
    }

    /**
     * Texture-backed counterpart to the color outlets: one axis-aligned textured quad carrying
     * an ARGB tint over the sampled texture. Texture draws that bypass this class's instance
     * methods (the minimap's map tiles) route through here so a fade scales them like every
     * fill and text, instead of special-casing the call sites. An opaque tint is exactly
     * {@link GuiShapes#drawQuad} - the pre-transition fast path.
     */
    public static void drawTexturedQuad(
        final MatrixStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final float u0,
        final float v0,
        final float u1,
        final float v1,
        final int argbTint
    ) {
        if (argbTint == OPAQUE_TINT) {
            GuiShapes.drawQuad(matrices, x, y, width, height, u0, v0, u1, v1);
            return;
        }
        GuiShapes.drawTintedQuad(matrices, x, y, width, height, u0, v0, u1, v1, argbTint);
    }

    /**
     * Fills one axis-aligned rect in float coordinates through this GuiDraw's own transform,
     * without rounding. Unlike {@link #fill(int, int, int, int, int)} this carries transforms
     * pushed with {@link #pushTransform()} (the context's own fill would drop them on 1.21.8+),
     * at the cost of bypassing the shared vertex-consumer batching - pair it with
     * {@link #flushGui()} when earlier buffered fills must stay underneath.
     */
    public void fillRect(
        final float x1,
        final float y1,
        final float x2,
        final float y2,
        final int color
    ) {
        // GuiShapes applies the reveal alpha itself; pre-scaling here would double it.
        GuiShapes.fillRect(matrices, x1, y1, x2 - x1, y2 - y1, color);
    }

    /** Draws the item's normal 16px GUI model, scaled and centered on one marker position. */
    public void drawItemIcon(
        final MinecraftClient client,
        final ItemStack stack,
        final float centerX,
        final float centerY,
        final float size
    ) {
        final float scale = size / 16f;
        final float left = centerX - size / 2f;
        final float top = centerY - size / 2f;
        //#if MC>=12108
        //$$ final Matrix3x2fStack pose = context.getMatrices();
        //$$ pose.pushMatrix();
        //$$ try {
        //$$     // Items are recorded with the context pose, which never sees this GuiDraw's own
        //$$     // stack; install it first (same fold as withTextPose).
        //$$     final Matrix4f model = matrices.peek().getPositionMatrix();
        //$$     pose.set(model.m00(), model.m01(), model.m10(), model.m11(), model.m30(), model.m31());
        //$$     pose.translate(left, top);
        //$$     pose.scale(scale, scale);
        //#if MC>=260100
        //$$     context.item(stack, 0, 0);
        //#else
        //$$     context.drawItem(stack, 0, 0);
        //#endif
        //$$ } finally {
        //$$     pose.popMatrix();
        //$$ }
        //#elseif MC>=12000
        //$$ matrices.push();
        //$$ try {
        //$$     matrices.translate(left, top, 0);
        //$$     matrices.scale(scale, scale, 1f);
        //$$     context.drawItem(stack, 0, 0);
        //$$ } finally {
        //$$     matrices.pop();
        //$$ }
        //#else
        final MatrixStack modelView = RenderSystem.getModelViewStack();
        modelView.push();
        try {
            // Items render through the model-view stack, which the HUD's MatrixStack is
            // relative to - fold this GuiDraw's transform in or a pushed label transform
            // never reaches the icon.
            modelView.method_34425(matrices.peek().getModel());
            modelView.translate(left, top, 0);
            modelView.scale(scale, scale, 1f);
            RenderSystem.applyModelViewMatrix();
            client.getItemRenderer().renderInGui(stack, 0, 0);
        } finally {
            modelView.pop();
            RenderSystem.applyModelViewMatrix();
        }
        //#endif
        //#if MC<12104
        // GUI item models use their own depth through 1.21.3. Release it so every GuiDraw caller
        // keeps ordinary painter's order without component-specific depth state.
        GuiShapes.clearGuiDepth();
        //#endif
    }

    public void renderBackground(
        final Screen screen,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        //#if MC>=12106
        //$$ // 1.21.6 hoisted the background out of Screen.render into Screen.renderWithTooltip,
        //$$ // which already ran it before renderContents; drawing it again doubles applyBlur.
        //#elseif MC>=12002
        //$$ screen.renderBackground(context, mouseX, mouseY, tickDelta);
        //#elseif MC>=12000
        //$$ screen.renderBackground(context);
        //#else
        screen.renderBackground(matrices);
        //#endif
    }

    public void drawTooltip(
        final Screen screen,
        final TextRenderer renderer,
        final Text text,
        final int mouseX,
        final int mouseY
    ) {
        final List<OrderedText> lines = renderer.wrapLines(text, Math.max(200, screen.width / 2));
        //#if MC>=12108
        //$$ context.drawTooltip(lines, mouseX, mouseY);
        //#elseif MC>=12000
        //$$ context.drawOrderedTooltip(renderer, lines, mouseX, mouseY);
        //#else
        screen.renderOrderedTooltip(matrices, lines, mouseX, mouseY);
        //#endif
    }

    public void fill(final int x1, final int y1, final int x2, final int y2, final int color) {
        //#if MC>=12000
        //$$ context.fill(x1, y1, x2, y2, revealed(color));
        //#else
        GuiShapes.fillRect(matrices(), x1, y1, x2 - x1, y2 - y1, color);
        //#endif
    }

    /**
     * Fills one rect of a popup (dropdown backdrop, option row, selection stripe, its
     * scrollbar) that must fully cover earlier screen content.
     *
     * <p>The main glyph pass of any text drawn earlier in the frame writes depth at
     * {@code TextRenderer.FORWARD_SHIFT} (z + 0.03, nearer than z=0 fills) while sharing the
     * GUI layer's LEQUAL depth test, so a plain {@link #fill} is depth-rejected at those
     * pixels and the earlier text bleeds through the popup.
     */
    public void fillPopup(final int x1, final int y1, final int x2, final int y2, final int color) {
        //#if MC>=12108
        //$$ // Overlap with an earlier element already starts a nearer recorded layer.
        //$$ context.fill(x1, y1, x2, y2, revealed(color));
        //#elseif MC>=12000
        //$$ // Vanilla's own tooltip path: depth test ALWAYS, no depth write.
        //$$ context.fill(RenderLayer.getGuiOverlay(), x1, y1, x2, y2, revealed(color));
        //#else
        // No gui_overlay layer before 1.20; releasing the glyph depth is the only way through.
        GuiShapes.clearGuiDepth();
        GuiShapes.fillRect(matrices(), x1, y1, x2 - x1, y2 - y1, color);
        //#endif
    }

    //#if MC>=12000 && MC<12108
    //$$ /**
    //$$  * Paints every fill queued through the shared GUI vertex consumers so far. On these
    //$$  * versions those fills stay buffered until a later flush, which paints them over this
    //$$  * mod's immediate tessellator draws issued in between; earlier versions fill immediately
    //$$  * and 1.21.8+ records GUI elements in order, so it is a no-op there.
    //$$  */
    //$$ public void flushGui() {
    //$$     context.draw();
    //$$ }
    //#else
    /** No-op where fills already paint in call order; see the buffered-version variant. */
    public void flushGui() {
    }
    //#endif

    //#if MC>=12000
    //$$ public DrawContext context() {
    //$$     return context;
    //$$ }
    //#endif

    //#if MC>=12108
    //$$ /**
    //$$  * Runs {@code draw} with this mod's accumulated transform installed as the context's own 2D
    //$$  * pose, the glyph origin folded into it.
    //$$  *
    //$$  * <p>From 1.21.6 text is recorded rather than drawn, so it has to go through the context to
    //$$  * be layered against the rest of the GUI at all - and the context carries a 2D pose instead
    //$$  * of a MatrixStack, so a rotated or scaled caller (minimap markers, waypoint labels) would
    //$$  * otherwise lose its transform. Folding the origin into the pose also keeps the sub-pixel
    //$$  * placement that the context's integer coordinates drop.
    //$$  */
    //$$ private void withTextPose(final float x, final float y, final Runnable draw) {
    //$$     final Matrix3x2fStack pose = context.getMatrices();
    //$$     pose.pushMatrix();
    //$$     try {
    //$$         final Matrix4f model = matrices.peek().getPositionMatrix();
    //$$         pose.set(model.m00(), model.m01(), model.m10(), model.m11(), model.m30(), model.m31());
    //$$         pose.translate(x, y);
    //$$         draw.run();
    //$$     } finally {
    //$$         pose.popMatrix();
    //$$     }
    //$$ }
    //#endif

    public int drawTextWithShadow(
        final TextRenderer renderer,
        final String text,
        final float x,
        final float y,
        final int color
    ) {
        //#if MC>=260100
        //$$ withTextPose(x, y, () -> context.text(renderer, text, 0, 0, revealed(color)));
        //$$ return (int) x + renderer.width(text);
        //#elseif MC>=12108
        //$$ withTextPose(x, y, () -> context.drawTextWithShadow(renderer, text, 0, 0, revealed(color)));
        //$$ return (int) x + renderer.getWidth(text);
        //#elseif MC>=12103
        //$$ final int[] result = {0};
        //$$ context.draw(vertexConsumers -> result[0] = renderer.draw(
        //$$     text, x, y, revealed(color), true, matrices().peek().getPositionMatrix(),
        //$$     vertexConsumers, TextLayerType.NORMAL, 0, 15728880
        //$$ ));
        //$$ return result[0];
        //#elseif MC>=12000
        //$$ final int result = renderer.draw(
        //$$     text, x, y, revealed(color), true, matrices().peek().getPositionMatrix(),
        //$$     context.getVertexConsumers(), TextLayerType.NORMAL, 0, 15728880,
        //$$     renderer.isRightToLeft()
        //$$ );
        //$$ context.draw();
        //$$ return result;
        //#else
        return renderer.drawWithShadow(matrices, text, x, y, revealed(color));
        //#endif
    }

    public int drawTextWithShadow(
        final TextRenderer renderer,
        final Text text,
        final float x,
        final float y,
        final int color
    ) {
        //#if MC>=260100
        //$$ withTextPose(x, y, () -> context.text(renderer, text, 0, 0, revealed(color)));
        //$$ return (int) x + renderer.width(text);
        //#elseif MC>=12108
        //$$ withTextPose(x, y, () -> context.drawTextWithShadow(renderer, text, 0, 0, revealed(color)));
        //$$ return (int) x + renderer.getWidth(text);
        //#elseif MC>=12103
        //$$ final int[] result = {0};
        //$$ context.draw(vertexConsumers -> result[0] = renderer.draw(
        //$$     text, x, y, revealed(color), true, matrices().peek().getPositionMatrix(),
        //$$     vertexConsumers, TextLayerType.NORMAL, 0, 15728880
        //$$ ));
        //$$ return result[0];
        //#elseif MC>=12000
        //$$ final int result = renderer.draw(
        //$$     text, x, y, revealed(color), true, matrices().peek().getPositionMatrix(),
        //$$     context.getVertexConsumers(), TextLayerType.NORMAL, 0, 15728880
        //$$ );
        //$$ context.draw();
        //$$ return result;
        //#else
        return renderer.drawWithShadow(matrices, text, x, y, revealed(color));
        //#endif
    }

    public int drawTextWithShadow(
        final TextRenderer renderer,
        final OrderedText text,
        final float x,
        final float y,
        final int color
    ) {
        //#if MC>=260100
        //$$ withTextPose(x, y, () -> context.text(renderer, text, 0, 0, revealed(color)));
        //$$ return (int) x + renderer.width(text);
        //#elseif MC>=12108
        //$$ withTextPose(x, y, () -> context.drawTextWithShadow(renderer, text, 0, 0, revealed(color)));
        //$$ return (int) x + renderer.getWidth(text);
        //#elseif MC>=12103
        //$$ final int[] result = {0};
        //$$ context.draw(vertexConsumers -> result[0] = renderer.draw(
        //$$     text, x, y, revealed(color), true, matrices().peek().getPositionMatrix(),
        //$$     vertexConsumers, TextLayerType.NORMAL, 0, 15728880
        //$$ ));
        //$$ return result[0];
        //#elseif MC>=12000
        //$$ final int result = renderer.draw(
        //$$     text, x, y, revealed(color), true, matrices().peek().getPositionMatrix(),
        //$$     context.getVertexConsumers(), TextLayerType.NORMAL, 0, 15728880
        //$$ );
        //$$ context.draw();
        //$$ return result;
        //#else
        return renderer.drawWithShadow(matrices, text, x, y, revealed(color));
        //#endif
    }
}
