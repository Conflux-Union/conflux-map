package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.WorldMatrices;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import com.mojang.blaze3d.systems.RenderSystem;
//#if MC>=260200
//$$ import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
//$$ import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
//$$ import net.minecraft.client.renderer.feature.TextFeatureRenderer;
//$$ import net.minecraft.client.renderer.rendertype.RenderTypes;
//$$ import net.minecraft.network.chat.Component;
//$$ import org.joml.Matrix4f;
//#endif
//#if MC>=260200
//$$ import net.minecraft.client.gui.Font;
//#endif
//#if MC>=260300
//$$ import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
//#endif
import net.minecraft.client.MinecraftClient;
//#if MC<260200
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
//#endif
import net.minecraft.client.util.math.MatrixStack;

/**
 * Owns the label pass's per-version state brackets, billboard transform, and submissions.
 * {@link #beginPass} and {@link #endPass} bracket one label render: before 1.21.9 they
 * reinstall the world projection and the view matrix captured at LAST (the same END state
 * repair the beam pass makes), while the in-pass phases from 1.21.9 to 26.1 only save the
 * global ModelView through {@link WorldMatrices#pushWorldHudModelView} so the billboard keeps
 * the world view rotation vanilla installed. Inside that bracket, {@link #beginLabels}/
 * {@link #endLabels} wrap the label loop: before 1.21.5 the labels disable depth testing
 * (world content must never hide player-facing navigation information) and the shared
 * entity vertex consumers are flushed once after the loop on every pre-26.2 version. The
 * layout itself arrives from {@link WaypointLabelPainter}; this class applies the camera
 * rotation and submits each quad/text run through the version's pipeline.
 */
final class LabelPass implements AutoCloseable, WaypointLabelPainter.Sink {
    private final MinecraftClient client;
    private final WaypointLabelPainter labelPainter;
    /** The pass frame, valid between {@link #beginPass} and {@link #endPass}. */
    private OverlayFrame frame;
    //#if MC>=260300
    //$$ private OrderedSubmitNodeCollector plateSubmits;
    //$$ private OrderedSubmitNodeCollector textSubmits;
    //#elseif MC>=260200
    //$$ private FabricOrderedSubmitNodeCollector plateSubmits;
    //$$ private FabricOrderedSubmitNodeCollector textSubmits;
    //#endif

    static LabelPass of(final MinecraftClient client, final ConfluxConfig config) {
        return new LabelPass(client, config);
    }

    private LabelPass(final MinecraftClient client, final ConfluxConfig config) {
        this.client = client;
        this.labelPainter = new WaypointLabelPainter(client, config);
    }

    void beginPass(final OverlayFrame frame) {
        this.frame = frame;
        //#if MC<12109
        WorldMatrices.applyWorldProjection(frame.projectionMatrix);
        WorldMatrices.pushWorldViewSnapshot();
        //#elseif MC<260200
        //$$ WorldMatrices.pushWorldHudModelView();
        //#endif
    }

    void beginLabels() {
        //#if MC<12105
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        //#endif
        //#if MC>=260200
        //$$ // 26.1 collects item submits separately so they render in the current frame;
        //$$ // plates go into order(0) and text into order(1), matching the painter's layering.
        //#if MC>=260300
        //$$ plateSubmits = frame.labelSubmits.order(0);
        //$$ textSubmits = frame.labelSubmits.order(1);
        //#else
        //$$ plateSubmits = (FabricOrderedSubmitNodeCollector) frame.labelSubmits.order(0);
        //$$ textSubmits = (FabricOrderedSubmitNodeCollector) frame.labelSubmits.order(1);
        //#endif
        //#endif
    }

    void drawLabel(
        final double worldX,
        final double worldY,
        final double worldZ,
        final WaypointRenderEntry waypoint,
        final double distance3d,
        final double projectionDistance,
        final float animationProgress,
        final float visibilityAlpha,
        final boolean selected
    ) {
        labelPainter.drawLabel(
            frame.matrices, frame.cameraPos, worldX, worldY, worldZ,
            waypoint, distance3d, projectionDistance, animationProgress,
            visibilityAlpha, selected, this
        );
    }

    /**
     * The billboard rotation in its three forms - a matrix multiply on 26.3, which dropped
     * quaternion poses; a quaternion multiply on 26.2; the camera's rotation everywhere
     * earlier - then the label scale. Vanilla's modern billboard needs the positive X sign
     * from 1.21 on; earlier versions cull their quads unless X is negative.
     */
    @Override
    public void billboard(final MatrixStack matrices, final float scale) {
        //#if MC>=260300
        //$$ matrices.mulPose(new org.joml.Matrix4f().rotation(frame.cameraRotation));
        //#elseif MC>=260200
        //$$ matrices.mulPose(frame.cameraRotation);
        //#else
        matrices.multiply(frame.camera.getRotation());
        //#endif
        //#if MC>=12100
        //$$ matrices.scale(scale, -scale, scale);
        //#else
        matrices.scale(-scale, -scale, scale);
        //#endif
    }

