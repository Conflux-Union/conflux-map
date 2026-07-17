package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
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
    /** 50% white overlay used to brighten a hovered marker's fill color (fullscreen map only). */
    private static final int HOVER_TINT = 0x80FFFFFF;

    private WaypointMarkerRenderer() {
    }

    /**
     * In-range marker at a fixed screen position, upright - the caller is responsible for
     * counter-rotating the *position* in rotate mode (see {@code drawCardinals}'s rotate ->
     * translate -> counter-rotate mechanism); this method never applies its own rotation.
     */
    public static void draw(
        final MatrixStack matrices,
        final Waypoint waypoint,
        final float x,
        final float y,
        final float halfSize,
        final float alpha,
        final boolean hovered
    ) {
        final int fill = fillColor(waypoint.colorArgb, alpha, hovered);
        final int outer = withAlpha(OUTER_CONTRAST, alpha);
        final int inner = withAlpha(INNER_RING, alpha);
        if (waypoint.type == Waypoint.Type.DEATH) {
            drawCross(matrices, x, y, halfSize, outer, inner, fill);
        } else {
            drawDiamond(matrices, x, y, halfSize, outer, inner, fill);
        }
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
        final int colorArgb,
        final float alpha
    ) {
        matrices.push();
        matrices.translate(x, y, 0);
        matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(angleDegrees));
        final int outer = withAlpha(OUTER_CONTRAST, alpha);
        final int inner = withAlpha(INNER_RING, alpha);
        final int fill = withAlpha(colorArgb | 0xFF000000, alpha);
        RenderUtil.fillTriangle(matrices, 0f, -6.5f, -5f, 5.5f, 5f, 5.5f, outer);
        RenderUtil.fillTriangle(matrices, 0f, -5.5f, -4.2f, 4.6f, 4.2f, 4.6f, inner);
        RenderUtil.fillTriangle(matrices, 0f, -4.3f, -3f, 3.6f, 3f, 3.6f, fill);
        matrices.pop();
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

    private static int withAlpha(final int argb, final float alpha) {
        final int a = Math.round(Argb.alpha(argb) * MathHelper.clamp(alpha, 0f, 1f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
