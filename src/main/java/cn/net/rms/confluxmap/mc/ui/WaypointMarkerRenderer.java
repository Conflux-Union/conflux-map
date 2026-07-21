package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3f;

/**
 * VoxelMap-style waypoint marker drawing shared by {@code MinimapHudRenderer} and
 * {@code FullscreenMapScreen} (deliverable D) so both surfaces render identical marker
 * shapes - only their size, alpha and hover handling differ per surface. Every waypoint
 * uses the player-selected color as its plate and a white first-name character, keeping
 * the minimap and fullscreen map visually consistent.
 */
public final class WaypointMarkerRenderer {
    private static final int OUTER_CONTRAST = 0xFF101010;
    private static final int WHITE_TEXT = 0xFFFFFFFF;
    private static final int SHARED_OUTLINE = 0xFF55DDE0;
    private static final int LOCKED_OUTLINE = 0xFFFFD166;
    /** 50% white overlay used to brighten a hovered marker's fill color (fullscreen map only). */
    private static final int HOVER_TINT = 0x80FFFFFF;

    private WaypointMarkerRenderer() {
    }

    /**
     * In-range marker at a fixed screen position, upright - the caller is responsible for
     * counter-rotating the *position* in rotate mode (see {@code drawCardinals}'s rotate ->
     * translate -> counter-rotate mechanism); this method never applies its own rotation.
     *
     * <p>The plate scales with {@code halfSize}; the glyph is scaled down when necessary,
     * so different surfaces can choose different sizes without changing the icon style.
     */
    public static void draw(
        final MatrixStack matrices,
        final TextRenderer textRenderer,
        final WaypointRenderEntry waypoint,
        final float x,
        final float y,
        final float halfSize,
        final float alpha,
        final boolean hovered
    ) {
        final int fill = fillColor(waypoint.colorArgb(), alpha, hovered);
        final int outer = withAlpha(outlineColor(waypoint), alpha);
        final float plateSize = halfSize * 2f;
        RenderUtil.fillRect(matrices, x - halfSize - 1f, y - halfSize - 1f, plateSize + 2f, plateSize + 2f, outer);
        RenderUtil.fillRect(matrices, x - halfSize, y - halfSize, plateSize, plateSize, fill);

        final String initial = initial(waypoint.name());
        final int textWidth = textRenderer.getWidth(initial);
        final float available = Math.max(1f, plateSize - 2f);
        final float textScale = Math.min(1f, available / Math.max(textWidth, textRenderer.fontHeight));
        matrices.push();
        matrices.translate(x, y, 0);
        matrices.scale(textScale, textScale, 1f);
        textRenderer.drawWithShadow(
            matrices,
            initial,
            -textWidth / 2f,
            -textRenderer.fontHeight / 2f,
            withAlpha(WHITE_TEXT, alpha)
        );
        matrices.pop();
        drawLockIndicator(matrices, waypoint, x, y, halfSize, alpha);
    }

    /** First code point of the trimmed name; validation normally prevents the fallback. */
    static String initial(final String name) {
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "?";
        }
        final int[] codePoints = trimmed.codePoints().limit(1).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    /**
     * Directional edge-indicator arrow, rotated to the waypoint's on-screen bearing -
     * deliberately never counter-rotated (waypoint-ux.md S7: this is the one marker whose
     * whole purpose is pointing, unlike the upright in-range marker above). Proportioned to
     * match {@code MinimapHudRenderer#drawPlayerArrow}'s existing arrowhead, with the same
     * three-layer outline as {@link #draw}.
     */
    public static void drawEdgeArrow(
        final MatrixStack matrices,
        final float x,
        final float y,
        final float angleDegrees,
        final WaypointRenderEntry waypoint,
        final float alpha
    ) {
        matrices.push();
        matrices.translate(x, y, 0);
        matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(angleDegrees));
        final int outer = withAlpha(outlineColor(waypoint), alpha);
        final int inner = withAlpha(WHITE_TEXT, alpha);
        final int fill = withAlpha(waypoint.colorArgb() | 0xFF000000, alpha);
        RenderUtil.fillTriangle(matrices, 0f, -6.5f, -5f, 5.5f, 5f, 5.5f, outer);
        RenderUtil.fillTriangle(matrices, 0f, -5.5f, -4.2f, 4.6f, 4.2f, 4.6f, inner);
        RenderUtil.fillTriangle(matrices, 0f, -4.3f, -3f, 3.6f, 3f, 3.6f, fill);
        matrices.pop();
        drawLockIndicator(matrices, waypoint, x, y, 4f, alpha);
    }

    private static int fillColor(final int colorArgb, final float alpha, final boolean hovered) {
        final int opaque = colorArgb | 0xFF000000;
        final int base = hovered ? Argb.blendOver(opaque, HOVER_TINT) : opaque;
        return withAlpha(base, alpha);
    }

    private static int outlineColor(final WaypointRenderEntry waypoint) {
        if (!waypoint.shared()) {
            return OUTER_CONTRAST;
        }
        return waypoint.locked() ? LOCKED_OUTLINE : SHARED_OUTLINE;
    }

    /** Tiny upright padlock tag; the colored outline alone identifies an unlocked shared point. */
    private static void drawLockIndicator(
        final MatrixStack matrices,
        final WaypointRenderEntry waypoint,
        final float x,
        final float y,
        final float halfSize,
        final float alpha
    ) {
        if (!waypoint.locked()) {
            return;
        }
        final float left = x + halfSize - 1f;
        final float top = y - halfSize - 2f;
        final int outline = withAlpha(OUTER_CONTRAST, alpha);
        final int lock = withAlpha(LOCKED_OUTLINE, alpha);
        RenderUtil.fillRect(matrices, left, top + 2f, 5f, 4f, outline);
        RenderUtil.fillRect(matrices, left + 1f, top, 3f, 4f, outline);
        RenderUtil.fillRect(matrices, left + 1f, top + 3f, 3f, 2f, lock);
        RenderUtil.fillRect(matrices, left + 2f, top + 1f, 1f, 2f, lock);
    }

    private static int withAlpha(final int argb, final float alpha) {
        final int a = Math.round(Argb.alpha(argb) * MathHelper.clamp(alpha, 0f, 1f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
