package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.waypoint.DimensionScale;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderCatalog;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;

/**
 * Gives waypoints an in-world presence (user feedback driving this slice: "waypoints
 * have NO in-world presence"): a vertical translucent beam at each visible waypoint's
 * column, and a camera-facing name/distance label floating above it.
 *
 * <p>Registers on {@link WorldRenderEvents#AFTER_TRANSLUCENT}, which fires once solid,
 * cutout AND translucent terrain (water, glass, ...) are already in the framebuffer -
 * so a depth-tested beam/label correctly occludes against all of that, not just solid
 * blocks. Per that event's own javadoc in fabric-rendering-v1 1.10.1 (bundled with the
 * installed fabric-api 0.46.1+1.17), {@link WorldRenderContext#consumers()} is {@code
 * null} at this phase and its matrix stack carries no pre-existing camera translation -
 * both are handled here: the beam is drawn with plain {@code Tessellator}/{@code
 * BufferBuilder} calls ({@link RenderUtil#fillTriangle3D}) instead of a vertex consumer,
 * and every position is explicitly translated by {@code worldPos - camera.getPos()}
 * before drawing. The label's text/background instead opens its own {@link
 * VertexConsumerProvider.Immediate} (the same one vanilla uses for entity nametags,
 * {@code client.getBufferBuilders().getEntityVertexConsumers()}) which works at any
 * phase and is flushed once at the end of this render pass.
 */
public final class WaypointWorldRenderer {
    /** {@code config.waypointRenderDistance == 0} ("unlimited") caps beams at this instead, for perf - see deliverable A brief. */
    private static final double UNLIMITED_RENDER_DISTANCE_CAP = 2048.0;

    private static final double BEAM_HALF_WIDTH = 0.18;
    private static final float BEAM_CORE_ALPHA = 0.55f;
    /** Same near-camera fade-in constant as the label (waypoint-ux.md S6), applied to horizontal distance from the beam column. */
    private static final double BEAM_NEAR_FADE_BLOCKS = 5.0;
    /** Beam alpha never drops below this fraction of {@link #BEAM_CORE_ALPHA} even at the render-distance edge - "intensifies as you approach" without vanishing far away. */
    private static final float BEAM_FAR_FLOOR = 0.30f;

    private static final double LABEL_Y_OFFSET = 1.5;
    /** waypoint-ux.md S6 "distance fade-in": alpha ramps 0 -> 1 over the nearest ~5 blocks so the label doesn't pop in right next to the camera. */
    private static final double LABEL_NEAR_FADE_BLOCKS = 5.0;
    private static final float LABEL_BASE_SCALE = 0.025f;
    private static final double LABEL_SCALE_NEAR_BLOCKS = 16.0;
    private static final double LABEL_SCALE_GROWTH_PER_BLOCK = 48.0;
    private static final float LABEL_MAX_SCALE_MULT = 4.0f;
    private static final int LABEL_BACKGROUND_COLOR = 0x60000000;
    private static final int LABEL_DISTANCE_COLOR = 0xFFC8C8C8;
    /** LightmapTextureManager.pack(15, 15) - always fully lit, like other UI-ish world markers. */
    private static final int LABEL_LIGHT = 0xF000F0;
    private static final float LABEL_PADDING = 2f;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final WaypointRenderCatalog waypointRenderCatalog;

    public WaypointWorldRenderer(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge,
        final WaypointRenderCatalog waypointRenderCatalog
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.waypointRenderCatalog = waypointRenderCatalog;
    }

