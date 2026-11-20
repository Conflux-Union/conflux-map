package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.waypoint.library.MapScreenshotIndex;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointListMemory.MapPhotoMode;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

/** Projects screenshot locations in their own dimension without adding pathpoints. */
final class MapScreenshotOverlay {
    private record Chunk(int x, int z) {
        static Chunk at(MapScreenshotIndex.Location point) {
            return new Chunk(chunkCoordinate(point.x()), chunkCoordinate(point.z()));
        }
    }
    static int chunkCoordinate(double coordinate) { return Math.floorDiv((int) Math.floor(coordinate), 16); }

    private record Marker(MapPhotoMarkers.Bounds bounds, List<MapScreenshotIndex.Shot> shots) {}
    private List<MapScreenshotIndex.Shot> source;
    private List<List<MapScreenshotIndex.Shot>> groups = List.of();
    private final List<Marker> markers = new ArrayList<>();

    private static MapScreenshotIndex index() { return ConfluxMapClient.get().mapScreenshotIndex(); }

    void draw(GuiDraw draw, FullscreenMapScreen map, MapPhotoMarkers photos, TextRenderer font,
              SessionGuard.Session session, int width, int height, double centerX, double centerZ,
              double scale, int mx, int my) {
        markers.clear();
        var mode = ConfluxMapClient.get().waypointListMemory().options().screenshotMode;
        if (index() == null || !session.active() || mode == MapPhotoMode.OFF) return;
        var shots = index().list(MapScreenshotIndex.Scope.of(session.world(), session.dimension()));
        if (source != shots) {
            source = shots;
            var byChunk = new LinkedHashMap<Chunk, List<MapScreenshotIndex.Shot>>();
            for (var shot : shots) {
                byChunk.computeIfAbsent(Chunk.at(shot.location()), ignored -> new ArrayList<>()).add(shot);
            }
            groups = List.copyOf(byChunk.values());
        }
        for (var group : groups) {
            var shot = group.get(0);
            var point = shot.location();
            int x = (int) Math.round(width / 2.0 + (point.x() - centerX) / scale);
            int y = (int) Math.round(height / 2.0 + (point.z() - centerZ) / scale);
            if (x < 0 || y < 0 || x >= width || y >= height) continue;
            var bounds = photos.draw(draw, map, font, x, y, group.size(), mode == MapPhotoMode.THUMBNAIL, mx, my, shot.file(), shot.rotation(),
                () -> ConfluxMapClient.get().waypointMediaService().screenshotPixels(index().file(shot), true));
            if (bounds != null) markers.add(new Marker(bounds, group));
        }
    }

