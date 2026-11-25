package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointMetadata;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointGridLayout.Cell;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;

/** The live map's waypoint details and photo, anchored to a single rendered marker. */
final class MapWaypointHover {
    private static final int WIDTH = 168;
    private static final int HEADER = 24;
    private static final int ROW = 17;
    private static final int PADDING = 5;
    private static final int CAMERA_WIDTH = 18;
    private static final long APPROACH_TIME = 250_000_000L;

    private record Line(OrderedText text, String item) {}

    private WaypointRenderEntry point;
    private Cell anchor;
    private CompactWaypointPanelLayout card;
    private MapPhotoMarkers.Bounds camera;
    private List<Line> lines = List.of();
    private WaypointMetadata previous;
    private int scroll, rows, fromX, fromY;
    private long hoverUntil;

    WaypointRenderEntry point() { return point; }

    void track(WaypointRenderEntry next, int x, int y, int radius, int mx, int my) {
        long now = System.nanoTime();
        if (card != null && card.contains(mx, my)) {
            hoverUntil = now + APPROACH_TIME;
            return;
        }
        if (card != null && now <= hoverUntil && !Objects.equals(next, point)
            && card.onApproach(anchor, fromX, fromY, mx, my)) return;
        if (!Objects.equals(point, next)) clear();
        if (next == null) return;
        point = next;
        var nextAnchor = new Cell(0, x - radius, y - radius, radius * 2 + 1, radius * 2 + 1);
        if (!nextAnchor.equals(anchor)) card = null;
        anchor = nextAnchor;
        fromX = mx;
        fromY = my;
        hoverUntil = now + APPROACH_TIME;
    }

    boolean draw(FullscreenMapScreen map, GuiDraw draw, TextRenderer font, WaypointMapPhotoOverlay photos,
                 MapPhotoMarkers thumbnails, int width, int height, int mx, int my) {
        final var app = ConfluxMapClient.get();
        final var library = app.waypointLibraryService();
        if (point == null || !map.viewingLiveWorld() || point.source() == WaypointRenderEntry.Source.SIBLING
            || !map.viewSession().world().equals(library.world())) {
            clear();
            return false;
        }
        var metadata = app.waypointListMemory().options().details
            ? library.library().get(point.id(), point.shared()) : WaypointMetadata.EMPTY;
        var photo = photos.iconPhoto(point);
        if (photo == null && metadata.note().isBlank() && metadata.tags().isEmpty() && metadata.resourceItems().isEmpty()) {
            card = null;
            camera = null;
            return false;
        }
        if (!metadata.equals(previous)) {
            previous = metadata;
            lines = buildLines(font, metadata);
        }
        int availableRows = Math.max(0, (height - 28 - HEADER - PADDING - 4) / ROW);
        rows = Math.min(lines.size(), Math.min(8, availableRows));
        int cardHeight = HEADER + rows * ROW + PADDING;
        if (card == null || card.height() != cardHeight || !fits(map, card, width, height)) {
            card = CompactWaypointPanelLayout.place(anchor, width, height, cardHeight, rect -> fits(map, rect, width, height));
            if (card == null) {
                card = null;
                camera = null;
                return false;
            }
        }
        scroll = Math.min(scroll, lines.size() - rows);
        int x = card.x(), y = card.y(), w = card.width();
        draw.fill(x, y, x + w, y + card.height(), 0xF0202020);
        camera = photo == null ? null : new MapPhotoMarkers.Bounds(x + w - PADDING - CAMERA_WIDTH, y + PADDING, CAMERA_WIDTH, 14);
        int nameWidth = w - 2 * PADDING - (camera == null ? 0 : CAMERA_WIDTH + 3);
        draw.drawTextWithShadow(font, font.trimToWidth(point.name(), nameWidth), x + PADDING, y + 7, 0xFFFFFFFF);
        if (camera != null) {
            boolean hovered = camera.contains(mx, my);
            draw.fill(camera.x(), camera.y(), camera.x() + camera.w(), camera.y() + camera.h(),
                hovered ? 0xFF535353 : 0xFF383838);
            MapPhotoMarkers.drawCamera(draw, camera.x() + camera.w() / 2, camera.y() + camera.h() / 2, hovered);
            if (hovered) photos.previewIcon(thumbnails, point, photo);
        }
        for (int i = 0; i < rows; i++) {
            Line line = lines.get(i + scroll);
            int rowY = y + HEADER + i * ROW;
            if (line.item() != null) {
                var stack = WaypointMarkerRenderer.itemIcon(line.item());
                if (!stack.isEmpty()) draw.drawItemIcon(MinecraftClient.getInstance(), stack, x + PADDING + 6, rowY + 6, 13);
            }
            draw.drawTextWithShadow(font, line.text(), x + PADDING + (line.item() == null ? 0 : 16), rowY + 2, 0xFFDDDDDD);
        }
        if (rows > 0 && rows < lines.size()) {
            int thumb = Math.max(3, rows * ROW * rows / lines.size());
            int thumbY = y + HEADER + (rows * ROW - thumb) * scroll / (lines.size() - rows);
            draw.fill(x + w - 3, thumbY, x + w - 1, thumbY + thumb, 0xFF999999);
        }
        return true;
    }

