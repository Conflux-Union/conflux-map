package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Pure label layout: one camera-facing marker with an interruptible, right-expanding detail
 * panel, placed camera-relative and sized by perspective. Sizing, colors, and reveal timing
 * come from {@link WaypointLabelStyle}/{@link WaypointLabelMetrics}, shared with the
 * item-icon HUD flavor so both read identically. Submission and the billboard rotation are
 * version-specific and arrive through the {@link Sink} {@link LabelPass} provides.
 */
final class WaypointLabelPainter {
    /** The version-specific submissions one label needs; implemented by {@link LabelPass}. */
    interface Sink {
        /** Applies the billboard rotation and the label scale (with the version's scale sign). */
        void billboard(MatrixStack matrices, float scale);

        /** One flat colored quad in label space, after the billboard transform. */
        void rect(MatrixStack matrices, float x, float y, float width, float height, int color);

        /** One see-through text run in label space, after the billboard transform. */
        void text(MatrixStack matrices, String text, float x, float y, int color);
    }

    private final MinecraftClient client;
    private final ConfluxConfig config;

    WaypointLabelPainter(final MinecraftClient client, final ConfluxConfig config) {
        this.client = client;
        this.config = config;
    }

    void drawLabel(
        final MatrixStack matrices,
        final Vec3d cameraPos,
        final double worldX,
        final double worldY,
        final double worldZ,
        final WaypointRenderEntry waypoint,
        final double distance3d,
        final double projectionDistance,
        final float animationProgress,
        final float visibilityAlpha,
        final boolean selected,
        final Sink sink
    ) {
        final float nearFade = (float) MathHelper.clamp(
            distance3d / WaypointLabelStyle.LABEL_NEAR_FADE_BLOCKS, 0.0, 1.0
        );
        if (nearFade <= 0.01f) {
            return;
        }
        final float labelOpacity = WaypointLabelStyle.labelOpacityFactor(config);
        final double anchorX = worldX - cameraPos.x;
        final double anchorY = worldY + WaypointLabelMetrics.LABEL_Y_OFFSET - cameraPos.y;
        final double anchorZ = worldZ - cameraPos.z;
        final double anchorDistance = Math.sqrt(
            anchorX * anchorX + anchorY * anchorY + anchorZ * anchorZ
        );
        final double renderedDistance = WaypointLabelMetrics.projectedLabelDistance(
            anchorDistance, projectionDistance
        );
        final double projectionScale = anchorDistance > 0.001
            ? renderedDistance / anchorDistance
            : 1.0;

        final float easedProgress = WaypointHudMotion.smoothStep(animationProgress);
        // Perspective sizing: apparent size peaks at the legacy constant-screen-size look at or
        // inside the reference distance and shrinks with true 1/d perspective beyond it, floored
        // far away; the aim/select animation zooms the label back to full apparent size. Scaling
        // against the projected distance also keeps pulling a far marker closer (far-plane
        // clamping) from enlarging it.
        final float scaleMult = WaypointLabelMetrics.labelWorldScaleMult(renderedDistance, easedProgress);
        // Applied last so the user factor is a plain multiplier on apparent size at every distance.
        final float scale = WaypointLabelStyle.LABEL_BASE_SCALE
            * scaleMult * config.waypointLabelScalePercent / 100f;

        final TextRenderer textRenderer = client.textRenderer;
        final String name = waypoint.name();
        final String distanceText = Math.round(distance3d) + " m";
        final int nameWidth = textRenderer.getWidth(name);
        final int distanceWidth = textRenderer.getWidth(distanceText);
        final float panelFullWidth = Math.max(nameWidth, distanceWidth)
            + WaypointLabelStyle.LABEL_PANEL_PADDING * 2f;
        final float iconSize = MathHelper.lerp(
            easedProgress,
            WaypointLabelStyle.LABEL_ICON_COLLAPSED_SIZE,
            WaypointLabelStyle.LABEL_ICON_EXPANDED_SIZE
        );
        final float iconHalfSize = iconSize / 2f;
        final float panelX = iconHalfSize + WaypointLabelStyle.LABEL_PANEL_GAP;
        final float panelReveal = MathHelper.clamp(
            easedProgress / WaypointLabelStyle.LABEL_TEXT_REVEAL_START, 0f, 1f
        );
        final float panelWidth = panelFullWidth * panelReveal;

        matrices.push();
        matrices.translate(
            anchorX * projectionScale,
            anchorY * projectionScale,
            anchorZ * projectionScale
        );
        sink.billboard(matrices, scale);

        if (panelWidth > 0.5f) {
            sink.rect(
                matrices, panelX, -WaypointLabelStyle.LABEL_PANEL_HEIGHT / 2f,
                panelWidth, WaypointLabelStyle.LABEL_PANEL_HEIGHT,
                WaypointLabelStyle.withAlpha(
                    WaypointLabelStyle.LABEL_BACKGROUND_COLOR,
                    nearFade * visibilityAlpha * labelOpacity
                )
            );
        }
        drawIcon(
            matrices, textRenderer, waypoint, iconHalfSize,
            nearFade * labelOpacity * visibilityAlpha,
            selected, sink
        );

        final float textReveal = MathHelper.clamp(
            (easedProgress - WaypointLabelStyle.LABEL_TEXT_REVEAL_START)
                / (1f - WaypointLabelStyle.LABEL_TEXT_REVEAL_START),
            0f, 1f
        );
        if (textReveal > 0.01f) {
            final float textX = panelX + WaypointLabelStyle.LABEL_PANEL_PADDING + (1f - textReveal) * 4f;
            final float textAlpha = nearFade * textReveal * visibilityAlpha * labelOpacity;
            sink.text(
                matrices, name, textX, -9f,
                WaypointLabelStyle.withAlpha(WaypointLabelStyle.LABEL_NAME_COLOR, textAlpha)
            );
            sink.text(
                matrices, distanceText, textX, 1f,
                WaypointLabelStyle.withAlpha(WaypointLabelStyle.LABEL_DISTANCE_COLOR, textAlpha)
            );
        }
        matrices.pop();
    }

    private void drawIcon(
        final MatrixStack matrices,
        final TextRenderer textRenderer,
        final WaypointRenderEntry waypoint,
        final float halfSize,
        final float alpha,
        final boolean selected,
        final Sink sink
    ) {
        final float size = halfSize * 2f;
        sink.rect(
            matrices, -halfSize - 1f, -halfSize - 1f, size + 2f, size + 2f,
            WaypointLabelStyle.withAlpha(
                selected ? 0xFFFFE066 : WaypointLabelStyle.outlineColor(waypoint), alpha
            )
        );
        sink.rect(
            matrices, -halfSize, -halfSize, size, size,
            WaypointLabelStyle.withAlpha(waypoint.colorArgb() | 0xFF000000, alpha)
        );

        final String markerText = WaypointMarkerRenderer.markerText(
            waypoint.name(),
            waypoint.markerLabel()
        );
        final int initialWidth = textRenderer.getWidth(markerText);
        final float available = Math.max(1f, size - 3f);
        final float textScale = Math.min(1f, available / Math.max(initialWidth, textRenderer.fontHeight));
        matrices.push();
        matrices.scale(textScale, textScale, 1f);
        sink.text(
            matrices, markerText, -initialWidth / 2f, -textRenderer.fontHeight / 2f,
            WaypointLabelStyle.withAlpha(
                WaypointMarkerRenderer.textColorFor(waypoint.colorArgb()), alpha
            )
        );
        matrices.pop();
    }
}
