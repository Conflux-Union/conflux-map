package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointListMemory.MapPhotoMode;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;

final class WaypointMapPhotoOverlay {
    private final Map<MapPhotoMarkers.Bounds, WaypointMediaService.Key> markers = new LinkedHashMap<>();
    private WorldIdentity world;
    private Map<WaypointMediaService.Key, WaypointMediaService.Photo> firstPhotos = Map.of();
    private int generation;

    private static MapPhotoMode mode() {
        var options = ConfluxMapClient.get().waypointListMemory().options();
        return options.images ? options.waypointPhotoMode : MapPhotoMode.OFF;
    }

    void beginFrame(WorldIdentity next) {
        markers.clear();
        if (mode() == MapPhotoMode.OFF) next = null;
        if (java.util.Objects.equals(world, next)) return;
        clear();
        world = next;
        if (next == null) return;
        int request = generation;
        ConfluxMapClient.get().waypointMediaService().firstPhotos(next).whenComplete((photos, failure) ->
            MinecraftClient.getInstance().execute(() -> {
                if (request != generation) return;
                if (failure != null) ConfluxMapMod.LOGGER.warn("Could not load waypoint image markers", failure);
                else firstPhotos = photos;
            }));
    }

    void draw(GuiDraw draw, FullscreenMapScreen screen, MapPhotoMarkers canvas, TextRenderer font,
              WaypointRenderEntry point, float x, float y, int mx, int my) {
        if (mode() != MapPhotoMode.THUMBNAIL) return;
        var key = key(point);
        if (key == null) return;
        var photo = firstPhotos.get(key);
        if (photo == null) return;
        final int offset = 34;
        final int screenX = Math.round(x);
        final int screenY = Math.round(y);
        final java.util.function.Supplier<java.util.concurrent.CompletableFuture<WaypointMediaService.Pixels>> decode =
            () -> ConfluxMapClient.get().waypointMediaService().waypointThumbnail(key, photo);
        var bounds = canvas.draw(draw, screen, font, screenX - offset, screenY,
            1, true, mx, my, photo.id(), photo.rotation(), decode);
        if (bounds == null) {
            bounds = canvas.draw(draw, screen, font, screenX + offset, screenY,
                1, true, mx, my, photo.id(), photo.rotation(), decode);
        }
        if (bounds != null) markers.put(bounds, key);
    }

    boolean open(FullscreenMapScreen screen, double x, double y) {
        if (mode() != MapPhotoMode.THUMBNAIL) return false;
        for (var marker : markers.entrySet()) {
            if (!marker.getKey().contains(x, y)) continue;
            return openGallery(screen, marker.getValue());
        }
        return false;
    }

    WaypointMediaService.Photo iconPhoto(WaypointRenderEntry point) {
        var key = key(point);
        return mode() == MapPhotoMode.ICON && key != null ? firstPhotos.get(key) : null;
    }

    void previewIcon(MapPhotoMarkers canvas, WaypointRenderEntry point, WaypointMediaService.Photo photo) {
        var key = key(point);
        if (key == null) return;
        canvas.hoverIcon(photo.id(), photo.rotation(),
            () -> ConfluxMapClient.get().waypointMediaService().waypointThumbnail(key, photo));
    }

    boolean openIcon(FullscreenMapScreen screen, WaypointRenderEntry point) {
        return iconPhoto(point) != null && openGallery(screen, key(point));
    }

    private WaypointMediaService.Key key(WaypointRenderEntry point) {
        if (world == null || point.source() == WaypointRenderEntry.Source.SIBLING) return null;
        return new WaypointMediaService.Key(world, point.shared(), point.id());
    }

    private boolean openGallery(FullscreenMapScreen screen, WaypointMediaService.Key key) {
        final var app = ConfluxMapClient.get();
        final var model = key.shared() ? app.sharedWaypointListModel() : app.localWaypointListModel();
        if (!screen.viewingLiveWorld() || !key.world().equals(app.gameBridge().session().world())
            || model.rowById(key.waypoint()) == null) return false;
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), new WaypointImagesScreen(screen, key,
            () -> key.world().equals(app.gameBridge().session().world()) && model.rowById(key.waypoint()) != null));
        return true;
    }

    void clear() {
        generation++;
        world = null;
        markers.clear();
        firstPhotos = Map.of();
    }
}
