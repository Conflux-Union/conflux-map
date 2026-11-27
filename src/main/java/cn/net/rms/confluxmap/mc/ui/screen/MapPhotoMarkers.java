package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;

/** Shared collision bounds, hit testing and bounded thumbnail textures for the full map. */
final class MapPhotoMarkers {
    private static final int MAX_TEXTURES = 24;
    private static final int MAX_PENDING = 4;
    private final List<Bounds> visible = new ArrayList<>();
    private final Map<Object, Thumbnail> textures = new LinkedHashMap<>();
    private int frame, pending;
    private Hover hoveredIcon;
    private int viewportWidth, viewportHeight;

    record Bounds(float x, float y, float w, float h) {
        boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
        boolean overlaps(Bounds other) {
            return x < other.x + other.w && x + w > other.x && y < other.y + other.h && y + h > other.y;
        }
    }

    private record Hover(Object key, double rotation, Supplier<CompletableFuture<WaypointMediaService.Pixels>> decode) {}

    private static final class Thumbnail {
        int used;
        boolean failed;
        WaypointPhotoTexture texture;
    }

    void beginFrame(int width, int height) {
        viewportWidth = width;
        viewportHeight = height;
        frame++;
        hoveredIcon = null;
        visible.clear();
        textures.values().removeIf(entry -> {
            if (frame - entry.used <= 2 && (entry.texture == null || !entry.texture.closed())) return false;
            if (entry.texture != null) entry.texture.close();
            return true;
        });
    }

    Bounds draw(GuiDraw draw, FullscreenMapScreen map, TextRenderer font, float x, float y, int count,
                boolean thumbnail, int mx, int my, Object textureKey, double rotation,
                Supplier<CompletableFuture<WaypointMediaService.Pixels>> decode) {
        final String badge = count > 1 ? "×" + count : "";
        final float bodyWidth = thumbnail ? 48f : 16f;
        final float bodyHeight = thumbnail ? 34f : 14f;
        final float badgeWidth = badge.isEmpty() ? 0f : font.getWidth(badge) + 4f;
        final Bounds bounds = new Bounds(x - bodyWidth * 0.5f, y - bodyHeight * 0.5f, bodyWidth + badgeWidth, bodyHeight);
        if (bounds.x < 0 || bounds.y < 0 || bounds.x + bounds.w > viewportWidth || bounds.y + bounds.h > viewportHeight
            || map.mapOverlayIntersectsUi(bounds.x, bounds.y, bounds.x + bounds.w, bounds.y + bounds.h)
            || visible.stream().anyMatch(bounds::overlaps)) return null;
        visible.add(bounds);

        final boolean hovered = bounds.contains(mx, my);
        if (hovered && !thumbnail) hoverIcon(textureKey, rotation, decode);
        GuiShapes.fillRect(draw.matrices(), bounds.x, bounds.y, bounds.w, bounds.h, 0xEE202020);
        final WaypointPhotoTexture texture = thumbnail ? texture(textureKey, decode) : null;
        if (texture != null) {
            texture.draw(draw, bounds.x + 2f, bounds.y + 2f, bodyWidth - 4f, bodyHeight - 4f, 1, 0, 0, rotation);
        } else {
            drawCamera(draw, bounds.x + bodyWidth * 0.5f, y, hovered);
        }
        if (!badge.isEmpty()) draw.drawTextWithShadow(font, badge, bounds.x + bodyWidth + 1f, y - 4f, 0xFFFFFFFF);
        return bounds;
    }

    static void drawCamera(GuiDraw draw, float x, float y, boolean hovered) {
        int color = hovered ? 0xFFFFD36A : 0xFFF1F1F1;
        GuiShapes.fillRect(draw.matrices(), x - 7f, y - 4f, 14f, 9f, color);
        GuiShapes.fillRect(draw.matrices(), x - 4f, y - 6f, 6f, 2f, color);
        GuiShapes.fillRect(draw.matrices(), x - 3f, y - 3f, 6f, 7f, 0xFF303030);
        GuiShapes.fillRect(draw.matrices(), x - 1f, y - 1f, 3f, 3f, 0xFF78A5DC);
    }

    void hoverIcon(Object key, double rotation, Supplier<CompletableFuture<WaypointMediaService.Pixels>> decode) {
        hoveredIcon = new Hover(key, rotation, decode);
    }

    boolean drawHover(GuiDraw draw, TextRenderer font, int mx, int my) {
        if (hoveredIcon == null) return false;

        var texture = texture(hoveredIcon.key(), hoveredIcon.decode());
        var entry = textures.get(hoveredIcon.key());
        final int width = Math.min(144, viewportWidth);
        final int height = Math.min(texture == null ? 20 : 96, viewportHeight);
        if (width <= 4 || height <= 4) return false;
        final int x = Math.max(0, mx + width + 12 <= viewportWidth ? mx + 12 : mx - width - 8);
        final int y = Math.max(0, my + height + 12 <= viewportHeight ? my + 12 : my - height - 8);
        draw.fill(x, y, x + width, y + height, 0xF0202020);
        if (texture != null) {
            texture.draw(draw, x + 2, y + 2, width - 4, height - 4, 1, 0, 0, hoveredIcon.rotation());
        } else {
            var status = WaypointImagesScreen.label(entry != null && entry.failed ? "unavailable" : "loading");
            draw.drawTextWithShadow(font, font.trimToWidth(status.getString(), width - 8), x + 4, y + 6, 0xFFBBBBBB);
        }
        return true;
    }

    private WaypointPhotoTexture texture(Object key, Supplier<CompletableFuture<WaypointMediaService.Pixels>> decode) {
        Thumbnail entry = textures.get(key);
        if (entry != null) {
            entry.used = frame;
            return entry.texture;
        }
        if (pending >= MAX_PENDING || textures.size() >= MAX_TEXTURES) return null;
        final Thumbnail requested = new Thumbnail();
        requested.used = frame;
        textures.put(key, requested);
        pending++;
        decode.get().whenComplete((pixels, error) -> MinecraftClient.getInstance().execute(() -> {
            pending--;
            if (textures.get(key) != requested) return;
            if (error == null) requested.texture = new WaypointPhotoTexture(pixels);
            else requested.failed = true;
        }));
        return null;
    }

    void clear() {
        hoveredIcon = null;
        visible.clear();
        for (Thumbnail entry : textures.values()) if (entry.texture != null) entry.texture.close();
        textures.clear();
    }
}
