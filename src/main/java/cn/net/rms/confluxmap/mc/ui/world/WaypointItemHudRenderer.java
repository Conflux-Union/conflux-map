package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
//#if MC<260100
import cn.net.rms.confluxmap.mixin.GameRendererAccessor;
//#endif
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import java.util.List;
//#if MC>=12106
//$$ import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
//#elseif MC>=12104
//$$ import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
//#if MC>=260100
//$$ import net.minecraft.client.DeltaTracker;
//$$ import net.minecraft.client.gui.GuiGraphicsExtractor;
//#endif
import net.minecraft.client.render.Camera;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#endif
//#if MC>=12100
//$$ import net.minecraft.client.render.RenderTickCounter;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Draws complete item-backed waypoint labels in one current-frame flat HUD pass. */
public final class WaypointItemHudRenderer {
    private final MinecraftClient client;
    private final ConfluxConfig config;
    private List<Label> labels = List.of();

    public WaypointItemHudRenderer(final MinecraftClient client, final ConfluxConfig config) {
        this.client = client;
        this.config = config;
    }

    public void register() {
        //#if MC>=12106
        //$$ HudElementRegistry.attachElementBefore(
        //$$     VanillaHudElements.CROSSHAIR,
        //$$     cn.net.rms.confluxmap.compat.Ids.of("confluxmap", "waypoint_items"),
        //$$     this::render
        //$$ );
        //#elseif MC>=12104
        //$$ HudLayerRegistrationCallback.EVENT.register(layers -> layers.attachLayerBefore(
        //$$     IdentifiedLayer.CROSSHAIR,
        //$$     cn.net.rms.confluxmap.compat.Ids.of("confluxmap", "waypoint_items"),
        //$$     this::render
        //$$ ));
        //#endif
    }

    public void publish(final List<Label> labels) {
        this.labels = List.copyOf(labels);
    }

    List<Label> snapshot() {
        return labels;
    }

    //#if MC>=260100
    //$$ private void render(final GuiGraphicsExtractor context, final DeltaTracker tickCounter) {
    //$$     draw(GuiDraw.of(context), 0f);
    //#elseif MC>=12104
    //$$ private void render(final DrawContext context, final RenderTickCounter tickCounter) {
    //#if MC>=12109
    //$$     draw(GuiDraw.of(context), tickCounter.getTickProgress(false));
    //#else
    //$$     draw(GuiDraw.of(context), tickCounter.getTickDelta(false));
    //#endif
    //#elseif MC>=12100
    //$$ public void renderBeforeCrosshair(final DrawContext context, final RenderTickCounter tickCounter) {
    //#if MC>=12109
    //$$     draw(GuiDraw.of(context), tickCounter.getTickProgress(false));
    //#else
    //$$     draw(GuiDraw.of(context), tickCounter.getTickDelta(false));
    //#endif
    //#elseif MC>=12000
    //$$ public void renderBeforeCrosshair(final DrawContext context) {
    //$$     draw(GuiDraw.of(context), client.getTickDelta());
    //#else
    public void renderBeforeCrosshair(final MatrixStack matrices) {
        draw(GuiDraw.of(matrices), client.getTickDelta());
    //#endif
    }

    private void draw(final GuiDraw draw, final float tickDelta) {
        final Camera camera = camera();
        final Vec3d cameraPos = cameraPosition(camera);
        final float cameraYaw = cameraYaw(camera);
        final float cameraPitch = cameraPitch(camera);
        final double verticalFov = verticalFov(camera, tickDelta);
        //#if MC>=260100
        //$$ final int screenWidth = client.getWindow().getGuiScaledWidth();
        //$$ final int screenHeight = client.getWindow().getGuiScaledHeight();
        //#else
        final int screenWidth = client.getWindow().getScaledWidth();
        final int screenHeight = client.getWindow().getScaledHeight();
        //#endif

        for (final Label label : labels) {
            final WaypointRenderEntry waypoint = label.waypoint();
            final double dx = waypoint.x() - cameraPos.x;
            final double dy = waypoint.y() + WaypointWorldRenderer.LABEL_Y_OFFSET - cameraPos.y;
            final double dz = waypoint.z() - cameraPos.z;
            final double anchorDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            final double renderedDistance = WaypointWorldRenderer.projectedLabelDistance(
                anchorDistance, label.projectionDistance()
            );
            final float easedProgress = WaypointHudMotion.smoothStep(label.animationProgress());
            final float iconSize = MathHelper.lerp(
                easedProgress,
                WaypointWorldRenderer.LABEL_ICON_COLLAPSED_SIZE,
                WaypointWorldRenderer.LABEL_ICON_EXPANDED_SIZE
            );
            WaypointHudItemProjection.project(
                cameraYaw,
                cameraPitch,
                dx,
                dy,
                dz,
                screenWidth,
                screenHeight,
                verticalFov,
                renderedDistance,
                iconSize,
                config.waypointLabelScalePercent
            ).ifPresent(placement -> drawLabel(draw, label, placement, easedProgress));
        }
    }