    public void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(this::render);
    }

    private void render(final WorldRenderContext context) {
        if (!config.waypointBeamsEnabled && !config.waypointLabelsEnabled) {
            return;
        }
        if (!gameBridge.session().active()) {
            return;
        }
        final Optional<PlayerView> playerViewOpt = gameBridge.player(context.tickDelta());
        if (playerViewOpt.isEmpty()) {
            return;
        }
        final PlayerView player = playerViewOpt.get();
        final DimensionId currentDimension = gameBridge.session().dimension();
        final Camera camera = context.camera();
        final Vec3d cameraPos = camera.getPos();
        final MatrixStack matrices = context.matrixStack();
        final double maxDistance = config.waypointRenderDistance > 0
            ? config.waypointRenderDistance
            : UNLIMITED_RENDER_DISTANCE_CAP;
        final double bottomY = 0.0;
        final double topY = currentDimension.equals(DimensionId.NETHER) ? 128.0 : 256.0;

        RenderSystem.enableDepthTest();
        if (config.waypointBeamsEnabled) {
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderUtil.beginAdditiveTriangles();
        }

        VertexConsumerProvider.Immediate immediate = null;
        for (final WaypointRenderEntry waypoint : waypointRenderCatalog.snapshot()) {
            if (!DimensionScale.isVisibleFrom(waypoint.dimensionId(), currentDimension)) {
                continue;
            }
            final double worldX = DimensionScale.convertHorizontal(waypoint.x(), waypoint.dimensionId(), currentDimension);
            final double worldZ = DimensionScale.convertHorizontal(waypoint.z(), waypoint.dimensionId(), currentDimension);
            final double dx = worldX - player.x();
            final double dy = waypoint.y() - player.y();
            final double dz = worldZ - player.z();
            final double distance3d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance3d > maxDistance) {
                continue;
            }

            if (config.waypointBeamsEnabled) {
                final double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
                drawBeam(
                    matrices, cameraPos, worldX, worldZ, bottomY, topY,
                    waypoint.colorArgb(), horizontalDistance, maxDistance
                );
            }
            if (config.waypointLabelsEnabled) {
                if (immediate == null) {
                    immediate = client.getBufferBuilders().getEntityVertexConsumers();
                }
                drawLabel(matrices, immediate, camera, cameraPos, worldX, waypoint.y(), worldZ, waypoint, distance3d);
            }
        }

        if (config.waypointBeamsEnabled) {
            RenderUtil.restoreDefaultBlend();
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
        }
        if (immediate != null) {
            immediate.draw();
        }
    }

    /**
     * Square-tube beam (deliverable A's "4-sided prism" option) spanning the dimension's
     * full vertical range at the waypoint's X/Z, independent of the waypoint's own Y
     * (waypoint-ux.md S6: this is a deliberate simplification the reference implementation
     * also makes - a full-height column is trivial to draw and visible from anywhere at
     * that X/Z). Drawn double-sided (no back-face culling, see the caller) so the tube
     * reads correctly from inside or outside.
     */
    private void drawBeam(
        final MatrixStack matrices,
        final Vec3d cameraPos,
        final double worldX,
        final double worldZ,
        final double bottomY,
        final double topY,
        final int colorArgb,
        final double horizontalDistance,
        final double maxDistance
    ) {
        final float nearFade = (float) MathHelper.clamp(horizontalDistance / BEAM_NEAR_FADE_BLOCKS, 0.0, 1.0);
        final float farFactor = (float) MathHelper.clamp(1.0 - horizontalDistance / maxDistance, 0.0, 1.0);
        final float intensify = BEAM_FAR_FLOOR + (1f - BEAM_FAR_FLOOR) * farFactor;
        final float alpha = BEAM_CORE_ALPHA * nearFade * intensify;
        if (alpha <= 0.01f) {
            return;
        }
        final int color = Argb.pack(Math.round(alpha * 255f), Argb.red(colorArgb), Argb.green(colorArgb), Argb.blue(colorArgb));

        matrices.push();
        matrices.translate(worldX - cameraPos.x, -cameraPos.y, worldZ - cameraPos.z);
        final float h = (float) BEAM_HALF_WIDTH;
        final float bottom = (float) bottomY;
        final float top = (float) topY;
        drawBeamSide(matrices, -h, -h, h, -h, bottom, top, color);
        drawBeamSide(matrices, h, -h, h, h, bottom, top, color);
        drawBeamSide(matrices, h, h, -h, h, bottom, top, color);
        drawBeamSide(matrices, -h, h, -h, -h, bottom, top, color);
        matrices.pop();
    }

    /** One side face of the beam tube, from local (x0,z0) to (x1,z1), spanning bottom..top. */
    private void drawBeamSide(
        final MatrixStack matrices,
        final float x0, final float z0,
        final float x1, final float z1,
        final float bottom, final float top,
        final int color
    ) {
        RenderUtil.fillTriangle3D(matrices, x0, bottom, z0, x1, bottom, z1, x1, top, z1, color);
        RenderUtil.fillTriangle3D(matrices, x0, bottom, z0, x1, top, z1, x0, top, z0, color);
    }

    /**
     * Camera-facing name/distance label. Uses {@link TextRenderer}'s {@code seeThrough}
     * draw parameter (the same one behind vanilla entity nametags fading through walls) to
     * get waypoint-ux.md S6's "two-pass occlusion fade" - a full-opacity depth-tested pass
     * plus a fainter always-visible pass - without hand-rolling the dual draw.
     */
    private void drawLabel(
        final MatrixStack matrices,
        final VertexConsumerProvider.Immediate immediate,
        final Camera camera,
        final Vec3d cameraPos,
        final double worldX,
        final double worldY,
        final double worldZ,
        final WaypointRenderEntry waypoint,
        final double distance3d
    ) {
        final float nearFade = (float) MathHelper.clamp(distance3d / LABEL_NEAR_FADE_BLOCKS, 0.0, 1.0);
        if (nearFade <= 0.01f) {
            return;
        }
        final float scaleMult = 1f + (float) MathHelper.clamp(
            (distance3d - LABEL_SCALE_NEAR_BLOCKS) / LABEL_SCALE_GROWTH_PER_BLOCK, 0.0, LABEL_MAX_SCALE_MULT - 1.0
        );
        final float scale = LABEL_BASE_SCALE * scaleMult;

        final TextRenderer textRenderer = client.textRenderer;
        final String name = displayName(waypoint);
        final String distanceText = new TranslatableText("confluxmap.value.blocks", Math.round(distance3d)).getString();
        final int nameWidth = textRenderer.getWidth(name);
        final int distanceWidth = textRenderer.getWidth(distanceText);
        final int maxWidth = Math.max(nameWidth, distanceWidth);
        final int lineHeight = textRenderer.fontHeight + 1;

        matrices.push();
        matrices.translate(worldX - cameraPos.x, worldY + LABEL_Y_OFFSET - cameraPos.y, worldZ - cameraPos.z);
        matrices.multiply(camera.getRotation());
        matrices.scale(-scale, -scale, scale);

        final int bgAlpha = Math.round(Argb.alpha(LABEL_BACKGROUND_COLOR) * nearFade);
        final int bgColor = (bgAlpha << 24) | (LABEL_BACKGROUND_COLOR & 0x00FFFFFF);
        RenderUtil.fillRect(
            matrices, -maxWidth / 2f - LABEL_PADDING, -lineHeight - LABEL_PADDING,
            maxWidth + LABEL_PADDING * 2f, lineHeight * 2f + LABEL_PADDING * 2f, bgColor
        );

        final Matrix4f model = matrices.peek().getModel();
        final int nameColor = withAlpha(waypoint.colorArgb() | 0xFF000000, nearFade);
        final int distanceColor = withAlpha(LABEL_DISTANCE_COLOR, nearFade);
        textRenderer.draw(name, -nameWidth / 2f, -lineHeight, nameColor, false, model, immediate, true, 0, LABEL_LIGHT);
        textRenderer.draw(distanceText, -distanceWidth / 2f, 1f, distanceColor, false, model, immediate, true, 0, LABEL_LIGHT);
        matrices.pop();
    }

    private static int withAlpha(final int argb, final float alpha) {
        final int a = Math.round(Argb.alpha(argb) * MathHelper.clamp(alpha, 0f, 1f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static String displayName(final WaypointRenderEntry waypoint) {
        if (!waypoint.shared()) {
            return waypoint.name();
        }
        return (waypoint.locked() ? "[L] " : "[S] ") + waypoint.name();
    }
}
