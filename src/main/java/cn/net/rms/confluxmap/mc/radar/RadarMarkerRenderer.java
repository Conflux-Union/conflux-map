package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.radar.RadarCategory;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.List;
import java.util.function.Predicate;
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
 * Draws already-projected radar markers (entity head/item icon or diamond fallback,
 * plus optional player name labels), shared by {@code MinimapHudRenderer} and
 * {@code FullscreenMapScreen} so both surfaces share the same marker visuals while choosing
 * player-name visibility independently. Its visual rules
 * evolved from {@code MinimapHudRenderer}'s original radar-marker drawing - see
 * docs/reference-specs/radar-icons.md secs 2-3 for the VoxelMap-style look this reproduces. All
 * item icons intentionally render without a generated border; entity portraits have an
 * independently configurable outline.
 */
public final class RadarMarkerRenderer {
    private static final int PLAYER_COLOR = 0xFFFFFFFF;
    private static final int HOSTILE_COLOR = 0xFFFF4040;
    private static final int PASSIVE_COLOR = 0xFF50E060;
    private static final int OTHER_COLOR = 0xFFA0A0A0;
    private static final int VERTICAL_WINDOW = 32;

    private static final int ICON_OUTLINE = 0xD0000000;
    private static final float DIAMOND_RADIUS = 2f;
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

    /** Surface-selected marker detail level and player-name visibility. */
    public record Presentation(boolean detailedIcons, boolean showPlayerNames) {
        private static final Presentation COMPACT = new Presentation(false, false);
        private static final Presentation DETAILED = new Presentation(true, false);
        private static final Presentation DETAILED_WITH_NAMES = new Presentation(true, true);

        public static Presentation compact() {
            return COMPACT;
        }

        public static Presentation detailed(final boolean showPlayerNames) {
            return showPlayerNames ? DETAILED_WITH_NAMES : DETAILED;
        }

        /** Resolves the configured minimap default plus the temporary player-list expansion. */
        public static Presentation minimap(
            final ConfluxConfig.RadarDisplayMode displayMode,
            final boolean playerListPressed,
            final boolean showPlayerNames
        ) {
            if (displayMode == ConfluxConfig.RadarDisplayMode.DOTS && !playerListPressed) {
                return compact();
            }
            return detailed(playerListPressed && showPlayerNames);
        }
    }

    /**
     * Draws each marker at its projected position. Detailed mode uses an entity head or the
     * entity's normal in-game item icon when available, while compact mode keeps player portraits
     * and renders every other category as a colored diamond. Dropped and flying items use their
     * live stack; other targets use the same item form as creative pick-block where vanilla
     * exposes one.
     *
     * <p>Spectator-mode entries render every element (icon, marker, name) at
     * {@link #SPECTATOR_ALPHA} of its normal alpha, on top of any elevation fading.
     *
     * @param markers visible markers with screen-space positions already projected by the caller
     */
    public static void drawAll(
        final GuiDraw draw,
        final MinecraftClient client,
        final ConfluxConfig config,
        final EntityIconManager iconManager,
        final List<Marker> markers,
        final Presentation presentation
    ) {
        drawAll(draw, client, config, iconManager, markers, presentation, marker -> true);
    }

    /** Draws radar markers while allowing a screen to suppress markers covered by a modal overlay. */
    public static void drawAll(
        final GuiDraw draw,
        final MinecraftClient client,
        final ConfluxConfig config,
        final EntityIconManager iconManager,
        final List<Marker> markers,
        final Presentation presentation,
        final Predicate<Marker> visible
    ) {
        for (final Marker marker : markers) {
            if (visible.test(marker)) {
                drawMarker(draw, client, config, iconManager, marker, presentation);
            }
        }
    }

