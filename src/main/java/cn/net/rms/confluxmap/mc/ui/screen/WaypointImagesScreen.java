package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Private image gallery. Media operations are explicitly saved immediately, independently of form drafts. */
final class WaypointImagesScreen extends PhotoViewerScreen {
    private final Screen parent;
    private final WaypointMediaService.Key key;
    private final BooleanSupplier valid;
    private final WaypointMediaService media = ConfluxMapClient.get().waypointMediaService();
    private List<WaypointMediaService.Photo> photos = List.of();
    private TextFieldWidget caption;
    private WaypointPhotoTexture texture;
    private boolean busy, removed, confirmRemove;
    private int index, generation;
    private String status = "";
    private UUID captionPhoto;
    private String captionDraft = "";

    WaypointImagesScreen(Screen parent, WaypointMediaService.Key key, BooleanSupplier valid) {
        super(waypointTitle(key)); this.parent = parent; this.key = key; this.valid = valid;
    }
    static Text label(String name) { return Texts.translatable("confluxmap.photos." + name); }
    private static Text waypointTitle(WaypointMediaService.Key key) {
        var app = ConfluxMapClient.get();
        var model = key.shared() ? app.sharedWaypointListModel() : app.localWaypointListModel();
        var row = model.rowById(key.waypoint());
        return row == null ? label("title") : Texts.translatable("confluxmap.photos.waypoint_title", row.name());
    }

    private boolean current() {
        return !removed && ConfluxMapClient.get().waypointListMemory().options().images && valid.getAsBoolean() && key.world().equals(ConfluxMapClient.get().waypointLibraryService().world());
    }
    @Override protected void init() {
        removed = false;
        if (busy) controls();
        else refresh(selectedPhoto(), index);
    }
    private void controls() {
        UUID nextPhoto = selectedPhoto();
        captionDraft = caption != null && java.util.Objects.equals(captionPhoto, nextPhoto)
            ? caption.getText() : photos.isEmpty() ? "" : photos.get(index).caption();
        captionPhoto = nextPhoto;
        clearChildren();
        int left = 16, w = Math.max(180, width - 32);
        addDrawableChild(Widgets.button(left, 30, 88, 20, label("import"), b -> choose())).active = !busy;
        addDrawableChild(Widgets.button(left + 92, 30, 44, 20, Texts.literal("-"), b -> zoom(-1)));
        addDrawableChild(Widgets.button(left + 140, 30, 44, 20, Texts.literal("+"), b -> zoom(1)));
        addDrawableChild(Widgets.button(left + 188, 30, Math.max(44, Math.min(100, width - left * 2 - 188)), 20,
            label("paste"), b -> paste())).active = !busy;
        addDrawableChild(Widgets.button(left, height - 78, 40, 20, Texts.literal("<"), b -> changePage(index - 1))).active = !busy && index > 0;
        addDrawableChild(Widgets.button(left + 44, height - 78, 40, 20, Texts.literal(">"), b -> changePage(index + 1))).active = !busy && index + 1 < photos.size();
        addDrawableChild(Widgets.button(left + 92, height - 78, 64, 20, label("earlier"), b -> edit(-1, false))).active = !busy && index > 0;
        addDrawableChild(Widgets.button(left + 160, height - 78, 64, 20, label("later"), b -> edit(1, false))).active = !busy && index + 1 < photos.size();
        caption = new TextFieldWidget(this.textRenderer, left, height - 54, Math.max(80, w - 140), 20, label("caption"));
        caption.setMaxLength(512); caption.setText(captionDraft);
        caption.setEditable(!busy);
        addDrawableChild(caption);
        addDrawableChild(Widgets.button(width - 152, height - 54, 64, 20, label("save"), b -> edit(0, false))).active = !busy && !photos.isEmpty();
        addDrawableChild(Widgets.button(width - 84, height - 54, 68, 20, label(confirmRemove ? "confirm" : "remove"), b -> {
            if (confirmRemove) edit(0, true); else { confirmRemove = true; controls(); }
        })).active = !busy && !photos.isEmpty();
        //#if MC>=260100
        //$$ if (gesturesEnabled()) {
        //$$     addRenderableWidget(Widgets.button(width - 118, 6, 102, 20, label("save_rotation"),
        //$$         b -> saveRotation())).active = !busy && !photos.isEmpty();
        //$$ }
        //#else
        if (gesturesEnabled()) {
            addDrawableChild(Widgets.button(width - 118, 6, 102, 20, label("save_rotation"),
                b -> saveRotation())).active = !busy && !photos.isEmpty();
        }
        //#endif
        addDrawableChild(Widgets.button(width - 96, height - 28, 80, 20, Texts.translatable("gui.done"), b -> onClose()));
    }
    private UUID selectedPhoto() {
        return photos.isEmpty() ? null : photos.get(index).id();
    }