    boolean open(FullscreenMapScreen map, double x, double y) {
        if (ConfluxMapClient.get().waypointListMemory().options().screenshotMode == MapPhotoMode.OFF) return false;
        for (Marker marker : markers) {
            if (!marker.bounds().contains(x, y)) continue;
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), new Viewer(map, marker.shots()));
            return true;
        }
        return false;
    }

    void clear() { markers.clear(); }

    private static final class Image {
        private MapScreenshotIndex.Shot shot;
        private WaypointPhotoTexture texture;
        private int generation;
        private boolean failed;

        void show(MapScreenshotIndex.Shot next, boolean thumbnail) {
            if (Objects.equals(shot, next) && (texture == null || !texture.closed())) return;
            shot = next;
            int request = ++generation;
            failed = false;
            if (texture != null) { texture.close(); texture = null; }
            if (next == null) return;
            ConfluxMapClient.get().waypointMediaService().screenshotPixels(index().file(next), thumbnail)
                .whenComplete((pixels, error) -> MinecraftClient.getInstance().execute(() -> {
                    if (request != generation) return;
                    if (error != null) failed = true;
                    else texture = new WaypointPhotoTexture(pixels);
                }));
        }

    }

    private static Text captureTitle(MapScreenshotIndex.Shot shot) {
        return Texts.translatable("confluxmap.photos.coordinate_title",
            (int) Math.floor(shot.location().x()), (int) Math.floor(shot.location().z()));
    }

    private static final class Viewer extends PhotoViewerScreen {
        private final FullscreenMapScreen parent;
        private final List<MapScreenshotIndex.Shot> shots;
        private final Image image = new Image();
        private int selected;
        private boolean saving, saveFailed;

        Viewer(FullscreenMapScreen parent, List<MapScreenshotIndex.Shot> shots) {
            super(captureTitle(shots.get(0)));
            this.parent = parent;
            this.shots = new ArrayList<>(shots);
            resetView(shots.get(0).rotation());
        }

        @Override protected void init() {
            clearChildren();
            addDrawableChild(Widgets.button(16, height - 28, 36, 20, Texts.literal("<"), b -> select(selected - 1))).active = !saving && selected > 0;
            addDrawableChild(Widgets.button(56, height - 28, 36, 20, Texts.literal(">"), b -> select(selected + 1))).active = !saving && selected + 1 < shots.size();
            addDrawableChild(Widgets.button(100, height - 28, 36, 20, Texts.literal("-"), b -> zoom(-1)));
            addDrawableChild(Widgets.button(140, height - 28, 36, 20, Texts.literal("+"), b -> zoom(1)));
            //#if MC>=260100
            //$$ if (gesturesEnabled()) {
            //$$     addRenderableWidget(Widgets.button(width - 118, 6, 102, 20,
            //$$         WaypointImagesScreen.label(saveFailed ? "retry_rotation" : "save_rotation"),
            //$$         b -> saveRotation())).active = !saving;
            //$$ }
            //#else
            if (gesturesEnabled()) {
                addDrawableChild(Widgets.button(width - 118, 6, 102, 20,
                    WaypointImagesScreen.label(saveFailed ? "retry_rotation" : "save_rotation"),
                    b -> saveRotation())).active = !saving;
            }
            //#endif
            addDrawableChild(Widgets.button(width - 96, height - 28, 80, 20, Texts.translatable("gui.done"), b -> onClose()));
        }

        private void select(int next) {
            if (saving || next < 0 || next >= shots.size() || next == selected) return;
            selected = next;
            saveFailed = false;
            resetView(shots.get(selected).rotation());
            init();
        }

        //#if MC>=260100
        //$$ @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
        //$$     if (!isClosing() && (input.isLeft() || input.isRight())) {
        //$$         select(selected + (input.isLeft() ? -1 : 1));
        //$$         return true;
        //$$     }
        //$$     return super.keyPressed(input);
        //$$ }
        //#elseif MC>=12109
        //$$ @Override public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        //$$     if (!isClosing() && (input.isLeft() || input.isRight())) {
        //$$         select(selected + (input.isLeft() ? -1 : 1));
        //$$         return true;
        //$$     }
        //$$     return super.keyPressed(input);
        //$$ }
        //#else
        @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!isClosing() && (keyCode == cn.net.rms.confluxmap.compat.Keys.LEFT || keyCode == cn.net.rms.confluxmap.compat.Keys.RIGHT)) {
                select(selected + (keyCode == cn.net.rms.confluxmap.compat.Keys.LEFT ? -1 : 1));
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        //#endif

        private void saveRotation() {
            if (saving || !rotationChanged()) return;
            saving = true;
            init();
            index().saveRotation(shots.get(selected), rotation()).whenComplete((shot, error) ->
                net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                    saving = false;
                    saveFailed = error != null;
                    if (error == null) {
                        shots.set(selected, shot);
                        image.shot = shot;
                        markRotationSaved(shot.rotation());
                    }
                    if (MinecraftAccess.screen(net.minecraft.client.MinecraftClient.getInstance()) == this) init();
                }));
        }

        @Override protected void renderContents(GuiDraw draw, int mx, int my, float delta) {
            draw.renderBackground(this, mx, my, delta);
            var shot = shots.get(selected);
            image.show(shot, false);
            int titleWidth = width - 32;
            if (gesturesEnabled()) titleWidth -= 108;
            String pages = (selected + 1) + "/" + shots.size();
            String title = this.textRenderer.trimToWidth(captureTitle(shot).getString(),
                Math.max(0, titleWidth - this.textRenderer.getWidth("  " + pages)));
            draw.drawTextWithShadow(this.textRenderer, title + "  " + pages, 16, 12, 0xFFFFFFFF);
            if (image.texture != null) {
                drawPhoto(draw, image.texture, 16, 28, Math.max(1, width - 32), Math.max(1, height - 64));
            } else {
                draw.drawTextWithShadow(this.textRenderer,
                    WaypointImagesScreen.label(image.failed ? "unavailable" : "loading").getString(), 20, 32, 0xFFBBBBBB);
            }
        }

        @Override protected boolean imageReady() { return !saving && image.texture != null && !image.texture.closed(); }
        @Override public void removed() { image.show(null, false); super.removed(); }
        @Override public void onClose() { requestClose(parent); }
        @Override public boolean isPauseScreen() { return false; }
    }
}
