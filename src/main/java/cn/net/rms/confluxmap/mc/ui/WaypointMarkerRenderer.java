package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3f;

/**
 * VoxelMap-style waypoint marker drawing shared by {@code MinimapHudRenderer} and
 * {@code FullscreenMapScreen} (deliverable D) so both surfaces render identical marker
 * shapes - only their size, alpha and hover handling differ per surface. A normal
 * waypoint draws as a filled diamond; a death point draws as a skull-convention X/cross
 * instead (waypoint-ux.md S4: death points are distinguished purely by icon/color
 * convention, never a separate rendering path - deliverable C asks for that distinct
 * look on both map surfaces). Both shapes share the same three-layer dark-outer/
 * white-inner/color-fill contrast outline so a marker stays legible over any tile color
 * underneath, mirroring the existing player-arrow's two-layer outline technique
 * ({@code MinimapHudRenderer#drawPlayerArrow}) one layer further.
 */
public final class WaypointMarkerRenderer {
    private static final int OUTER_CONTRAST = 0xFF101010;
    private static final int INNER_RING = 0xFFFFFFFF;
    private static final int SHARED_OUTLINE = 0xFF55DDE0;
    private static final int LOCKED_OUTLINE = 0xFFFFD166;
    /** 50% white overlay used to brighten a hovered marker's fill color (fullscreen map only). */
    private static final int HOVER_TINT = 0x80FFFFFF;

    private WaypointMarkerRenderer() {
    }

    private static final int BADGE_HEIGHT = 11;
    private static final int DARK_TEXT = 0xFF202020;

    /**
     * In-range marker at a fixed screen position, upright - the caller is responsible for
     * counter-rotating the *position* in rotate mode (see {@code drawCardinals}'s rotate ->
     * translate -> counter-rotate mechanism); this method never applies its own rotation.
     *
     * <p>Normal waypoints render as a text badge - the first two characters of the name on
     * a color-filled plate (user feedback: shape markers were too easy to miss); a nameless
     * waypoint falls back to the diamond. Death points keep the skull-convention X.
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
        final int inner = withAlpha(INNER_RING, alpha);
        if (waypoint.type() == Waypoint.Type.DEATH) {
            drawCross(matrices, x, y, halfSize, outer, inner, fill);
            drawLockIndicator(matrices, waypoint, x, y, halfSize, alpha);
            return;
        }
        final String badge = badgeText(waypoint.name());
        if (badge.isEmpty()) {
            drawDiamond(matrices, x, y, halfSize, outer, inner, fill);
            drawLockIndicator(matrices, waypoint, x, y, halfSize, alpha);
            return;
        }
        final int textWidth = textRenderer.getWidth(badge);
        final float plateWidth = textWidth + 4f;
        final float left = x - plateWidth / 2f;
        final float top = y - BADGE_HEIGHT / 2f;
        RenderUtil.fillRect(matrices, left - 1f, top - 1f, plateWidth + 2f, BADGE_HEIGHT + 2f, outer);
        RenderUtil.fillRect(matrices, left, top, plateWidth, BADGE_HEIGHT, fill);
        final int textColor = withAlpha(luminance(waypoint.colorArgb()) > 0.62f ? DARK_TEXT : 0xFFFFFFFF, alpha);
        textRenderer.drawWithShadow(matrices, badge, x - textWidth / 2f, top + 2f, textColor);
        drawLockIndicator(matrices, waypoint, x, y, halfSize, alpha);
    }

    /** First two code points of the trimmed name ("矿洞入口" -> "矿洞", "Base" -> "Ba"). */
    private static String badgeText(final String name) {
        final String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        final int[] codePoints = trimmed.codePoints().limit(2).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    private static float luminance(final int argb) {
        return (0.299f * Argb.red(argb) + 0.587f * Argb.green(argb) + 0.114f * Argb.blue(argb)) / 255f;
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
        final int inner = withAlpha(INNER_RING, alpha);
        final int fill = withAlpha(waypoint.colorArgb() | 0xFF000000, alpha);
        RenderUtil.fillTriangle(matrices, 0f, -6.5f, -5f, 5.5f, 5f, 5.5f, outer);
        RenderUtil.fillTriangle(matrices, 0f, -5.5f, -4.2f, 4.6f, 4.2f, 4.6f, inner);
        RenderUtil.fillTriangle(matrices, 0f, -4.3f, -3f, 3.6f, 3f, 3.6f, fill);
        matrices.pop();
        drawLockIndicator(matrices, waypoint, x, y, 4f, alpha);
    }

    private static void drawDiamond(
        final MatrixStack matrices, final float x, final float y, final float halfSize, final int outer, final int inner, final int fill
    ) {
        drawDiamondLayer(matrices, x, y, halfSize, outer);
        drawDiamondLayer(matrices, x, y, Math.max(0.5f, halfSize - 1.2f), inner);
        drawDiamondLayer(matrices, x, y, Math.max(0.5f, halfSize - 2.2f), fill);
    }

    private static void drawDiamondLayer(final MatrixStack matrices, final float x, final float y, final float h, final int color) {
        RenderUtil.fillTriangle(matrices, x, y - h, x - h, y, x + h, y, color);
        RenderUtil.fillTriangle(matrices, x, y + h, x - h, y, x + h, y, color);
    }

    /** Skull-convention death marker: an X built from two crossed bars (a "+" rotated 45 degrees). */
    private static void drawCross(
        final MatrixStack matrices, final float x, final float y, final float halfSize, final int outer, final int inner, final int fill
    ) {
        drawCrossLayer(matrices, x, y, halfSize + 1.2f, Math.max(1f, halfSize * 0.65f), outer);
        drawCrossLayer(matrices, x, y, halfSize + 0.4f, Math.max(1f, halfSize * 0.5f), inner);
        drawCrossLayer(matrices, x, y, halfSize, Math.max(1f, halfSize * 0.42f), fill);
    }

    private static void drawCrossLayer(
        final MatrixStack matrices, final float x, final float y, final float armHalfLength, final float thickness, final int color
    ) {
        matrices.push();
        matrices.translate(x, y, 0);
        matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(45f));
        RenderUtil.fillRect(matrices, -armHalfLength, -thickness / 2f, armHalfLength * 2f, thickness, color);
        RenderUtil.fillRect(matrices, -thickness / 2f, -armHalfLength, thickness, armHalfLength * 2f, color);
        matrices.pop();
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