    @Override
    public void rect(final MatrixStack matrices, final float x, final float y, final float width, final float height, final int color) {
        //#if MC>=260200
        //$$ submitRect(matrices, x, y, width, height, color);
        //#else
        GuiShapes.fillRect3D(matrices, x, y, width, height, color);
        //#endif
    }

    @Override
    public void text(final MatrixStack matrices, final String text, final float x, final float y, final int color) {
        //#if MC>=260200
        //$$ submitText(matrices, text, x, y, color);
        //#else
        drawSeeThroughText(
            client.textRenderer, text, x, y, color, matrices, frame.immediate,
            WaypointLabelStyle.LABEL_LIGHT
        );
        //#endif
    }

    void endLabels() {
        //#if MC<260200
        frame.immediate.draw();
        //#endif
    }

    void endPass() {
        //#if MC<12105
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        //#endif
        //#if MC<12109
        WorldMatrices.popModelView();
        WorldMatrices.restoreProjection();
        //#elseif MC<260200
        //$$ WorldMatrices.popModelView();
        //#endif
        frame = null;
    }

    //#if MC>=260200
    //$$ private void submitRect(
    //$$     final PoseStack matrices,
    //$$     final float x,
    //$$     final float y,
    //$$     final float width,
    //$$     final float height,
    //$$     final int color
    //$$ ) {
    //#if MC>=260300
    //$$     // 26.3 removed RenderTypes.textBackgroundSeeThrough and rebuilt the text Submit
    //$$     // records around Content objects; the collector's own background submit replaces
    //$$     // the custom-geometry path entirely.
    //$$     plateSubmits.submitTextBackground(
    //$$         matrices, x, y, x + width, y + height, color,
    //$$         Font.DisplayMode.SEE_THROUGH, WaypointLabelStyle.LABEL_LIGHT
    //$$     );
    //#else
    //$$     plateSubmits.submitCustom(
    //$$         SubmitRenderPhases.ALWAYS_ON_TOP,
    //$$         new CustomFeatureRenderer.Submit(
    //$$             matrices.last().copy(),
    //$$             RenderTypes.textBackgroundSeeThrough(),
    //$$             (pose, vertices) -> {
    //$$                 vertices.addVertex(pose, x, y + height, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
    //$$                 vertices.addVertex(pose, x + width, y + height, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
    //$$                 vertices.addVertex(pose, x + width, y, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
    //$$                 vertices.addVertex(pose, x, y, 0f).setColor(color).setLight(WaypointLabelStyle.LABEL_LIGHT);
    //$$             }
    //$$         )
    //$$     );
    //#endif
    //$$ }
    //$$
    //$$ private void submitText(
    //$$     final PoseStack matrices,
    //$$     final String text,
    //$$     final float x,
    //$$     final float y,
    //$$     final int color
    //$$ ) {
    //#if MC>=260300
    //$$     textSubmits.submitText(
    //$$         matrices, x, y, Component.literal(text).getVisualOrderText(),
    //$$         false, Font.DisplayMode.SEE_THROUGH, WaypointLabelStyle.LABEL_LIGHT, color, 0, 0
    //$$     );
    //#else
    //$$     textSubmits.submitCustom(
    //$$         SubmitRenderPhases.ALWAYS_ON_TOP,
    //$$         new TextFeatureRenderer.Submit(
    //$$             new Matrix4f(matrices.last().pose()),
    //$$             x,
    //$$             y,
    //$$             Component.literal(text).getVisualOrderText(),
    //$$             false,
    //$$             Font.DisplayMode.SEE_THROUGH,
    //$$             WaypointLabelStyle.LABEL_LIGHT,
    //$$             color,
    //$$             0,
    //$$             0
    //$$         )
    //$$     );
    //#endif
    //$$ }
    //#endif

    //#if MC<260200
    /** Draws fully-lit marker text through the versioned text-layer argument. */
    private static void drawSeeThroughText(
        final TextRenderer textRenderer,
        final String text,
        final float x,
        final float y,
        final int color,
        final MatrixStack matrices,
        final VertexConsumerProvider.Immediate immediate,
        final int light
    ) {
        //#if MC>=12000
        //$$ textRenderer.draw(
        //$$     text, x, y, color, false, matrices.peek().getPositionMatrix(), immediate,
        //$$     TextRenderer.TextLayerType.SEE_THROUGH, 0, light
        //$$ );
        //#else
        textRenderer.draw(
            text, x, y, color, false, matrices.peek().getModel(), immediate, true, 0, light
        );
        //#endif
    }
    //#endif
    /**
     * The pass brackets restore every state they touch inline, so nothing here outlives
     * {@link #endPass}; close keeps the seam closeable as per-version native state moves
     * in behind it.
     */
    @Override
    public void close() {
    }
}