    private void drawLabel(
        final GuiDraw draw,
        final Label label,
        final WaypointHudItemProjection.Placement placement,
        final float easedProgress
    ) {
        final WaypointRenderEntry waypoint = label.waypoint();
        final float nearFade = (float) MathHelper.clamp(
            label.distance3d() / WaypointWorldRenderer.LABEL_NEAR_FADE_BLOCKS,
            0.0,
            1.0
        );
        if (nearFade <= 0.01f) {
            return;
        }
        final float unitScale = placement.unitScale();
        final float iconSize = placement.size();
        final float iconHalfSize = iconSize / 2f;
        final float centerX = placement.centerX();
        final float centerY = placement.centerY();
        final String name = waypoint.name();
        final String distanceText = Math.round(label.distance3d()) + " m";
        final TextRenderer textRenderer = textRenderer();
        final int nameWidth = textRenderer.getWidth(name);
        final int distanceWidth = textRenderer.getWidth(distanceText);
        final float panelFullWidth = Math.max(nameWidth, distanceWidth)
            + WaypointWorldRenderer.LABEL_PANEL_PADDING * 2f;
        final float panelReveal = MathHelper.clamp(
            easedProgress / WaypointWorldRenderer.LABEL_TEXT_REVEAL_START, 0f, 1f
        );
        final float panelWidth = panelFullWidth * panelReveal * unitScale;
        final float panelX = centerX + iconHalfSize
            + WaypointWorldRenderer.LABEL_PANEL_GAP * unitScale;
        final float visibilityAlpha = nearFade * label.visibilityAlpha();
        if (panelWidth > 0.5f) {
            fill(
                draw,
                panelX,
                centerY - WaypointWorldRenderer.LABEL_PANEL_HEIGHT * unitScale / 2f,
                panelX + panelWidth,
                centerY + WaypointWorldRenderer.LABEL_PANEL_HEIGHT * unitScale / 2f,
                WaypointWorldRenderer.withAlpha(
                    WaypointWorldRenderer.LABEL_BACKGROUND_COLOR, visibilityAlpha
                )
            );
        }

        final float plateAlpha = visibilityAlpha * config.waypointIconOpacity
            / (float) ConfluxConfig.MAX_WAYPOINT_ICON_OPACITY;
        fill(
            draw,
            centerX - iconHalfSize - unitScale,
            centerY - iconHalfSize - unitScale,
            centerX + iconHalfSize + unitScale,
            centerY + iconHalfSize + unitScale,
            WaypointWorldRenderer.withAlpha(
                label.selected() ? 0xFFFFE066 : WaypointWorldRenderer.outlineColor(waypoint),
                plateAlpha
            )
        );
        fill(
            draw,
            centerX - iconHalfSize,
            centerY - iconHalfSize,
            centerX + iconHalfSize,
            centerY + iconHalfSize,
            WaypointWorldRenderer.withAlpha(waypoint.colorArgb() | 0xFF000000, plateAlpha)
        );
        final ItemStack stack = WaypointMarkerRenderer.itemIcon(waypoint.iconItemId());
        if (!stack.isEmpty()) {
            draw.drawItemIcon(client, stack, centerX, centerY, iconSize);
        }

        final float textReveal = MathHelper.clamp(
            (easedProgress - WaypointWorldRenderer.LABEL_TEXT_REVEAL_START)
                / (1f - WaypointWorldRenderer.LABEL_TEXT_REVEAL_START),
            0f,
            1f
        );
        if (textReveal <= 0.01f) {
            return;
        }
        final float textX = iconSize / (2f * unitScale)
            + WaypointWorldRenderer.LABEL_PANEL_GAP
            + WaypointWorldRenderer.LABEL_PANEL_PADDING
            + (1f - textReveal) * 4f;
        final float textAlpha = visibilityAlpha * textReveal;
        draw.pushTransform();
        draw.translate(centerX, centerY);
        draw.scale(unitScale, unitScale);
        draw.drawTextWithShadow(
            textRenderer,
            name,
            textX,
            -9f,
            WaypointWorldRenderer.withAlpha(WaypointWorldRenderer.LABEL_NAME_COLOR, textAlpha)
        );
        draw.drawTextWithShadow(
            textRenderer,
            distanceText,
            textX,
            1f,
            WaypointWorldRenderer.withAlpha(WaypointWorldRenderer.LABEL_DISTANCE_COLOR, textAlpha)
        );
        draw.popTransform();
    }

    private TextRenderer textRenderer() {
        //#if MC>=260100
        //$$ return client.font;
        //#else
        return client.textRenderer;
        //#endif
    }

    private static void fill(
        final GuiDraw draw,
        final float x1,
        final float y1,
        final float x2,
        final float y2,
        final int color
    ) {
        draw.fill(
            MathHelper.floor(x1),
            MathHelper.floor(y1),
            MathHelper.ceil(x2),
            MathHelper.ceil(y2),
            color
        );
    }

    private Camera camera() {
        //#if MC>=260200
        //$$ return client.gameRenderer.mainCamera();
        //#elseif MC>=260100
        //$$ return client.gameRenderer.getMainCamera();
        //#else
        return client.gameRenderer.getCamera();
        //#endif
    }

    private static Vec3d cameraPosition(final Camera camera) {
        //#if MC>=260100
        //$$ return camera.position();
        //#elseif MC>=12111
        //$$ return camera.getCameraPos();
        //#else
        return camera.getPos();
        //#endif
    }

    private static float cameraYaw(final Camera camera) {
        //#if MC>=260100
        //$$ return camera.yRot();
        //#else
        return camera.getYaw();
        //#endif
    }

    private static float cameraPitch(final Camera camera) {
        //#if MC>=260100
        //$$ return camera.xRot();
        //#else
        return camera.getPitch();
        //#endif
    }

    private double verticalFov(final Camera camera, final float tickDelta) {
        //#if MC>=260100
        //$$ return camera.getFov();
        //#else
        return ((GameRendererAccessor) client.gameRenderer).confluxmap$getFov(
            camera, tickDelta, true
        );
        //#endif
    }

    public record Label(
        WaypointRenderEntry waypoint,
        double distance3d,
        double projectionDistance,
        float animationProgress,
        float visibilityAlpha,
        boolean selected
    ) {
    }
}
