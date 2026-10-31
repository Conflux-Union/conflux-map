package cn.net.rms.confluxmap.mc.render;

import com.mojang.blaze3d.systems.RenderSystem;
//#if MC>=12105
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//$$ import com.mojang.blaze3d.systems.RenderPass;
//$$ import net.minecraft.client.gl.Framebuffer;
//#if MC>=12108
//$$ import net.minecraft.client.gui.ScreenRect;
//$$ import net.minecraft.client.gui.render.state.GuiRenderState;
//#endif
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;

/**
 * Tracks where this mod's GUI-space batches render: the overridden draw target, the active
 * scissor rectangle (framebuffer pixels plus, on 1.21.8+, the scaled GUI units the recorded
 * GUI pass clips in), and the GUI element list the game is currently collecting.
 * {@link OffscreenCanvas} retargets draws at its own framebuffer through
 * {@link #setDrawTarget}; {@link Mesh} reads the state back when it finishes a batch,
 * and on 26.3 resolves pipelines through {@link #compiled} because a render pass only
 * accepts pipelines the game's fallback cache has already compiled.
 */
public final class RenderTargetState {
    //#if MC>=12105
    //$$ private static Framebuffer drawTarget;
    //$$ private static boolean scissorEnabled;
    //$$ private static int scissorX;
    //$$ private static int scissorY;
    //$$ private static int scissorWidth;
    //$$ private static int scissorHeight;
    //#if MC>=12108
    //$$ private static GuiRenderState guiState;
    //$$ // The GUI renderer clips in scaled GUI units, the render pass in framebuffer pixels.
    //$$ private static int guiScissorX;
    //$$ private static int guiScissorY;
    //$$ private static int guiScissorWidth;
    //$$ private static int guiScissorHeight;
    //#endif
    //#endif

    private RenderTargetState() {
    }

    //#if MC>=12105
    //$$ static Framebuffer drawTarget() {
    //#if MC>=260200
    //$$     return drawTarget == null
    //$$         ? Minecraft.getInstance().gameRenderer.mainRenderTarget()
    //$$         : drawTarget;
    //#else
    //$$     return drawTarget == null ? MinecraftClient.getInstance().getFramebuffer() : drawTarget;
    //#endif
    //$$ }
    //$$
    //$$ static void setDrawTarget(final Framebuffer target) {
    //$$     drawTarget = target;
    //$$ }
    //$$
    //$$ static void applyScissor(final RenderPass pass) {
    //$$     if (scissorEnabled) {
    //$$         pass.enableScissor(scissorX, scissorY, scissorWidth, scissorHeight);
    //$$     }
    //$$ }
    //#endif

//#if MC>=12108
    //$$ /**
    //$$  * Points GUI-space batches at the element list the game is currently collecting. Set once
    //$$  * per {@code GuiDraw}, which is the only way into this mod's screen and HUD drawing.
    //$$  */
    //$$ public static void setGuiState(final GuiRenderState state) {
    //$$     guiState = state;
    //$$ }
    //$$
    //$$ /** Null while an {@link OffscreenCanvas} owns the draws - those target its own framebuffer. */
    //$$ static GuiRenderState guiState() {
    //$$     return drawTarget == null ? guiState : null;
    //$$ }
    //$$
    //$$ static ScreenRect guiScissor() {
    //$$     return scissorEnabled
    //$$         ? new ScreenRect(guiScissorX, guiScissorY, guiScissorWidth, guiScissorHeight)
    //$$         : null;
    //$$ }
//#endif

    //#if MC>=260300
    //$$ /**
    //$$  * 26.3 pipelines must be compiled before a render pass accepts them; the game's
    //$$  * fallback cache owns the live compiler and is refreshed on every shader reload.
    //$$  */
    //$$ static com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline compiled(final RenderPipeline pipeline) {
    //$$     return RenderSystem.fallbackPipelineCache.get(pipeline);
    //$$ }
    //#endif

    /**
     * Enables the GL scissor test for a rectangle given in GUI (scaled) coordinates,
     * converting to framebuffer pixels via the window's current scale factor.
     */
    public static void enableScissor(
        final MinecraftClient client,
        final int guiX,
        final int guiY,
        final int guiWidth,
        final int guiHeight
    ) {
        final Window window = client.getWindow();
        final double scale = window.getScaleFactor();
        final int fbHeight = window.getFramebufferHeight();
        final int x = (int) Math.round(guiX * scale);
        final int w = (int) Math.round(guiWidth * scale);
        final int h = (int) Math.round(guiHeight * scale);
        final int y = fbHeight - (int) Math.round((guiY + guiHeight) * scale);
        //#if MC>=12105
        //$$ scissorEnabled = true;
        //$$ scissorX = x;
        //$$ scissorY = y;
        //$$ scissorWidth = w;
        //$$ scissorHeight = h;
        //#if MC>=12108
        //$$ guiScissorX = guiX;
        //$$ guiScissorY = guiY;
        //$$ guiScissorWidth = guiWidth;
        //$$ guiScissorHeight = guiHeight;
        //#endif
        //#else
        RenderSystem.enableScissor(x, y, w, h);
        //#endif
    }

    /** Enables scissoring in the pixel coordinates of the currently bound off-screen target. */
    public static void enableTargetScissor(
        final int targetX,
        final int targetY,
        final int width,
        final int height,
        final int targetHeight
    ) {
        final int scissorY = targetScissorY(targetY, height, targetHeight);
        //#if MC>=12105
        //$$ scissorEnabled = true;
        //$$ scissorX = targetX;
        //$$ RenderTargetState.scissorY = scissorY;
        //$$ scissorWidth = width;
        //$$ scissorHeight = height;
        //#if MC>=12108
        //$$ guiScissorX = targetX;
        //$$ guiScissorY = targetY;
        //$$ guiScissorWidth = width;
        //$$ guiScissorHeight = height;
        //#endif
        //#else
        RenderSystem.enableScissor(targetX, scissorY, width, height);
        //#endif
    }

    static int targetScissorY(final int targetY, final int height, final int targetHeight) {
        return targetHeight - targetY - height;
    }

    public static void disableScissor() {
        //#if MC>=12105
        //$$ scissorEnabled = false;
        //#else
        RenderSystem.disableScissor();
        //#endif
    }
}
