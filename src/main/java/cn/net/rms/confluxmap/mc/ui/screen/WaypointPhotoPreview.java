package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.MinecraftClient;
import java.util.Objects;

/** A bounded one-thumbnail cache owned by a screen; stale async completions cannot upload. */
final class WaypointPhotoPreview implements AutoCloseable {
    private WaypointMediaService.Key key;
    private record Preview(WaypointMediaService.Pixels pixels, double rotation) {}

    private WaypointPhotoTexture texture;
    private double rotation;
    private int generation;
    private boolean closed;
    private String error = "";
    void show(WaypointMediaService.Key next) {
        if (Objects.equals(key, next) && !closed && (texture == null || !texture.closed())) return;
        clear(); key = next; closed = false;
        if (next == null) return;
        int request = generation;
        var service = ConfluxMapClient.get().waypointMediaService();
        service.list(next).thenCompose(photos -> {
            if (photos.isEmpty()) return java.util.concurrent.CompletableFuture.<Preview>completedFuture(null);
            var photo = photos.get(0);
            return service.waypointThumbnail(next, photo).thenApply(pixels -> new Preview(pixels, photo.rotation()));
        }).whenComplete((preview, failure) -> MinecraftClient.getInstance().execute(() -> {
            if (closed || request != generation || !next.world().equals(ConfluxMapClient.get().waypointLibraryService().world())) return;
            if (failure != null) error = WaypointImagesScreen.label("unavailable").getString();
            else if (preview != null) {
                texture = new WaypointPhotoTexture(preview.pixels());
                rotation = preview.rotation();
            }
        }));
    }
    boolean ready() { return texture != null && !texture.closed(); }

    boolean draw(GuiDraw draw, int x, int y, int w, int h) {
        if (texture == null) return false;
        texture.draw(draw, x, y, w, h, 1, 0, 0, rotation); return true;
    }
    String error() { return error; }
    private void clear() { generation++; error = ""; if (texture != null) { texture.close(); texture = null; } }
    @Override public void close() { closed = true; clear(); key = null; }
}
