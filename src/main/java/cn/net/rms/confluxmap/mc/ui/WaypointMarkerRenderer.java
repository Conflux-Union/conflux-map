package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.WaypointVerticalRelation;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;

/**
 * VoxelMap-style waypoint marker drawing shared by {@code MinimapHudRenderer} and
 * {@code FullscreenMapScreen} (deliverable D) so both surfaces render identical marker
 * shapes - only their size, alpha and hover handling differ per surface. Every waypoint
 * uses the player-selected color as its plate and a contrasting first-name character,
 * keeping the minimap and fullscreen map visually consistent.
 */
public final class WaypointMarkerRenderer {
    private static final int OUTER_CONTRAST = 0xFF101010;
    private static final int WHITE_TEXT = 0xFFFFFFFF;
    private static final int LIGHT_BACKGROUND_LUMINANCE = 186;
    private static final int SHARED_OUTLINE = 0xFF55DDE0;
    private static final int HEIGHT_BADGE_OUTLINE = 0xFF101010;
    private static final int HEIGHT_BADGE_FILL = 0xFFFFFFFF;
    /** 50% white overlay used to brighten a hovered marker's fill color (fullscreen map only). */
    private static final int HOVER_TINT = 0x80FFFFFF;

    /** Corner "T" badge marking local temporary waypoints, mirroring the height badge's two-tone style. */
    public static final int TEMPORARY_BADGE_OUTLINE = 0xFF101010;
    public static final int TEMPORARY_BADGE_TEXT = 0xFFFFFFFF;
    public static final float TEMPORARY_BADGE_SIZE = 6f;
    public static final String TEMPORARY_BADGE_LETTER = "T";

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
        final GuiDraw draw,
        final TextRenderer textRenderer,
        final WaypointRenderEntry waypoint,
        final float x,
        final float y,
        final float halfSize,
        final float alpha,
        final boolean hovered,
        final WaypointVerticalRelation verticalRelation
    ) {
        final MatrixStack matrices = draw.matrices();
        final int fill = fillColor(waypoint.colorArgb(), alpha, hovered);
        final int outer = withAlpha(outlineColor(waypoint), alpha);
        final float plateSize = halfSize * 2f;
        GuiShapes.fillRect(matrices, x - halfSize - 1f, y - halfSize - 1f, plateSize + 2f, plateSize + 2f, outer);
        GuiShapes.fillRect(matrices, x - halfSize, y - halfSize, plateSize, plateSize, fill);

        final ItemStack itemIcon = itemIcon(waypoint.iconItemId());
        if (!itemIcon.isEmpty()) {
            draw.drawItemIcon(
                MinecraftClient.getInstance(),
                itemIcon,
                x,
                y,
                Math.max(1f, plateSize - 2f)
            );
            drawHeightBadge(matrices, verticalRelation, x, y, halfSize, alpha);
            drawTemporaryBadge(draw, textRenderer, waypoint, x, y, halfSize, alpha);
            return;
        }

        final String markerText = markerText(waypoint.name(), waypoint.markerLabel());
        final int textWidth = textRenderer.getWidth(markerText);
        final float available = Math.max(1f, plateSize - 2f);
        final float textScale = Math.min(1f, available / Math.max(textWidth, textRenderer.fontHeight));
        matrices.push();
        matrices.translate(x, y, 0);
        matrices.scale(textScale, textScale, 1f);
        draw.drawTextWithShadow(
            textRenderer,
            markerText,
            -textWidth / 2f,
            -textRenderer.fontHeight / 2f,
            withAlpha(textColorFor(fill), alpha)
        );
        matrices.pop();
        drawHeightBadge(matrices, verticalRelation, x, y, halfSize, alpha);
        drawTemporaryBadge(draw, textRenderer, waypoint, x, y, halfSize, alpha);
    }

    public static String markerText(final String name, final String markerLabel) {
        return markerLabel == null || markerLabel.isBlank()
            ? initial(name)
            : markerLabel;
    }

    public static ItemStack itemIcon(final String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            return Regs.item(Ids.of(itemId))
                .map(ItemStack::new)
                .filter(stack -> !stack.isEmpty())
                .orElse(ItemStack.EMPTY);
        } catch (final IllegalArgumentException e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * First non-blank code point of the name; validation normally prevents the fallback.
     * Skips all Unicode whitespace and space separators (including NBSP and ideographic
     * space, which {@code trim()}/{@code strip()} keep or miss) so the marker initial is
     * always a visible glyph when the name has one.
     */
    static String initial(final String name) {
        if (name == null) {
            return "?";
        }
        final int[] visible = name.codePoints()
            .filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))
            .limit(1)
            .toArray();
        if (visible.length == 0) {
            return "?";
        }
        return new String(visible, 0, 1);
    }

    /** Select black on light marker plates and white on dark plates. */
    public static int textColorFor(final int markerColorArgb) {
        return Argb.luminance(markerColorArgb) >= LIGHT_BACKGROUND_LUMINANCE
            ? Argb.OPAQUE_BLACK
            : WHITE_TEXT;
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
        return SHARED_OUTLINE;
    }

    /** Geometry output for {@link #emitHeightBadge}, so any submission channel can host the badge. */
    public interface HeightBadgeConsumer {
        void rect(float x, float y, float width, float height, int color);

        void triangle(float x0, float y0, float x1, float y1, float x2, float y2, int color);
    }

    /**
     * Emits the height badge centered on {@code (centerX, centerY)}: a square for LEVEL,
     * an outline-plus-fill triangle pointing along the relation otherwise. Shared by the
     * map markers and both in-world label flavors; NONE emits nothing.
     */
    public static void emitHeightBadge(
        final WaypointVerticalRelation relation,
        final float centerX,
        final float centerY,
        final float alpha,
        final HeightBadgeConsumer out
    ) {
        if (relation == WaypointVerticalRelation.NONE) {
            return;
        }
        final int outline = withAlpha(HEIGHT_BADGE_OUTLINE, alpha);
        final int fill = withAlpha(HEIGHT_BADGE_FILL, alpha);
        if (relation == WaypointVerticalRelation.LEVEL) {
            out.rect(centerX - 2.5f, centerY - 2.5f, 5f, 5f, outline);
            out.rect(centerX - 1.5f, centerY - 1.5f, 3f, 3f, fill);
            return;
        }
        final boolean above = relation == WaypointVerticalRelation.ABOVE;
        final float tipY = above ? centerY - 2.5f : centerY + 2.5f;
        final float baseY = above ? centerY + 2f : centerY - 2f;
        out.triangle(centerX, tipY, centerX - 2.5f, baseY, centerX + 2.5f, baseY, outline);
        final float innerTipY = above ? centerY - 1.2f : centerY + 1.2f;
        final float innerBaseY = above ? centerY + 1f : centerY - 1f;
        out.triangle(centerX, innerTipY, centerX - 1.2f, innerBaseY, centerX + 1.2f, innerBaseY, fill);
    }

    /**
     * The badge drawn straight onto a GUI matrix stack - the map markers and the item-icon
     * HUD label flavor use this; {@code (x, y)} is the icon center.
     */
    public static void drawHeightBadge(
        final MatrixStack matrices,
        final WaypointVerticalRelation relation,
        final float x,
        final float y,
        final float halfSize,
        final float alpha
    ) {
        emitHeightBadge(relation, x - halfSize, y - halfSize, alpha, new HeightBadgeConsumer() {
            @Override
            public void rect(final float rx, final float ry, final float w, final float h, final int color) {
                GuiShapes.fillRect(matrices, rx, ry, w, h, color);
            }

            @Override
            public void triangle(
                final float x0, final float y0, final float x1, final float y1,
                final float x2, final float y2, final int color
            ) {
                GuiShapes.fillTriangle(matrices, x0, y0, x1, y1, x2, y2, color);
            }
        });
    }

    /**
     * Dark plate with a half-height white "T" on the marker's top-right corner -
     * the height badge's mirrored twin.
     */
    public static void drawTemporaryBadge(
        final GuiDraw draw,
        final TextRenderer textRenderer,
        final WaypointRenderEntry waypoint,
        final float x,
        final float y,
        final float halfSize,
        final float alpha
    ) {
        if (waypoint.type() != Waypoint.Type.TEMPORARY || !waypoint.local()) {
            return;
        }
        final MatrixStack matrices = draw.matrices();
        final float half = TEMPORARY_BADGE_SIZE / 2f;
        GuiShapes.fillRect(
            matrices, x + halfSize - half, y - halfSize - half,
            TEMPORARY_BADGE_SIZE, TEMPORARY_BADGE_SIZE,
            withAlpha(TEMPORARY_BADGE_OUTLINE, alpha)
        );
        final int textWidth = textRenderer.getWidth(TEMPORARY_BADGE_LETTER);
        matrices.push();
        matrices.translate(x + halfSize, y - halfSize, 0);
        matrices.scale(0.5f, 0.5f, 1f);
        draw.drawTextWithShadow(
            textRenderer,
            TEMPORARY_BADGE_LETTER,
            -textWidth / 2f,
            -textRenderer.fontHeight / 2f,
            withAlpha(TEMPORARY_BADGE_TEXT, alpha)
        );
        matrices.pop();
    }

    /** Localized "(temporary)" suffix shared by the list rows and the in-world labels. */
    public static String temporaryTag() {
        return Texts.translatable("confluxmap.waypoint.temporary.tag").getString();
    }

    private static int withAlpha(final int argb, final float alpha) {
        final int a = Math.round(Argb.alpha(argb) * MathHelper.clamp(alpha, 0f, 1f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
