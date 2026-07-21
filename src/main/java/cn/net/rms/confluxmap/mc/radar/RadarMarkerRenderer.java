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
 * docs/reference-specs/radar-icons.md sec 3 for the VoxelMap-style look this reproduces
 * (elevation dimming, thin dark contour, no faction frames).
 */
public final class RadarMarkerRenderer {
    private static final int PLAYER_COLOR = 0xFFFFFFFF;
    private static final int HOSTILE_COLOR = 0xFFFF4040;
    private static final int PASSIVE_COLOR = 0xFF50E060;
    private static final int OTHER_COLOR = 0xFFA0A0A0;
    private static final int VERTICAL_WINDOW = 32;

    private static final float ICON_HALF_SIZE = 5f;
    private static final float ICON_SIZE = ICON_HALF_SIZE * 2f;
    /** Name labels sit just below the icon's 1px square border. */
    private static final float ICON_NAME_OFFSET = 7f;
    private static final int ICON_CONTOUR = 0xB0000000;
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
     * @param x screen-space marker center (already projected/rotated by the caller)
     * @param y screen-space marker center (already projected/rotated by the caller)
     * @param yDelta the entity's current world Y minus the player's, for elevation dimming
     * @param live the live entity if still loaded, or {@code null} to force the dot fallback
     */
    public static void draw(
        final MatrixStack matrices,
        final MinecraftClient client,
        final ConfluxConfig config,
        final EntityIconManager iconManager,
        final RadarEntry entry,
        final float x,
        final float y,
        final int yDelta,
        final Entity live
    ) {
        final float alphaScale = entry.spectator() ? SPECTATOR_ALPHA : 1f;
        if (config.radarIconsEnabled && live != null) {
            final EntityIconManager.FaceIcon icon = iconManager.iconFor(live);
            if (icon != null) {
                drawIcon(matrices, client, icon, x, y, yDelta, alphaScale);
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
     * Icon quad tinted by the same elevation alpha/dim as the dot markers (base color white, so
     * the entity's real texture colors show through untouched), then a category-colored ring
     * drawn just outside it - the ring also gets the elevation treatment, matching how the
     * reference spec applies the same alpha/dim multiply to its headgear overlay layer.
     */
    private static void drawIcon(
        final MatrixStack matrices,
        final MinecraftClient client,
        final EntityIconManager.FaceIcon icon,
        final float x,
        final float y,
        final int yDelta,
        final float alphaScale
    ) {
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
        // VoxelMap-style presentation: a clean face icon with only a thin dark contour
        // for contrast against the terrain - no faction frames (user feedback: match
        // VoxelMap; a night full of hostiles turned colored frames into visual noise).
        final int borderColor = Argb.scaleAlpha(elevationColor(ICON_CONTOUR, yDelta), alphaScale);
        final float left = x - ICON_HALF_SIZE - 1f;
        final float top = y - ICON_HALF_SIZE - 1f;
        final float edge = ICON_SIZE + 2f;
        RenderUtil.fillRect(matrices, left, top, edge, 1f, borderColor);
        RenderUtil.fillRect(matrices, left, top + edge - 1f, edge, 1f, borderColor);
        RenderUtil.fillRect(matrices, left, top + 1f, 1f, edge - 2f, borderColor);
        RenderUtil.fillRect(matrices, left + edge - 1f, top + 1f, 1f, edge - 2f, borderColor);
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
