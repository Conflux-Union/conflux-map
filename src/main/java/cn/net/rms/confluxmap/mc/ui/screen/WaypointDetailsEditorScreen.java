package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointMetadata;
import cn.net.rms.confluxmap.core.waypoint.library.WaypointTag;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Edits a parent form draft; only the parent's final Save writes data. */
final class WaypointDetailsEditorScreen extends ConfluxScreen {
    private final Screen parent;
    private final Consumer<WaypointMetadata> apply;
    private final BooleanSupplier validSession;
    private final WaypointMetadata original;
    private final Set<String> tags;
    private final Set<String> products;
    private final java.util.Map<String, Integer> amounts;
    private TextFieldWidget noteField;
    private String note;
    private int page;
    private cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService.Key mediaKey;

    WaypointDetailsEditorScreen withImages(cn.net.rms.confluxmap.core.waypoint.library.WaypointMediaService.Key key) {
        mediaKey = key;
        return this;
    }

    WaypointDetailsEditorScreen(final Screen parent, final WaypointMetadata draft,
                                final BooleanSupplier validSession, final Consumer<WaypointMetadata> apply) {
        super(Texts.translatable("confluxmap.waypoint_details.title"));
        this.parent = parent;
        this.original = draft;
        this.validSession = validSession;
        this.apply = apply;
        note = draft.note();
        tags = new LinkedHashSet<>(draft.tags());
        products = new LinkedHashSet<>(draft.resourceItems());
        amounts = new java.util.LinkedHashMap<>(draft.resourceAmounts());
    }

    private int formWidth() { return Math.max(180, Math.min(440, width - 32)); }
    private int left() { return (width - formWidth()) / 2; }

    @Override
    protected void init() {
        if (noteField != null) {
            note = noteField.getText();
        }
        clearChildren();
        if (!validSession.getAsBoolean()) {
            return;
        }
        final int x = left();
        final int w = formWidth();
        if (cn.net.rms.confluxmap.ConfluxMapClient.get().waypointListMemory().options().images) addDrawableChild(Widgets.button(x + w - 80, 8, 80, 20, WaypointImagesScreen.label("title"), button -> {
            note = noteField.getText();
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), new WaypointImagesScreen(this, mediaKey, validSession));
        })).active = mediaKey != null;
        noteField = new TextFieldWidget(this.textRenderer, x, 44, w, 20, label("note"));
        noteField.setMaxLength(WaypointMetadata.NOTE_LIMIT);
        noteField.setText(note);
        addDrawableChild(noteField);
        final int cell = (w - 8) / 3;
        final WaypointTag[] presets = WaypointTag.values();
        for (int i = 0; i < presets.length; i++) {
            final WaypointTag tag = presets[i];
            addDrawableChild(Widgets.button(x + (i % 3) * (cell + 4), 80 + (i / 3) * 24, cell, 20,
                Texts.literal((tags.contains(tag.id()) ? "[x] " : "[ ] ") + Texts.translatable(tag.translationKey()).getString()),
                button -> {
                    if (!tags.remove(tag.id())) {
                        tags.add(tag.id());
                    }
                    init();
                })).active = tag != WaypointTag.RESOURCE || products.isEmpty();
        }
        final boolean resource = tags.contains(WaypointTag.RESOURCE.id());
        addDrawableChild(Widgets.button(x, 130, w, 20, label("add_product"), button -> {
            note = noteField.getText();
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), WaypointIconPickerScreen.forResources(this, item -> {
                if (validSession.getAsBoolean()) {
                    products.add(item);
                }
            }));
        })).active = resource;
        final List<String> items = new ArrayList<>(products);
        final int capacity = Math.max(1, (height - 218) / 22);
        final int pages = Math.max(1, (items.size() + capacity - 1) / capacity);
        page = Math.min(page, pages - 1);
        for (int i = page * capacity; i < Math.min(items.size(), (page + 1) * capacity); i++) {
            final String id = items.get(i);
            final int y = 154 + (i - page * capacity) * 22;
            final String name = this.textRenderer.trimToWidth(WaypointDetailsText.itemName(id), w - 42);
            addDrawableChild(Widgets.button(x, y, w, 20, Texts.literal(name + "  [x]"), button -> {
                products.remove(id);
                amounts.remove(id);
                init();
            }));
        }
        addDrawableChild(Widgets.button(x, height - 52, 60, 20, label("previous"), button -> { page--; init(); }))
            .active = page > 0;
        addDrawableChild(Widgets.button(x + 64, height - 52, 60, 20, label("next"), button -> { page++; init(); }))
            .active = page + 1 < pages;
        addDrawableChild(Widgets.button(x, height - 28, (w - 4) / 2, 20, label("apply"), button -> {
            if (validSession.getAsBoolean()) {
                apply.accept(new WaypointMetadata(noteField.getText(), tags, original.starred(), original.collections(), products, amounts));
                requestClose(parent);
            }
        }));
        addDrawableChild(Widgets.button(x + (w + 4) / 2, height - 28, (w - 4) / 2, 20,
            Texts.translatable("confluxmap.screen.waypoint.cancel"), button -> onClose()));
        setInitialFocus(noteField);
    }

    private static Text label(final String key) {
        return Texts.translatable("confluxmap.waypoint_details." + key);
    }

    @Override
    public void tick() {
        super.tick();
        if (!validSession.getAsBoolean()) {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), null);
            return;
        }
        Widgets.tick(noteField);
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float delta) {
        draw.renderBackground(this, mouseX, mouseY, delta);
        draw.drawTextWithShadow(this.textRenderer, getTitle().getString(), left(), 14, 0xFFFFFFFF);
        if (mediaKey == null && cn.net.rms.confluxmap.ConfluxMapClient.get().waypointListMemory().options().images)
            draw.drawTextWithShadow(this.textRenderer, WaypointImagesScreen.label("save_first").getString(), left(), height - 62, 0xFFBBBBBB);
        draw.drawTextWithShadow(this.textRenderer, label("note").getString(), left(), 32, 0xFFCCCCCC);
        draw.drawTextWithShadow(this.textRenderer, label("presets").getString(), left(), 68, 0xFFCCCCCC);
        final String hint = this.textRenderer.trimToWidth(label("save_hint").getString(), Math.max(10, formWidth() - 132));
        draw.drawTextWithShadow(this.textRenderer, hint, left() + 132, height - 46, 0xFFBBBBBB);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { requestClose(parent); }
}
