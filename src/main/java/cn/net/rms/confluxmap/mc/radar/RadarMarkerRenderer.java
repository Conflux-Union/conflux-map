package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.radar.RadarCategory;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.radar.RadarMarkerClusterer;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FlyingItemEntity;
import net.minecraft.entity.ItemEntity;
//#if MC<12100
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.Items;
//#endif
import net.minecraft.item.ItemStack;

/**
 * Draws already-projected radar markers (entity head/item icon or shaped-dot fallback,
 * plus optional player name labels and overlap counts), shared by {@code MinimapHudRenderer} and
 * {@code FullscreenMapScreen} so both surfaces render radar entries identically. Its visual rules
 * evolved from {@code MinimapHudRenderer}'s original radar-marker drawing - see
 * docs/reference-specs/radar-icons.md secs 2-3 for the VoxelMap-style look this reproduces. All
 * portrait and item icons intentionally render without a generated border.
 */
public final class RadarMarkerRenderer {
    private static final int PLAYER_COLOR = 0xFFFFFFFF;
    private static final int HOSTILE_COLOR = 0xFFFF4040;
    private static final int PASSIVE_COLOR = 0xFF50E060;
    private static final int OTHER_COLOR = 0xFFA0A0A0;
    private static final int VERTICAL_WINDOW = 32;

    private static final int STACK_BADGE_BACKGROUND = 0xD0000000;
    private static final int STACK_BADGE_TEXT = 0xFFFFFFFF;
    /** No elevation treatment inside this band - nearby mobs always render fully readable. */
    private static final int DEADZONE = 8;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    /** Alpha multiplier for spectator-mode players: shown as translucent ghosts, not hidden. */
    private static final float SPECTATOR_ALPHA = 0.5f;

    private RadarMarkerRenderer() {
    }

    /** Complete per-frame position and live state for one projected radar marker. */
    public record Marker(
        RadarEntry entry,
        float x,
        float y,
        int yDelta,
        Entity live
    ) {
    }

    /**
     * Clusters overlapping non-player icons, then draws an entity head icon or the entity's normal
     * in-game item icon when available, falling back to the original shaped dot otherwise. Dropped
     * and flying items use their live stack; other targets use the same item form as creative
     * pick-block where vanilla exposes one.
     *
     * <p>Spectator-mode entries render every element (icon, dot, name) at
     * {@link #SPECTATOR_ALPHA} of its normal alpha, on top of any elevation fading.
     *
     * @param markers visible markers with screen-space positions already projected by the caller
     */
    public static void drawAll(
        final GuiDraw draw,
        final MinecraftClient client,
        final ConfluxConfig config,
        final EntityIconManager iconManager,
        final List<Marker> markers
    ) {
        if (markers.isEmpty()) {
            return;
        }
        if (!config.radarIconsEnabled || markers.size() == 1) {
            for (final Marker marker : markers) {
                drawMarker(draw, client, config, iconManager, marker);
            }
            return;
        }

        final List<RadarMarkerClusterer.Candidate> candidates = new ArrayList<>(markers.size());
        for (int i = 0; i < markers.size(); i++) {
            final Marker marker = markers.get(i);
            candidates.add(new RadarMarkerClusterer.Candidate(
                i, marker.x(), marker.y(), marker.entry().category(),
                marker.entry().entityType(), marker.entry().entityId()
            ));
        }
        for (final RadarMarkerClusterer.Cluster cluster
            : RadarMarkerClusterer.cluster(candidates, config.radarIconSize)) {
            final Marker marker = markers.get(cluster.representativeIndex());
            final boolean iconDrawn = drawMarker(
                draw, client, config, iconManager, marker
            );
            if (cluster.count() > 1) {
                drawStackCount(
                    draw, client, marker.x(), marker.y(),
                    iconDrawn ? config.radarIconSize / 2f : 2.5f,
                    config.radarIconSize, cluster.count(), marker.entry().spectator()
                );
            }
        }
    }

