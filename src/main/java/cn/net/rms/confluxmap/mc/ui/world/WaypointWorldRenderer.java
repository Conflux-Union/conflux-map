package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderCatalog;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
//#if MC>=12111
//$$ import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
//#else
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Gives waypoints an in-world presence (user feedback driving this slice: "waypoints
 * have NO in-world presence"): a vertical translucent beam at each visible waypoint's
 * column, and a camera-facing name/distance label floating above it.
 *
 * <p>The beam runs at the last terrain-adjacent phase offered by the active Fabric rendering
 * API, so it participates in world occlusion. The HUD marker runs at the final main-world phase
 * and draws with depth testing disabled so world content cannot hide player-facing navigation
 * information. In fabric-rendering-v1 1.10.1 (bundled with the
 * installed fabric-api 0.46.1+1.17), {@link WorldRenderContext#consumers()} is {@code
 * null} at this phase and its matrix stack carries no pre-existing camera translation -
 * both are handled here: the beam is drawn with plain {@code Tessellator}/{@code
 * BufferBuilder} calls ({@link RenderUtil#fillTriangle3D}) instead of a vertex consumer,
 * and every position is explicitly translated by {@code worldPos - camera.getPos()}
 * before drawing. The label's text/background instead opens its own {@link
 * VertexConsumerProvider.Immediate} (the same one vanilla uses for entity nametags,
 * {@code client.getBufferBuilders().getEntityVertexConsumers()}) which works at any
 * phase and is flushed once at the end of this render pass.
 *
 * <p>Both the beam and the HUD label are proximity-gated: they only render for waypoints
 * within the player's current view distance, so far-away waypoints have no in-world
 * presence until the player travels toward them. {@code config.waypointRenderDistance}
 * (when non-zero) can only tighten that limit, never extend it.
 */
public final class WaypointWorldRenderer {
    private static final double BEAM_HALF_WIDTH = 0.18;
    private static final float BEAM_CORE_ALPHA = 0.55f;
    /** Same near-camera fade-in constant as the label (waypoint-ux.md S6), applied to horizontal distance from the beam column. */
    private static final double BEAM_NEAR_FADE_BLOCKS = 5.0;
    /** Beam alpha never drops below this fraction of {@link #BEAM_CORE_ALPHA} even at the render-distance edge - "intensifies as you approach" without vanishing far away. */
    private static final float BEAM_FAR_FLOOR = 0.30f;

    private static final double LABEL_Y_OFFSET = 1.5;
    /** waypoint-ux.md S6 "distance fade-in": alpha ramps 0 -> 1 over the nearest ~5 blocks so the label doesn't pop in right next to the camera. */
    private static final double LABEL_NEAR_FADE_BLOCKS = 5.0;
    private static final float LABEL_BASE_SCALE = 0.06f;
    private static final double LABEL_REFERENCE_DISTANCE = 12.0;
    private static final float LABEL_MIN_SCALE_MULT = 0.35f;
    private static final float LABEL_MAX_SCALE_MULT = 170.0f;
    private static final float LABEL_ICON_COLLAPSED_SIZE = 12.0f;
    private static final float LABEL_ICON_EXPANDED_SIZE = 18.0f;
    private static final float LABEL_PANEL_HEIGHT = 20.0f;
    private static final float LABEL_PANEL_PADDING = 3.0f;
    private static final float LABEL_PANEL_GAP = 1.0f;
    private static final float LABEL_TEXT_REVEAL_START = 0.72f;
    private static final int LABEL_BACKGROUND_COLOR = 0xC0101010;
    private static final int LABEL_LOCAL_OUTLINE_COLOR = 0xFF101010;
    private static final int LABEL_SHARED_OUTLINE_COLOR = 0xFF55DDE0;
    private static final int LABEL_LOCKED_OUTLINE_COLOR = 0xFFFFD166;
    private static final int LABEL_NAME_COLOR = 0xFFFFFFFF;
    private static final int LABEL_DISTANCE_COLOR = 0xFFC8C8C8;
    /** LightmapTextureManager.pack(15, 15) - always fully lit, like other UI-ish world markers. */
    private static final int LABEL_LIGHT = 0xF000F0;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final WaypointRenderCatalog waypointRenderCatalog;
    private final Map<UUID, Float> labelAnimationProgress = new HashMap<>();
    private long lastAnimationNanos;

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
        //#if MC>=12111
        //$$ WorldRenderEvents.BEFORE_TRANSLUCENT.register(this::renderBeams);
        //$$ WorldRenderEvents.END_MAIN.register(this::renderHud);
        //#else
        WorldRenderEvents.AFTER_TRANSLUCENT.register(this::renderBeams);
        WorldRenderEvents.LAST.register(this::renderHud);
        //#endif
    }

    private void renderBeams(final WorldRenderContext context) {
        if (!config.waypointBeamsEnabled) {
            return;
        }
        if (!gameBridge.session().active()) {
            return;
        }
        final Optional<PlayerView> playerViewOpt = gameBridge.player(tickDelta(context));
        if (playerViewOpt.isEmpty()) {
            return;
        }
        final PlayerView player = playerViewOpt.get();
        final DimensionId currentDimension = gameBridge.session().dimension();
        //#if MC>=12111
        //$$ final Camera camera = client.gameRenderer.getCamera();
        //$$ final Vec3d cameraPos = camera.getCameraPos();
        //$$ final MatrixStack matrices = context.matrices();
        //#else
        final Camera camera = context.camera();
        final Vec3d cameraPos = camera.getPos();
        final MatrixStack matrices = context.matrixStack();
        //#endif
        final double maxDistance = maxVisibleDistance();
        final double bottomY = 0.0;
        final double topY = currentDimension.equals(DimensionId.NETHER) ? 128.0 : 256.0;
        final List<WaypointRenderEntry> waypoints = waypointRenderCatalog.snapshot(currentDimension);

        //#if MC<12105
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        //#endif
        RenderUtil.beginAdditiveTriangles();

        for (final WaypointRenderEntry waypoint : waypoints) {
            final double worldX = waypoint.x();
            final double worldZ = waypoint.z();
            final double dx = worldX - player.x();
            final double dy = waypoint.y() - player.y();
            final double dz = worldZ - player.z();
            final double distance3d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance3d > maxDistance) {
                continue;
            }
            final double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            drawBeam(
                matrices, cameraPos, worldX, worldZ, bottomY, topY,
                waypoint.colorArgb(), horizontalDistance, maxDistance
            );
        }

        RenderUtil.restoreDefaultBlend();
        //#if MC<12105
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        //#endif
    }

    private void renderHud(final WorldRenderContext context) {
        if (!config.waypointLabelsEnabled) {
            labelAnimationProgress.clear();
            return;
        }
        if (!gameBridge.session().active()) {
            return;
        }
        final Optional<PlayerView> playerViewOpt = gameBridge.player(tickDelta(context));
        if (playerViewOpt.isEmpty()) {
            return;
        }
        final PlayerView player = playerViewOpt.get();
        final DimensionId currentDimension = gameBridge.session().dimension();
        //#if MC>=12111
        //$$ final Camera camera = client.gameRenderer.getCamera();
        //$$ final Vec3d cameraPos = camera.getCameraPos();
        //$$ final MatrixStack matrices = context.matrices();
        //#else
        final Camera camera = context.camera();
        final Vec3d cameraPos = camera.getPos();
        final MatrixStack matrices = context.matrixStack();
        //#endif
        final double maxDistance = maxVisibleDistance();
        final List<WaypointRenderEntry> waypoints = waypointRenderCatalog.snapshot(currentDimension);
        final WaypointRenderEntry targetedWaypoint = targetedWaypoint(waypoints, camera, cameraPos, maxDistance);
        final float animationDeltaSeconds = animationDeltaSeconds();
        final Set<UUID> visibleWaypointIds = new HashSet<>();

        for (final WaypointRenderEntry waypoint : waypoints) {
            final double dx = waypoint.x() - player.x();
            final double dy = waypoint.y() - player.y();
            final double dz = waypoint.z() - player.z();
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= maxDistance) {
                visibleWaypointIds.add(waypoint.id());
            }
        }

        if (!visibleWaypointIds.isEmpty()) {
            // Modern LAST keeps the camera view rotation in ModelView while its context stack is
            // local identity. Legacy LAST needs that stale global transform cleared instead.
            RenderUtil.pushWorldHudModelView();
            try {
                //#if MC<12105
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);
                //#endif
                final VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
                for (final WaypointRenderEntry waypoint : waypoints) {
                    if (!visibleWaypointIds.contains(waypoint.id())) {
                        continue;
                    }
                    final double dx = waypoint.x() - player.x();
                    final double dy = waypoint.y() - player.y();
                    final double dz = waypoint.z() - player.z();
                    final double distance3d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    final boolean targeted = targetedWaypoint != null && targetedWaypoint.id().equals(waypoint.id());
                    final float progress = updateLabelAnimation(waypoint.id(), targeted, animationDeltaSeconds);
                    drawLabel(
                        matrices, immediate, camera, cameraPos, waypoint.x(), waypoint.y(), waypoint.z(),
                        waypoint, distance3d, progress
                    );
                }
                immediate.draw();
            } finally {
                //#if MC<12105
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
                //#endif
                RenderUtil.popModelView();
            }
        }

        labelAnimationProgress.keySet().retainAll(visibleWaypointIds);
    }

    /**
     * Beams and labels only appear once the player is near the waypoint, where "near"
     * means within the current view distance (chunks converted to blocks). A non-zero
     * {@code config.waypointRenderDistance} can tighten the limit but never extend it
     * past what the player can actually see.
     */
    private double maxVisibleDistance() {
        final double viewDistanceBlocks = MinecraftAccess.viewDistance(client) * 16.0;
        return config.waypointRenderDistance > 0
            ? Math.min(config.waypointRenderDistance, viewDistanceBlocks)
            : viewDistanceBlocks;
    }

    private WaypointRenderEntry targetedWaypoint(
        final List<WaypointRenderEntry> waypoints,
        final Camera camera,
        final Vec3d cameraPos,
        final double maxDistance
    ) {
        WaypointRenderEntry best = null;
        double bestAlignment = -1.0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (final WaypointRenderEntry waypoint : waypoints) {
            final double dx = waypoint.x() - cameraPos.x;
            final double dy = waypoint.y() + LABEL_Y_OFFSET - cameraPos.y;
            final double dz = waypoint.z() - cameraPos.z;
            final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= 0.001 || distance > maxDistance) {
                continue;
            }
            final double alignment = WaypointHudMotion.alignment(
                camera.getYaw(), camera.getPitch(), dx, dy, dz
            );
            if (!WaypointHudMotion.insideTargetCone(alignment, distance)) {
                continue;
            }
            if (alignment > bestAlignment || (alignment == bestAlignment && distance < bestDistance)) {
                best = waypoint;
                bestAlignment = alignment;
                bestDistance = distance;
            }
        }
        return best;
    }

    private float animationDeltaSeconds() {
        final long now = System.nanoTime();
        if (lastAnimationNanos == 0L) {
            lastAnimationNanos = now;
            return 0.0f;
        }
        final float delta = MathHelper.clamp((now - lastAnimationNanos) / 1_000_000_000.0f, 0.0f, 0.1f);
        lastAnimationNanos = now;
        return delta;
    }

    private float updateLabelAnimation(final UUID waypointId, final boolean targeted, final float deltaSeconds) {
        final float current = labelAnimationProgress.getOrDefault(waypointId, 0.0f);
        final float next = WaypointHudMotion.advance(current, targeted, deltaSeconds);
        if (next <= 0.0f && !targeted) {
            labelAnimationProgress.remove(waypointId);
        } else {
            labelAnimationProgress.put(waypointId, next);
        }
        return next;
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

    /** Camera-facing marker with an interruptible, right-expanding detail panel. */
    private void drawLabel(
        final MatrixStack matrices,
        final VertexConsumerProvider.Immediate immediate,
        final Camera camera,
        final Vec3d cameraPos,
        final double worldX,
        final double worldY,
        final double worldZ,
        final WaypointRenderEntry waypoint,
        final double distance3d,
        final float animationProgress
    ) {
        final float nearFade = (float) MathHelper.clamp(distance3d / LABEL_NEAR_FADE_BLOCKS, 0.0, 1.0);
        if (nearFade <= 0.01f) {
            return;
        }
        // Scale proportionally with distance so the marker keeps a useful apparent size on screen.
        final float scaleMult = (float) MathHelper.clamp(
            distance3d / LABEL_REFERENCE_DISTANCE, LABEL_MIN_SCALE_MULT, LABEL_MAX_SCALE_MULT
        );
        final float scale = LABEL_BASE_SCALE * scaleMult;
        final float easedProgress = WaypointHudMotion.smoothStep(animationProgress);

        final TextRenderer textRenderer = client.textRenderer;
        final String name = waypoint.name();
        final String distanceText = Math.round(distance3d) + " m";
        final int nameWidth = textRenderer.getWidth(name);
        final int distanceWidth = textRenderer.getWidth(distanceText);
        final float panelFullWidth = Math.max(nameWidth, distanceWidth) + LABEL_PANEL_PADDING * 2f;
        final float iconSize = MathHelper.lerp(
            easedProgress, LABEL_ICON_COLLAPSED_SIZE, LABEL_ICON_EXPANDED_SIZE
        );
        final float iconHalfSize = iconSize / 2f;
        final float panelX = iconHalfSize + LABEL_PANEL_GAP;
        final float panelReveal = MathHelper.clamp(easedProgress / LABEL_TEXT_REVEAL_START, 0f, 1f);
        final float panelWidth = panelFullWidth * panelReveal;

        matrices.push();
        matrices.translate(worldX - cameraPos.x, worldY + LABEL_Y_OFFSET - cameraPos.y, worldZ - cameraPos.z);
        matrices.multiply(camera.getRotation());
        //#if MC>=12100
        //$$ matrices.scale(scale, -scale, scale);
        //#else
        matrices.scale(-scale, -scale, scale);
        //#endif

        if (panelWidth > 0.5f) {
            RenderUtil.fillRect(
                matrices, panelX, -LABEL_PANEL_HEIGHT / 2f,
                panelWidth, LABEL_PANEL_HEIGHT, withAlpha(LABEL_BACKGROUND_COLOR, nearFade)
            );
        }
        drawIcon(matrices, textRenderer, immediate, waypoint, iconHalfSize, nearFade);

        final float textReveal = MathHelper.clamp(
            (easedProgress - LABEL_TEXT_REVEAL_START) / (1f - LABEL_TEXT_REVEAL_START), 0f, 1f
        );
        if (textReveal > 0.01f) {
            final float textX = panelX + LABEL_PANEL_PADDING + (1f - textReveal) * 4f;
            final float textAlpha = nearFade * textReveal;
            RenderUtil.drawSeeThroughText(
                textRenderer, name, textX, -9f, withAlpha(LABEL_NAME_COLOR, textAlpha),
                matrices, immediate, LABEL_LIGHT
            );
            RenderUtil.drawSeeThroughText(
                textRenderer, distanceText, textX, 1f, withAlpha(LABEL_DISTANCE_COLOR, textAlpha),
                matrices, immediate, LABEL_LIGHT
            );
        }
        matrices.pop();
    }

    private static void drawIcon(
        final MatrixStack matrices,
        final TextRenderer textRenderer,
        final VertexConsumerProvider.Immediate immediate,
        final WaypointRenderEntry waypoint,
        final float halfSize,
        final float alpha
    ) {
        final float size = halfSize * 2f;
        RenderUtil.fillRect(
            matrices, -halfSize - 1f, -halfSize - 1f, size + 2f, size + 2f,
            withAlpha(outlineColor(waypoint), alpha)
        );
        RenderUtil.fillRect(
            matrices, -halfSize, -halfSize, size, size,
            withAlpha(waypoint.colorArgb() | 0xFF000000, alpha)
        );

        final String initial = initial(waypoint.name());
        final int initialWidth = textRenderer.getWidth(initial);
        final float available = Math.max(1f, size - 3f);
        final float textScale = Math.min(1f, available / Math.max(initialWidth, textRenderer.fontHeight));
        matrices.push();
        matrices.scale(textScale, textScale, 1f);
        RenderUtil.drawSeeThroughText(
            textRenderer, initial, -initialWidth / 2f, -textRenderer.fontHeight / 2f,
            withAlpha(LABEL_NAME_COLOR, alpha), matrices, immediate, LABEL_LIGHT
        );
        matrices.pop();
    }

    private static int withAlpha(final int argb, final float alpha) {
        final int a = Math.round(Argb.alpha(argb) * MathHelper.clamp(alpha, 0f, 1f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static int outlineColor(final WaypointRenderEntry waypoint) {
        if (!waypoint.shared()) {
            return LABEL_LOCAL_OUTLINE_COLOR;
        }
        return waypoint.locked() ? LABEL_LOCKED_OUTLINE_COLOR : LABEL_SHARED_OUTLINE_COLOR;
    }

    private static String initial(final String name) {
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "?";
        }
        final int[] codePoints = trimmed.codePoints().limit(1).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    private static float tickDelta(final WorldRenderContext context) {
        //#if MC>=12111
        //$$ return MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false);
        //#elseif MC>=12100
        //$$ return context.tickCounter().getTickDelta(false);
        //#else
        return context.tickDelta();
        //#endif
    }
}
