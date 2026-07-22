package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.radar.RadarCategory;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;

/**
 * Draws one already-projected radar marker (entity head icon or shaped-dot fallback,
 * plus an optional player name label), shared by {@code MinimapHudRenderer} and
 * {@code FullscreenMapScreen} so both surfaces render radar entries identically. Extracted
 * verbatim from {@code MinimapHudRenderer}'s original radar-marker drawing - see
 * docs/reference-specs/radar-icons.md secs 2-3 for the VoxelMap-style look this reproduces
 * (elevation dimming, thin silhouette contour, no faction frames). Two deliberate contour
 * deviations from the spec (user request): the outline ring is 1px (one fill pass, not two),
 * and its color flips between black and white by backdrop luminance instead of always black.
 */
public final class RadarMarkerRenderer {
    private static final int PLAYER_COLOR = 0xFFFFFFFF;
    private static final int HOSTILE_COLOR = 0xFFFF4040;
    private static final int PASSIVE_COLOR = 0xFF50E060;
    private static final int OTHER_COLOR = 0xFFA0A0A0;
    private static final int VERTICAL_WINDOW = 32;

    private static final float ICON_HALF_SIZE = 5f;
    private static final float ICON_SIZE = ICON_HALF_SIZE * 2f;
    /** The outline mask pads each icon cell 16px -> 18px, so its quad scales up around the same center. */
    private static final float OUTLINE_HALF_SIZE =
        ICON_HALF_SIZE * EntityIconOutlineTexture.PADDED_CELL_PX / (float) EntityIconOutlineTexture.CELL_PX;
    private static final float OUTLINE_SIZE = OUTLINE_HALF_SIZE * 2f;
    /** Name labels sit just below the icon's contour. */
    private static final float ICON_NAME_OFFSET = 7f;
    private static final int BLACK_CONTOUR = 0xB0000000;
    private static final int WHITE_CONTOUR = 0xB0FFFFFF;
    /** Backdrops at least this bright (Rec. 709 luminance, 0-255) keep the black contour; darker ones flip to white. */
    private static final int CONTOUR_FLIP_LUMINANCE = 100;
    /** No elevation treatment inside this band - nearby mobs always render fully readable. */
    private static final int DEADZONE = 8;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    /** Alpha multiplier for spectator-mode players: shown as translucent ghosts, not hidden. */
    private static final float SPECTATOR_ALPHA = 0.5f;

    private RadarMarkerRenderer() {
    }

    /**
     * Draws the entity head icon (VoxelMap-style: a small sub-UV crop of the entity's own skin/
     * mob texture, ringed in a category color) when available, falling back to the original
     * shaped dot otherwise - either because icons are disabled, the entity isn't currently loaded
     * (only the scan-time snapshot position survived), or its species has no entry in
     * {@link EntityIconManager}'s UV table.
     *
     * <p>Spectator-mode entries render every element (icon, contour, dot, name) at
     * {@link #SPECTATOR_ALPHA} of its normal alpha, on top of any elevation fading.
     *
     * @param backdrop the caller's map-color-under-a-position lookup, for contour contrast
     * @param x screen-space marker center (already projected/rotated by the caller)
     * @param y screen-space marker center (already projected/rotated by the caller)
     * @param blockX the marker's world X (the position {@code x}/{@code y} were projected from)
     * @param blockZ the marker's world Z (the position {@code x}/{@code y} were projected from)
     * @param blocksPerPixel the caller's current zoom, to size the backdrop sampling footprint
     * @param yDelta the entity's current world Y minus the player's, for elevation dimming
     * @param live the live entity if still loaded, or {@code null} to force the dot fallback
     */
    public static void draw(
        final MatrixStack matrices,
        final MinecraftClient client,
        final ConfluxConfig config,
        final EntityIconManager iconManager,
        final RadarBackdrop backdrop,
        final RadarEntry entry,
        final float x,
        final float y,
        final double blockX,
        final double blockZ,
        final float blocksPerPixel,
        final int yDelta,
        final Entity live
    ) {
        final float alphaScale = entry.spectator() ? SPECTATOR_ALPHA : 1f;
        if (config.radarIconsEnabled && live != null) {
            final EntityIconManager.FaceIcon icon = iconManager.iconFor(live);
            if (icon != null) {
                final int contourBase = contourBase(backdrop, blockX, blockZ, blocksPerPixel);
                drawIcon(matrices, client, iconManager, icon, x, y, yDelta, alphaScale, contourBase);
                if (config.radarShowPlayerNames && entry.category() == RadarCategory.PLAYER && entry.name() != null) {
                    drawCenteredLine(client, matrices, entry.name(), x, y + ICON_NAME_OFFSET, alphaScale);
                }
                return;
            }
        }
        final int color = Argb.scaleAlpha(elevationColor(baseColor(entry.category()), yDelta), alphaScale);
        switch (entry.category()) {
            case PLAYER:
                RenderUtil.fillRect(matrices, x - 2f, y - 2f, 4f, 4f, color);
                if (config.radarShowPlayerNames && entry.name() != null) {
                    drawCenteredLine(client, matrices, entry.name(), x, y + 3f, alphaScale);
                }
                break;
            case HOSTILE:
                RenderUtil.fillTriangle(matrices, x, y - 3.5f, x - 3f, y + 2.5f, x + 3f, y + 2.5f, color);
                break;
            case PASSIVE:
                RenderUtil.drawRing(matrices, x, y, 2.5f, 2.5f, color);
                break;
            default:
                RenderUtil.fillRect(matrices, x - 1.5f, y - 1.5f, 3f, 3f, color);
                break;
        }
    }

