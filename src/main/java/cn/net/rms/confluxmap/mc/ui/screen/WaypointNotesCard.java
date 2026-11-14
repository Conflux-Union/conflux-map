package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.mc.net.waypointimage.WaypointImageClient;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.TextureBinds;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.OrderedText;

/**
 * Tooltip-style hover card for waypoints that carry notes, shared by the
 * fullscreen map's marker hover and the waypoint list's row hover: the
 * fullscreen map's floating-panel style ({@link FullscreenMapLocationMenu})
 * with a 48px thumbnail and up to six wrapped description lines. A missing
 * thumbnail draws its placeholder; the fetch issued on hover fills it in on
 * a later frame.
 */
final class WaypointNotesCard {
    private static final int THUMBNAIL = 48;
    private static final int THUMBNAIL_GAP = 8;
    private static final int PADDING = 4;
    private static final int CURSOR_OFFSET = 12;
    private static final int EDGE_MARGIN = 4;
    private static final int MAX_LINES = 6;
    private static final int MAX_WRAP_WIDTH = 220;
    private static final int PLACEHOLDER_COLOR = 0xFF3F3F3F;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private WaypointNotesCard() {
    }

    static void draw(
        final GuiDraw draw,
        final TextRenderer font,
        final String description,
        final String imageId,
        final int mouseX,
        final int mouseY,
        final int screenWidth,
        final int screenHeight
    ) {
        final WaypointImageClient images = ConfluxMapClient.get().waypointImages();
        if (images != null && !imageId.isEmpty()) {
            images.ensureFetched(List.of(imageId));
        }
        final Optional<WaypointImageClient.Thumbnail> thumbnail =
            images == null || imageId.isEmpty()
                ? Optional.empty()
                : images.thumbnail(imageId);
        final boolean imageColumn = thumbnail.isPresent() || !imageId.isEmpty();
        final List<String> wrapped = wrapDescription(font, description, screenWidth);
        final int textWidth = wrapped.stream().mapToInt(font::getWidth).max().orElse(0);
        final int textHeight = wrapped.size() * (font.fontHeight + 2);
        final int imageBlock = imageColumn ? THUMBNAIL + THUMBNAIL_GAP : 0;
        final int panelWidth = Math.min(
            PADDING + imageBlock + textWidth + PADDING,
            screenWidth - 2 * EDGE_MARGIN
        );
        final int panelHeight = PADDING
            + Math.max(imageColumn ? THUMBNAIL : 0, textHeight)
            + PADDING;
        int panelX = mouseX + CURSOR_OFFSET;
        int panelY = mouseY + CURSOR_OFFSET;
        panelX = Math.max(EDGE_MARGIN, Math.min(panelX, screenWidth - panelWidth - EDGE_MARGIN));
        panelY = Math.max(EDGE_MARGIN, Math.min(panelY, screenHeight - panelHeight - EDGE_MARGIN));
        FullscreenMapLocationMenu.drawPanel(
            draw, new FullscreenMapLocationMenu.Bounds(panelX, panelY, panelWidth, panelHeight)
        );

        final int innerHeight = panelHeight - PADDING * 2;
        final int textTop = panelY + PADDING + Math.max(0, (innerHeight - textHeight) / 2);
        final int textX = panelX + PADDING + imageBlock;
        int row = 0;
        for (final String line : wrapped) {
            draw.drawTextWithShadow(font, line, textX, textTop + row * (font.fontHeight + 2), TEXT_COLOR);
            row++;
        }
        final int imageX = panelX + PADDING;
        final int imageY = panelY + PADDING + Math.max(0, (innerHeight - THUMBNAIL) / 2);
        if (thumbnail.isPresent()) {
            bindThumbnail(thumbnail.get().texture());
            TextureBinds.beginTexturedQuads();
            GuiShapes.drawQuad(draw.matrices(), imageX, imageY, THUMBNAIL, THUMBNAIL, 0f, 0f, 1f, 1f);
        } else if (imageColumn) {
            GuiShapes.fillRect(draw.matrices(), imageX, imageY, THUMBNAIL, THUMBNAIL, PLACEHOLDER_COLOR);
        }
    }

    /**
     * Wraps the description into at most six lines; hitting the cap ends the
     * sixth line with an ellipsis instead of cutting silently. The wrap width
     * tracks the screen so narrow GUI scales never build an oversized panel.
     */
    private static List<String> wrapDescription(
        final TextRenderer font,
        final String description,
        final int screenWidth
    ) {
        final List<String> wrapped = new ArrayList<>();
        if (description.isEmpty()) {
            return wrapped;
        }
        final int wrapWidth = Math.min(MAX_WRAP_WIDTH, screenWidth / 3);
        for (final OrderedText ordered : font.wrapLines(Texts.literal(description), wrapWidth)) {
            final StringBuilder out = new StringBuilder();
            ordered.accept((index, style, codePoint) -> {
                out.appendCodePoint(codePoint);
                return true;
            });
            wrapped.add(out.toString());
            if (wrapped.size() >= MAX_LINES + 1) {
                break;
            }
        }
        if (wrapped.size() == MAX_LINES + 1) {
            wrapped.set(MAX_LINES - 1, wrapped.get(MAX_LINES - 1) + "…");
            wrapped.subList(MAX_LINES, wrapped.size()).clear();
        }
        return wrapped;
    }

    private static void bindThumbnail(final NativeImageBackedTexture texture) {
        //#if MC>=12108
        //$$ TextureBinds.bindTexture(texture.getGlTextureView());
        //#elseif MC>=12105
        //$$ TextureBinds.bindTexture(texture.getGlTexture());
        //#else
        TextureBinds.bindTexture(texture.getGlId());
        //#endif
    }
}