    private static boolean drawMarker(
        final GuiDraw draw,
        final MinecraftClient client,
        final ConfluxConfig config,
        final EntityIconManager iconManager,
        final Marker marker
    ) {
        final RadarEntry entry = marker.entry();
        final float x = marker.x();
        final float y = marker.y();
        final int yDelta = marker.yDelta();
        final Entity live = marker.live();
        final MatrixStack matrices = draw.matrices();
        final float alphaScale = entry.spectator() ? SPECTATOR_ALPHA : 1f;
        if (config.radarIconsEnabled && live != null) {
            final ItemStack itemIcon = entry.category() == RadarCategory.OTHER
                ? itemIconFor(live)
                : ItemStack.EMPTY;
            if (!itemIcon.isEmpty()) {
                draw.drawItemIcon(client, itemIcon, x, y, config.radarIconSize);
                return true;
            }
            final EntityIconManager.FaceIcon icon = iconManager.iconFor(live);
            if (icon != null && drawIcon(
                    matrices, client, iconManager, icon, x, y, config.radarIconSize,
                    yDelta, alphaScale
                )) {
                if (config.radarShowPlayerNames && entry.category() == RadarCategory.PLAYER && entry.name() != null) {
                    drawCenteredLine(
                        client, draw, entry.name(), x, y + config.radarIconSize / 2f + 2f, alphaScale
                    );
                }
                return true;
            }
        }
        final int color = Argb.scaleAlpha(elevationColor(baseColor(entry.category()), yDelta), alphaScale);
        switch (entry.category()) {
            case PLAYER:
                RenderUtil.fillRect(matrices, x - 2f, y - 2f, 4f, 4f, color);
                if (config.radarShowPlayerNames && entry.name() != null) {
                    drawCenteredLine(client, draw, entry.name(), x, y + 3f, alphaScale);
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
        return false;
    }

    private static ItemStack itemIconFor(final Entity entity) {
        if (entity instanceof ItemEntity) {
            return ((ItemEntity) entity).getStack();
        }
        if (entity instanceof FlyingItemEntity) {
            return ((FlyingItemEntity) entity).getStack();
        }
        final ItemStack picked = entity.getPickBlockStack();
        if (picked != null && !picked.isEmpty()) {
            return picked;
        }
        //#if MC<12100
        // Vanilla 1.17.1 implements pick-block for boats but not minecarts. Later versions expose
        // the correct stack directly from every minecart subclass.
        if (entity instanceof AbstractMinecartEntity) {
            switch (((AbstractMinecartEntity) entity).getMinecartType()) {
                case RIDEABLE:
                    return new ItemStack(Items.MINECART);
                case CHEST:
                    return new ItemStack(Items.CHEST_MINECART);
                case FURNACE:
                    return new ItemStack(Items.FURNACE_MINECART);
                case TNT:
                    return new ItemStack(Items.TNT_MINECART);
                case HOPPER:
                    return new ItemStack(Items.HOPPER_MINECART);
                case COMMAND_BLOCK:
                    return new ItemStack(Items.COMMAND_BLOCK_MINECART);
                default:
                    break;
            }
        }
        //#endif
        return ItemStack.EMPTY;
    }

    /**
     * Draws the unframed portrait with the same elevation and spectator alpha as dot markers.
     *
     * @return false when the portrait could not be bound, so the caller still draws its dot
     */
    private static boolean drawIcon(
        final MatrixStack matrices,
        final MinecraftClient client,
        final EntityIconManager iconManager,
        final EntityIconManager.FaceIcon icon,
        final float x,
        final float y,
        final float iconSize,
        final int yDelta,
        final float alphaScale
    ) {
        final float iconHalfSize = iconSize / 2f;
        final int tint = Argb.scaleAlpha(elevationColor(0xFFFFFFFF, yDelta), alphaScale);
        if (icon.dynamic()) {
            if (!iconManager.bindDynamicColor()) {
                return false;
            }
        } else {
            RenderUtil.bindTexture(client, icon.texture());
        }
        RenderUtil.drawTintedQuad(
            matrices, x - iconHalfSize, y - iconHalfSize, iconSize, iconSize,
            icon.u0(), icon.v0(), icon.u1(), icon.v1(), tint
        );
        if (icon.hasOverlay()) {
            RenderUtil.bindTexture(client, icon.overlayTexture());
            RenderUtil.drawTintedQuad(
                matrices, x - iconHalfSize, y - iconHalfSize, iconSize, iconSize,
                icon.ou0(), icon.ov0(), icon.ou1(), icon.ov1(), tint
            );
        }
        return true;
    }

    /** Draws a compact count plate centered on the representative marker's bottom-right corner. */
    private static void drawStackCount(
        final GuiDraw draw,
        final MinecraftClient client,
        final float x,
        final float y,
        final float markerHalfSize,
        final float iconSize,
        final int count,
        final boolean spectator
    ) {
        final String text = Integer.toString(count);
        final float alphaScale = spectator ? SPECTATOR_ALPHA : 1f;
        final float textScale = Math.max(0.4f, Math.min(0.65f, iconSize / 16f));
        final int textWidth = client.textRenderer.getWidth(text);
        final float badgeWidth = textWidth * textScale + 2f;
        final float badgeHeight = client.textRenderer.fontHeight * textScale + 1f;
        final float centerX = x + markerHalfSize - 0.5f;
        final float centerY = y + markerHalfSize - 0.5f;
        final float left = centerX - badgeWidth / 2f;
        final float top = centerY - badgeHeight / 2f;
        RenderUtil.fillRect(
            draw.matrices(), left, top, badgeWidth, badgeHeight,
            Argb.scaleAlpha(STACK_BADGE_BACKGROUND, alphaScale)
        );
        draw.matrices().push();
        draw.matrices().translate(left + 1f, top + 0.5f, 0f);
        draw.matrices().scale(textScale, textScale, 1f);
        draw.drawTextWithShadow(
            client.textRenderer, text, 0f, 0f, Argb.scaleAlpha(STACK_BADGE_TEXT, alphaScale)
        );
        draw.matrices().pop();
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
        final GuiDraw draw,
        final String text,
        final float centerX,
        final float y,
        final float alphaScale
    ) {
        final int width = client.textRenderer.getWidth(text);
        draw.drawTextWithShadow(
            client.textRenderer, text, centerX - width / 2f, y, Argb.scaleAlpha(TEXT_COLOR, alphaScale)
        );
    }
}
