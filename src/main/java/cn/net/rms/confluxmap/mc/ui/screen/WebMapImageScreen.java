package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.net.WebMapClient;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/** Selects only photos already owned by this waypoint album. */
final class WebMapImageScreen extends ConfluxScreen {
    private final Screen parent;
    private final WaypointMediaService.Key album;
    private final Consumer<List<UUID>> confirmed;
    private final LinkedHashSet<UUID> selection;
    private final boolean uploadHere;
    private boolean submitted;
    private List<WaypointMediaService.Photo> photos = List.of();
    private WaypointPhotoTexture preview;
    private int index;
    private int generation;
    private String notice = "";

    WebMapImageScreen(Screen parent, WaypointMediaService.Key album, List<UUID> selected,
                      Consumer<List<UUID>> confirmed) {
        this(parent, album, selected, confirmed, false);
    }

    WebMapImageScreen(Screen parent, WaypointMediaService.Key album, List<UUID> selected,
                      Consumer<List<UUID>> confirmed, boolean uploadHere) {
        super(Texts.translatable("confluxmap.webmap.select_photos"));
        this.parent = parent;
        this.album = album;
        this.selection = new LinkedHashSet<>(selected);
        this.confirmed = confirmed;
        this.uploadHere = uploadHere;
    }

    @Override
    protected void init() {
        rebuild();
        final int request = ++generation;
        ConfluxMapClient.get().waypointMediaService().list(album).whenComplete((list, error) ->
            MinecraftClient.getInstance().execute(() -> {
                if (generation != request) return;
                if (error != null) {
                    notice = Texts.translatable("confluxmap.webmap.photo_failed").getString();
                    return;
                }
                photos = list;
                selection.removeIf(id -> photos.stream().noneMatch(photo -> photo.id().equals(id)));
                show(Math.min(index, Math.max(0, photos.size() - 1)));
            }));
    }

    private void rebuild() {
        clearChildren();
        final int center = width / 2;
        final int controls = height - 52;
        final boolean hasPhoto = !photos.isEmpty();
        addDrawableChild(Widgets.button(center - 112, controls, 30, 20, Texts.literal("<"),
            button -> show(index - 1))).active = index > 0;
        addDrawableChild(Widgets.button(center + 82, controls, 30, 20, Texts.literal(">"),
            button -> show(index + 1))).active = index + 1 < photos.size();
        final UUID photo = hasPhoto ? photos.get(index).id() : null;
        final boolean chosen = selection.contains(photo);
        addDrawableChild(Widgets.button(center - 77, controls, 154, 20,
            Texts.translatable(chosen ? "confluxmap.webmap.photo_deselect" : "confluxmap.webmap.photo_select",
                selection.size()), ignored -> {
                    if (!selection.remove(photo)) selection.add(photo);
                    rebuild();
                })).active = hasPhoto && (chosen || selection.size() < 4);
        addDrawableChild(Widgets.button(center - 104, height - 28, 100, 20,
            Texts.translatable(uploadHere ? "confluxmap.webmap.image_upload" : "confluxmap.screen.waypoint.done"),
            button -> {
                if (uploadHere && (selection.isEmpty() || WebMapClient.uploadBusy())) return;
                confirmed.accept(List.copyOf(selection));
                if (uploadHere) {
                    submitted = true;
                    selection.clear();
                    rebuild();
                } else {
                    onClose();
                }
            })).active = !uploadHere || !selection.isEmpty();
        addDrawableChild(Widgets.button(center + 4, height - 28, 100, 20,
            Texts.translatable("confluxmap.screen.waypoint.cancel"), button -> onClose()));
    }

    private void show(int next) {
        index = next;
        closePreview();
        rebuild();
        if (photos.isEmpty()) {
            notice = Texts.translatable("confluxmap.webmap.no_saved_photos").getString();
            return;
        }
        final var photo = photos.get(index);
        final int request = ++generation;
        notice = photo.caption();
        ConfluxMapClient.get().waypointMediaService().pixels(album, photo.id(), false)
            .whenComplete((pixels, error) -> MinecraftClient.getInstance().execute(() -> {
                if (generation != request) return;
                if (error == null) preview = new WaypointPhotoTexture(pixels);
                else notice = Texts.translatable("confluxmap.webmap.photo_failed").getString();
            }));
    }

    @Override
    protected void renderContents(GuiDraw draw, int mouseX, int mouseY, float delta) {
        draw.renderBackground(this, mouseX, mouseY, delta);
        center(draw, getTitle().getString(), 9);
        if (preview != null) {
            preview.draw(draw, 12, 26, width - 24, Math.max(1, height - 99), 1,
                0, 0, photos.get(index).rotation());
        }
        center(draw, submitted ? WebMapClient.result() : notice, height - 65);
    }

    private void center(GuiDraw draw, String text, int y) {
        String visible = this.textRenderer.trimToWidth(text, Math.max(0, width - 24));
        draw.drawTextWithShadow(this.textRenderer, visible,
            (width - this.textRenderer.getWidth(visible)) / 2, y, 0xFFFFFFFF);
    }

    private void closePreview() {
        if (preview != null) {
            preview.close();
            preview = null;
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() { requestClose(parent); }

    @Override public void removed() {
        ++generation;
        closePreview();
        super.removed();
    }
}