    /**
     * Averages the backdrop luminance over the icon's footprint (center + the 4 edge midpoints)
     * and picks the contour color with contrast against it - a single-block probe would flicker
     * as the icon slides across mixed terrain. Base contour only; elevation/spectator treatment
     * is applied at draw time like every other marker element.
     */
    private static int contourBase(final RadarBackdrop backdrop, final double blockX, final double blockZ, final float blocksPerPixel) {
        final float reach = (ICON_HALF_SIZE + 1f) * blocksPerPixel;
        final int luminance = (Argb.luminance(backdrop.argbAt(blockX, blockZ))
            + Argb.luminance(backdrop.argbAt(blockX - reach, blockZ))
            + Argb.luminance(backdrop.argbAt(blockX + reach, blockZ))
            + Argb.luminance(backdrop.argbAt(blockX, blockZ - reach))
            + Argb.luminance(backdrop.argbAt(blockX, blockZ + reach))) / 5;
        return luminance >= CONTOUR_FLIP_LUMINANCE ? BLACK_CONTOUR : WHITE_CONTOUR;
    }

    /**
     * Thin contour hugging the icon's silhouette (the spec's outline pass, no faction frames),
     * then the icon quad tinted by the same elevation alpha/dim as the dot markers (base color
     * white, so the entity's real texture colors show through untouched). The contour gets the
     * same elevation treatment, matching how the reference spec applies the same alpha/dim
     * multiply to its headgear overlay layer. Sheet icons draw their baked outline mask; player
     * faces (fully opaque squares - the square frame IS their silhouette outline) and a
     * failed-to-bake mask fall back to the plain 1px square frame.
     */
    private static void drawIcon(
        final MatrixStack matrices,
        final MinecraftClient client,
        final EntityIconManager iconManager,
        final EntityIconManager.FaceIcon icon,
        final float x,
        final float y,
        final int yDelta,
        final float alphaScale,
        final int contourBase
    ) {
        final int contour = Argb.scaleAlpha(elevationColor(contourBase, yDelta), alphaScale);
        final int outlineGlId = icon.fromSheet() ? iconManager.outlineTextureGlId(client) : -1;
        if (outlineGlId != -1) {
            // The padded outline grid mirrors the sheet grid, so the sheet UV rect addresses
            // the matching mask cell; only the quad grows by the padding ratio.
            RenderUtil.bindTexture(outlineGlId);
            RenderUtil.drawTintedQuad(
                matrices, x - OUTLINE_HALF_SIZE, y - OUTLINE_HALF_SIZE, OUTLINE_SIZE, OUTLINE_SIZE,
                icon.u0(), icon.v0(), icon.u1(), icon.v1(), contour
            );
        } else {
            final float left = x - ICON_HALF_SIZE - 1f;
            final float top = y - ICON_HALF_SIZE - 1f;
            final float edge = ICON_SIZE + 2f;
            RenderUtil.fillRect(matrices, left, top, edge, 1f, contour);
            RenderUtil.fillRect(matrices, left, top + edge - 1f, edge, 1f, contour);
            RenderUtil.fillRect(matrices, left, top + 1f, 1f, edge - 2f, contour);
            RenderUtil.fillRect(matrices, left + edge - 1f, top + 1f, 1f, edge - 2f, contour);
        }
        final int tint = Argb.scaleAlpha(elevationColor(0xFFFFFFFF, yDelta), alphaScale);
        RenderUtil.bindTexture(client, icon.texture());
        RenderUtil.drawTintedQuad(
            matrices, x - ICON_HALF_SIZE, y - ICON_HALF_SIZE, ICON_SIZE, ICON_SIZE,
            icon.u0(), icon.v0(), icon.u1(), icon.v1(), tint
        );
        if (icon.hasOverlay()) {
            RenderUtil.bindTexture(client, icon.overlayTexture());
            RenderUtil.drawTintedQuad(
                matrices, x - ICON_HALF_SIZE, y - ICON_HALF_SIZE, ICON_SIZE, ICON_SIZE,
                icon.ou0(), icon.ov0(), icon.ou1(), icon.ov1(), tint
            );
        }
    }

    private static int baseColor(final RadarCategory category) {
        switch (category) {
            case PLAYER:
                return PLAYER_COLOR;
            case HOSTILE:
                return HOSTILE_COLOR;
            case PASSIVE:
                return PASSIVE_COLOR;
            default:
                return OTHER_COLOR;
        }
    }

    /**
     * Above/below indication (radar-icons.md sec 3), simplified to a fixed 32-block window
     * (no zoom scaling, no doubled window for phantoms) rather than the spec's Full-mode icon
     * treatment: a "closeness" value is 1 right at the player's own height and falls off
     * (linearly, then squared) to 0 at the window edge. An entity above the player fades toward
     * transparent as closeness drops; an entity at or below stays opaque but its color dims
     * toward black as closeness drops, floored so it never fully vanishes.
     */
    private static int elevationColor(final int base, final int yDelta) {
        final int beyond = Math.max(0, Math.abs(yDelta) - DEADZONE);
        final float closeness = 1f - Math.min(beyond / (float) (VERTICAL_WINDOW - DEADZONE), 1f);
        if (yDelta > 0) {
            final int alpha = Math.round(Math.max(closeness, 0.35f) * 255f);
            return (base & 0x00FFFFFF) | (alpha << 24);
        }
        final float brightness = Math.max(closeness, 0.5f);
        return Argb.scale(base, brightness);
    }

    private static void drawCenteredLine(
        final MinecraftClient client,
        final MatrixStack matrices,
        final String text,
        final float centerX,
        final float y,
        final float alphaScale
    ) {
        final int width = client.textRenderer.getWidth(text);
        client.textRenderer.drawWithShadow(matrices, text, centerX - width / 2f, y, Argb.scaleAlpha(TEXT_COLOR, alphaScale));
    }
}