    private static boolean fits(FullscreenMapScreen map, CompactWaypointPanelLayout rect, int width, int height) {
        return rect.x() >= 0 && rect.y() >= 0 && rect.x() + rect.width() <= width && rect.y() + rect.height() <= height
            && !map.mapOverlayIntersectsUi(rect.x(), rect.y(), rect.x() + rect.width(), rect.y() + rect.height());
    }

    private static List<Line> buildLines(TextRenderer font, WaypointMetadata metadata) {
        List<Line> result = new ArrayList<>();
        if (!metadata.note().isBlank()) appendLines(result, font, "confluxmap.map.waypoint.note", metadata.note());
        if (!metadata.tags().isEmpty()) {
            List<String> tags = new ArrayList<>();
            for (String tag : metadata.tags()) {
                tags.add(cn.net.rms.confluxmap.core.waypoint.library.WaypointTag.fromStored(tag)
                    .map(value -> Texts.translatable(value.translationKey()).getString()).orElse(tag));
            }
            appendLines(result, font, "confluxmap.map.waypoint.tags", String.join(" / ", tags));
        }
        if (!metadata.resourceItems().isEmpty()) {
            result.add(new Line(Texts.translatable("confluxmap.map.waypoint.products").asOrderedText(), null));
            for (String id : metadata.resourceItems()) {
                Integer amount = metadata.resourceAmounts().get(id);
                String value = amount == null ? "" : " ×" + amount;
                int available = WIDTH - 2 * PADDING - 19 - font.getWidth(value);
                String name = font.trimToWidth(WaypointDetailsText.itemName(id), Math.max(0, available));
                result.add(new Line(Texts.literal(name + value).asOrderedText(), id));
            }
        }
        return List.copyOf(result);
    }

    private static void appendLines(List<Line> result, TextRenderer font, String label, String value) {
        for (OrderedText line : font.wrapLines(Texts.translatable(label, value), WIDTH - 2 * PADDING - 3)) {
            result.add(new Line(line, null));
        }
    }

    boolean click(FullscreenMapScreen map, WaypointMapPhotoOverlay photos, double x, double y) {
        if (card == null || !card.contains(x, y)) return false;
        if (camera != null && camera.contains(x, y)) photos.openIcon(map, point);
        return true;
    }

    boolean scroll(double x, double y, double delta) {
        if (card == null || !card.contains(x, y) || delta == 0) return false;
        scroll = Math.max(0, Math.min(lines.size() - rows, scroll + (delta > 0 ? -1 : 1)));
        return true;
    }

    void clear() {
        point = null;
        anchor = null;
        card = null;
        camera = null;
        lines = List.of();
        previous = null;
        scroll = 0;
        rows = 0;
    }
}