    private static void drawMarker(
        final GuiDraw draw,
        final MinecraftClient client,
        final ConfluxConfig config,
        final EntityIconManager iconManager,
        final Marker marker,
        final Presentation presentation
    ) {
        final RadarEntry entry = marker.entry();
        final float x = marker.x();
        final float y = marker.y();
        final int yDelta = marker.yDelta();
        final Entity live = marker.live();
        final MatrixStack matrices = draw.matrices();
        final float alphaScale = entry.spectator() ? SPECTATOR_ALPHA : 1f;
        if (usesDetailedIcon(entry.category(), presentation) && live != null) {
            final ItemStack itemIcon = entry.category() == RadarCategory.OTHER
                ? itemIconFor(live)
                : ItemStack.EMPTY;
            if (!itemIcon.isEmpty()) {
                draw.drawItemIcon(client, itemIcon, x, y, config.radarIconSize);
                return;
            }
            final EntityIconManager.FaceIcon icon = iconManager.iconFor(live);
            if (icon != null && drawIcon(
                matrices, client, iconManager, icon, x, y, config.radarIconSize,
                yDelta, alphaScale,
                config.radarPlayerIconOutlineEnabled ? config.radarIconOutlineThickness : 0
                )) {
                if (presentation.showPlayerNames()
                    && entry.category() == RadarCategory.PLAYER
                    && entry.name() != null) {
                    drawCenteredLine(
                        client, draw, entry.name(), x,
                        y + config.radarIconSize * icon.heightScale() / 2f + 2f, alphaScale
                    );
                }
                return;
            }
        }
        final int color = Argb.scaleAlpha(elevationColor(baseColor(entry.category()), yDelta), alphaScale);
        if (entry.category() == RadarCategory.PLAYER) {
            RenderUtil.fillRect(matrices, x - 2f, y - 2f, 4f, 4f, color);
            if (presentation.showPlayerNames() && entry.name() != null) {
                drawCenteredLine(client, draw, entry.name(), x, y + 3f, alphaScale);
            }
        } else {
            RenderUtil.fillBeveledDiamond(matrices, x, y, DIAMOND_RADIUS, color);
        }
    }

    static boolean usesDetailedIcon(
        final RadarCategory category,
        final Presentation presentation
    ) {
        return category == RadarCategory.PLAYER || presentation.detailedIcons();
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
     * Draws the portrait with the same elevation and spectator alpha as compact markers.
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
        final float alphaScale,
        final int outlineThickness
    ) {
        final float iconWidth = iconSize * icon.widthScale();
        final float iconHeight = iconSize * icon.heightScale();
        final float iconHalfWidth = iconWidth / 2f;
        final float iconHalfHeight = iconHeight / 2f;
        final int tint = Argb.scaleAlpha(elevationColor(0xFFFFFFFF, yDelta), alphaScale);
        if (icon.dynamic()) {
            if (!iconManager.bindDynamicColor()) {
                return false;
            }
        } else {
            RenderUtil.bindTexture(client, icon.texture());
        }
        if (outlineThickness > 0) {
            drawIconOutline(
                matrices, icon, x, y, iconWidth, iconHeight,
                outlineThickness, yDelta, alphaScale
            );
        }
        RenderUtil.drawTintedQuad(
            matrices, x - iconHalfWidth, y - iconHalfHeight, iconWidth, iconHeight,
            icon.u0(), icon.v0(), icon.u1(), icon.v1(), tint
        );
        if (icon.hasOverlay()) {
            RenderUtil.bindTexture(client, icon.overlayTexture());
            RenderUtil.drawTintedQuad(
                matrices, x - iconHalfWidth, y - iconHalfHeight, iconWidth, iconHeight,
                icon.ou0(), icon.ov0(), icon.ou1(), icon.ov1(), tint
            );
        }
        return true;
    }

    private static void drawIconOutline(
        final MatrixStack matrices,
        final EntityIconManager.FaceIcon icon,
        final float x,
        final float y,
        final float iconWidth,
        final float iconHeight,
        final int thickness,
        final int yDelta,
        final float alphaScale
    ) {
        final int color = Argb.scaleAlpha(
            elevationColor(ICON_OUTLINE, yDelta), alphaScale
        );
        RenderUtil.drawDarkTextureOutline(
            matrices, x - iconWidth / 2f, y - iconHeight / 2f,
            iconWidth, iconHeight,
            icon.u0(), icon.v0(), icon.u1(), icon.v1(), thickness, Argb.alpha(color) / 255f
        );
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