    private void refresh(UUID preferred, int fallbackIndex) {
        if (!current()) return;
        busy = true; controls();
        media.list(key).whenComplete((next, error) -> MinecraftClient.getInstance().execute(() -> {
            if (!current()) return;
            busy = false;
            if (error != null) {
                fail(error);
                return;
            }
            int nextIndex = Math.max(0, Math.min(fallbackIndex, next.size() - 1));
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i).id().equals(preferred)) {
                    nextIndex = i;
                    break;
                }
            }
            photos = next;
            select(nextIndex);
        }));
    }
    private void select(int next) {
        index = Math.max(0, Math.min(next, photos.size() - 1)); confirmRemove = false;
        resetView(photos.isEmpty() ? 0 : photos.get(index).rotation());
        loadTexture();
    }

    private void changePage(int next) {
        if (busy || isClosing() || !current() || next < 0 || next >= photos.size() || next == index) return;
        final var photo = photos.get(index);
        final String text = caption.getText();
        if (text.equals(photo.caption())) {
            select(next);
            return;
        }
        busy = true;
        controls();
        media.edit(key, photo.id(), text, 0, false).whenComplete((ignored, error) ->
            MinecraftClient.getInstance().execute(() -> {
                if (!current()) return;
                busy = false;
                if (error != null) {
                    fail(error);
                    return;
                }
                final var updated = new ArrayList<>(photos);
                updated.set(index, new WaypointMediaService.Photo(photo.id(), photo.extension(), text,
                    photo.width(), photo.height(), photo.rotation()));
                photos = List.copyOf(updated);
                status = "";
                select(next);
            }));
    }

    private void loadTexture() {
        int request = ++generation;
        release(); controls();
        if (photos.isEmpty()) return;
        media.pixels(key, photos.get(index).id(), false).whenComplete((pixels, error) -> MinecraftClient.getInstance().execute(() -> {
            if (!current() || request != generation) return;
            if (error != null) fail(error); else texture = new WaypointPhotoTexture(pixels);
        }));
    }
    private void edit(int move, boolean remove) {
        if (!current() || busy || photos.isEmpty()) return;
        UUID photo = selectedPhoto();
        String text = caption.getText(); busy = true; controls();
        media.edit(key, photo, text, move, remove).whenComplete((ignored, error) -> MinecraftClient.getInstance().execute(() -> {
            if (!current()) return;
            busy = false;
            if (error != null) fail(error); else refresh(remove ? null : photo, index);
        }));
    }
    //#if MC>=260100
    //$$ @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
    //$$     if (caption == null || !caption.isFocused()) {
    //$$         if (input.isPaste()) { paste(); return true; }
    //$$         if (input.isLeft() || input.isRight()) {
    //$$             int next = index + (input.isLeft() ? -1 : 1);
    //$$             changePage(next);
    //$$             return true;
    //$$         }
    //$$     }
    //$$     return super.keyPressed(input);
    //$$ }
    //#elseif MC>=12109
    //$$ @Override public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
    //$$     if (caption == null || !caption.isFocused()) {
    //$$         if (input.isPaste()) { paste(); return true; }
    //$$         if (input.isLeft() || input.isRight()) {
    //$$             int next = index + (input.isLeft() ? -1 : 1);
    //$$             changePage(next);
    //$$             return true;
    //$$         }
    //$$     }
    //$$     return super.keyPressed(input);
    //$$ }
    //#else
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (caption == null || !caption.isFocused()) {
            if (isPaste(keyCode)) { paste(); return true; }
            if (keyCode == cn.net.rms.confluxmap.compat.Keys.LEFT || keyCode == cn.net.rms.confluxmap.compat.Keys.RIGHT) {
                int next = index + (keyCode == cn.net.rms.confluxmap.compat.Keys.LEFT ? -1 : 1);
                changePage(next);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //#endif

    private void saveRotation() {
        if (busy || !current() || photos.isEmpty() || !rotationChanged()) return;
        busy = true;
        controls();
        media.saveRotation(key, selectedPhoto(), rotation()).whenComplete((photo, error) ->
            net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                if (!current()) return;
                busy = false;
                if (error != null) {
                    fail(error);
                    return;
                }
                var updated = new ArrayList<>(photos);
                updated.set(index, photo);
                photos = List.copyOf(updated);
                markRotationSaved(photo.rotation());
                status = "";
                controls();
            }));
    }

    private void paste() {
        if (!current() || busy || isClosing()) return;
        busy = true; controls();
        CompletableFuture.supplyAsync(() -> {
            java.nio.file.Path path = null;
            try {
                path = java.nio.file.Files.createTempFile("confluxmap-clipboard-", ".png");
                MapExportClipboardProcess.paste(path);
                return path;
            } catch (java.io.IOException error) {
                cleanPasteFile(path);
                throw new java.util.concurrent.CompletionException(error);
            } catch (InterruptedException error) {
                cleanPasteFile(path);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(error);
            }
        }).whenComplete((path, error) -> MinecraftClient.getInstance().execute(() -> {
            if (!current() || isClosing()) { cleanPasteFile(path); return; }
            if (error != null) {
                busy = false; status = label("paste_failed").getString(); controls();
                return;
            }
            media.add(key, path).whenComplete((photo, failure) -> {
                cleanPasteFile(path);
                MinecraftClient.getInstance().execute(() -> {
                    if (!current() || isClosing()) return;
                    busy = false;
                    if (failure != null) fail(failure);
                    else refreshAfterAdd(photo);
                });
            });
        }));
    }

    private static void cleanPasteFile(java.nio.file.Path path) {
        if (path == null) return;
        try { java.nio.file.Files.deleteIfExists(path); }
        catch (java.io.IOException error) {
            cn.net.rms.confluxmap.ConfluxMapMod.LOGGER.warn("Could not remove temporary pasted image", error);
        }
    }

    private void choose() {
        if (!current() || busy) return;
        busy = true; controls();
        WaypointImageFilePicker.choose(this::importFiles, error -> {
            if (current()) { busy = false; fail(error); }
        });
    }
    @Override public void filesDragged(List<java.nio.file.Path> paths) {
        if (current() && !busy) { busy = true; controls(); importFiles(paths); }
    }
    private void importFiles(List<java.nio.file.Path> paths) {
        if (!current()) return;
        CompletableFuture<WaypointMediaService.Photo> chain = CompletableFuture.completedFuture(null);
        for (var path : paths) chain = chain.thenCompose(ignored -> media.add(key, path));
        chain.whenComplete((photo, error) -> MinecraftClient.getInstance().execute(() -> {
            if (!current()) return;
            if (error != null) {
                fail(error);
                refresh(selectedPhoto(), index);
            } else {
                refreshAfterAdd(photo);
            }
        }));
    }
    private void refreshAfterAdd(WaypointMediaService.Photo photo) {
        status = "";
        refresh(photo == null ? selectedPhoto() : photo.id(), index);
    }
    private void fail(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        status = error.getMessage() == null ? error.toString() : error.getMessage(); controls();
    }
    @Override public void tick() {
        super.tick();
        if (isClosing() || MinecraftAccess.screen(MinecraftClient.getInstance()) != this) return;
        if (!current()) onClose();
        else { Widgets.tick(caption); if (!busy && texture != null && texture.closed()) loadTexture(); }
    }
    @Override protected void renderContents(GuiDraw draw, int mx, int my, float delta) {
        draw.renderBackground(this, mx, my, delta);
        int titleWidth = width - 32;
        if (gesturesEnabled()) titleWidth -= 108;
        String pages = photos.isEmpty() ? "0/0" : (index + 1) + "/" + photos.size();
        String title = this.textRenderer.trimToWidth(getTitle().getString(),
            Math.max(0, titleWidth - this.textRenderer.getWidth("  " + pages)));
        draw.drawTextWithShadow(this.textRenderer, title + "  " + pages, 16, 12, 0xFFFFFFFF);
        if (texture != null) drawPhoto(draw, texture, 16, 56, Math.max(1, width - 32), Math.max(1, height - 140));
        else draw.drawTextWithShadow(this.textRenderer, label(busy || !photos.isEmpty() ? "loading" : "empty").getString(), 16, 64, 0xFFBBBBBB);
        draw.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(status.isEmpty() ? label("private_hint").getString() : status, Math.max(1, width - 124)), 16, height - 22, status.isEmpty() ? 0xFFBBBBBB : 0xFFFF8888);
    }
    @Override protected boolean imageReady() { return !busy && texture != null && !texture.closed(); }
    private void release() { if (texture != null) { texture.close(); texture = null; } }
    @Override public void removed() { removed = true; generation++; release(); super.removed(); }
    @Override public void onClose() { requestClose(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
