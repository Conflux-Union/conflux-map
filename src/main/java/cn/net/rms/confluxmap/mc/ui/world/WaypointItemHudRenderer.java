package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
//#if MC<260100
import cn.net.rms.confluxmap.mixin.GameRendererAccessor;
//#endif
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.mc.render.WaypointHudFrameMatrices;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.RadarMarkerRenderer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    private final EntityIconManager iconManager;
    private List<Label> labels = List.of();

    public WaypointItemHudRenderer(final MinecraftClient client, final ConfluxConfig config) {
        this(client, config, null);
    }

    public WaypointItemHudRenderer(
        final MinecraftClient client,
        final ConfluxConfig config,
        final EntityIconManager iconManager
    ) {
        this.client = client;
        this.config = config;
        this.iconManager = iconManager;
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
        // On versions where this rides the crosshair render, vanilla's own hideGui guard keeps
        // this from firing under F1; on the Fabric HUD-element paths (1.21.6+) it must check.
        if (MinecraftAccess.isHudHidden(client)) {
            return;
        }
        final Camera camera = camera();
        final Vec3d cameraPos = cameraPosition(camera);
        final float cameraYaw = cameraYaw(camera);
        final float cameraPitch = cameraPitch(camera);
        final double verticalFov = verticalFov(camera, tickDelta);
        final WaypointHudFrameMatrices.Snapshot frame = WaypointHudFrameMatrices.current();
        //#if MC>=260100
        //$$ final int screenWidth = client.getWindow().getGuiScaledWidth();
        //$$ final int screenHeight = client.getWindow().getGuiScaledHeight();
        //#else
        final int screenWidth = client.getWindow().getScaledWidth();
        final int screenHeight = client.getWindow().getScaledHeight();
        //#endif

        // The label geometry below draws immediately, so buffered context fills (1.20-1.21.7)
        // queued before this callback would otherwise paint over it.
        if (!labels.isEmpty()) {
            draw.flushGui();
        }
        for (final Label label : labels) {
            final WaypointRenderEntry waypoint = label.waypoint();
            final double dx = waypoint.x() - cameraPos.x;
            final double dy = waypoint.y() + WaypointLabelMetrics.LABEL_Y_OFFSET - cameraPos.y;
            final double dz = waypoint.z() - cameraPos.z;
            final double anchorDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            final double renderedDistance = WaypointLabelMetrics.projectedLabelDistance(
                anchorDistance, label.projectionDistance()
            );
            final float easedProgress = WaypointHudMotion.smoothStep(label.animationProgress());
            final float iconSize = MathHelper.lerp(
                easedProgress,
                WaypointLabelStyle.LABEL_ICON_COLLAPSED_SIZE,
                WaypointLabelStyle.LABEL_ICON_EXPANDED_SIZE
            );
            // Prefer the world pass's captured matrices - they carry the nausea sway exactly
            // like the 3D labels; without a capture this frame, fall back to the camera basis.
            final Optional<WaypointHudItemProjection.Placement> placement = frame != null
                ? WaypointHudItemProjection.project(
                    frame,
                    dx,
                    dy,
                    dz,
                    screenWidth,
                    screenHeight,
                    renderedDistance,
                    easedProgress,
                    iconSize,
                    config.waypointLabelScalePercent
                )
                : WaypointHudItemProjection.project(
                    cameraYaw,
                    cameraPitch,
                    dx,
                    dy,
                    dz,
                    screenWidth,
                    screenHeight,
                    verticalFov,
                    renderedDistance,
                    easedProgress,
                    iconSize,
                    config.waypointLabelScalePercent
                );
            placement.ifPresent(value -> drawLabel(draw, label, value, easedProgress));
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
            label.distance3d() / WaypointLabelStyle.LABEL_NEAR_FADE_BLOCKS,
            0.0,
            1.0
        );
        if (nearFade <= 0.01f) {
            return;
        }
        final float unitScale = placement.unitScale();
        final float iconSize = placement.size();
        final float centerX = placement.centerX();
        final float centerY = placement.centerY();
        final String name = waypoint.name();
        final String distanceText = Math.round(label.distance3d()) + " m";
        final TextRenderer textRenderer = textRenderer();
        final int nameWidth = textRenderer.getWidth(name);
        final int distanceWidth = textRenderer.getWidth(distanceText);
        final float panelFullWidth = Math.max(nameWidth, distanceWidth)
            + WaypointLabelStyle.LABEL_PANEL_PADDING * 2f;
        final float panelReveal = MathHelper.clamp(
            easedProgress / WaypointLabelStyle.LABEL_TEXT_REVEAL_START, 0f, 1f
        );
        // Everything below is label-local (origin at the icon center, GUI units), so plate,
        // icon, portrait and text all ride one float transform instead of stepping whole GUI
        // pixels.
        final float panelWidth = panelFullWidth * panelReveal;
        final float innerHalf = iconSize / (2f * unitScale);
        final float gap = WaypointLabelStyle.LABEL_PANEL_GAP;
        final float visibilityAlpha = nearFade * label.visibilityAlpha();
        final float labelOpacity = WaypointLabelStyle.labelOpacityFactor(config);
        final float plateAlpha = visibilityAlpha * labelOpacity;

        draw.pushTransform();
        try {
            draw.translate(centerX, centerY);
            draw.scale(unitScale, unitScale);
            if (panelWidth * unitScale > 0.5f) {
                draw.fillRect(
                    innerHalf + gap,
                    -WaypointLabelStyle.LABEL_PANEL_HEIGHT / 2f,
                    innerHalf + gap + panelWidth,
                    WaypointLabelStyle.LABEL_PANEL_HEIGHT / 2f,
                    WaypointLabelStyle.withAlpha(
                        WaypointLabelStyle.LABEL_BACKGROUND_COLOR, visibilityAlpha * labelOpacity
                    )
                );
            }

            final PlateFrame plate = plateFrame(innerHalf, unitScale);
            final int ringColor = WaypointLabelStyle.withAlpha(
                label.selected() ? 0xFFFFE066 : WaypointLabelStyle.outlineColor(waypoint),
                plateAlpha
            );
            for (final Quad quad : ringQuads(plate)) {
                draw.fillRect(quad.x0(), quad.y0(), quad.x1(), quad.y1(), ringColor);
            }
            draw.fillRect(
                plate.left(),
                plate.top(),
                plate.right(),
                plate.bottom(),
                WaypointLabelStyle.withAlpha(waypoint.colorArgb() | 0xFF000000, plateAlpha)
            );
            final float iconLocalSize = iconSize / unitScale;
            if (label.playerId() != null) {
                RadarMarkerRenderer.drawPlayerPortrait(
                    draw,
                    client,
                    iconManager,
                    label.playerId(),
                    0f,
                    0f,
                    iconLocalSize,
                    plateAlpha,
                    label.selected()
                );
            } else {
                final ItemStack stack = WaypointMarkerRenderer.itemIcon(waypoint.iconItemId());
                if (!stack.isEmpty()) {
                    draw.drawItemIcon(client, stack, 0f, 0f, iconLocalSize);
                }
            }

            final float textReveal = MathHelper.clamp(
                (easedProgress - WaypointLabelStyle.LABEL_TEXT_REVEAL_START)
                    / (1f - WaypointLabelStyle.LABEL_TEXT_REVEAL_START),
                0f,
                1f
            );
            if (textReveal > 0.01f) {
                final float textX = innerHalf
                    + gap
                    + WaypointLabelStyle.LABEL_PANEL_PADDING
                    + (1f - textReveal) * 4f;
                final float textAlpha = visibilityAlpha * textReveal * labelOpacity;
                draw.drawTextWithShadow(
                    textRenderer,
                    name,
                    textX,
                    -9f,
                    WaypointLabelStyle.withAlpha(WaypointLabelStyle.LABEL_NAME_COLOR, textAlpha)
                );
                draw.drawTextWithShadow(
                    textRenderer,
                    distanceText,
                    textX,
                    1f,
                    WaypointLabelStyle.withAlpha(WaypointLabelStyle.LABEL_DISTANCE_COLOR, textAlpha)
                );
            }
        } finally {
            draw.popTransform();
        }
    }

    /**
     * Plate geometry in label-local units: the inner square plus a ring emitted as four edge
     * quads extending outward from that square by {@code border}. Every edge derives from the
     * same floats, so the band around the square survives any sub-pixel label position - the
     * previous version rounded two overlapping rects independently, which let the under-one-pixel
     * ring collapse onto the square and flicker while the label moved.
     */
    record PlateFrame(float left, float top, float right, float bottom, float border) {
    }

    static PlateFrame plateFrame(final float halfSize, final float unitScale) {
        return new PlateFrame(
            -halfSize,
            -halfSize,
            halfSize,
            halfSize,
            Math.max(1f, unitScale) / unitScale
        );
    }

    /** One axis-aligned ring-quad fill in label-local units. */
    record Quad(float x0, float y0, float x1, float y1) {
    }

    static Quad[] ringQuads(final PlateFrame plate) {
        final float border = plate.border();
        return new Quad[] {
            new Quad(
                plate.left() - border, plate.top() - border,
                plate.right() + border, plate.top()
            ),
            new Quad(
                plate.left() - border, plate.bottom(),
                plate.right() + border, plate.bottom() + border
            ),
            new Quad(
                plate.left() - border, plate.top(),
                plate.left(), plate.bottom()
            ),
            new Quad(
                plate.right(), plate.top(),
                plate.right() + border, plate.bottom()
            ),
        };
    }

    private TextRenderer textRenderer() {
        //#if MC>=260100
        //$$ return client.font;
        //#else
        return client.textRenderer;
        //#endif
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
        boolean selected,
        UUID playerId
    ) {
        public Label(
            final WaypointRenderEntry waypoint,
            final double distance3d,
            final double projectionDistance,
            final float animationProgress,
            final float visibilityAlpha,
            final boolean selected
        ) {
            this(
                waypoint, distance3d, projectionDistance,
                animationProgress, visibilityAlpha, selected, null
            );
        }
    }
}
