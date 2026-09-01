package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/** Selects one structure variant or leaves a candidate search unfiltered. */
final class StructureVariantPickerScreen extends ConfluxScreen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int MAX_BUTTON_WIDTH = 180;

    private final Screen parent;
    private final StructureIndex.StructureType type;
    private final OptionalInt selected;
    private final Consumer<OptionalInt> onSelect;

    StructureVariantPickerScreen(
        final Screen parent,
        final StructureIndex.StructureType type,
        final OptionalInt selected,
        final Consumer<OptionalInt> onSelect
    ) {
        super(Texts.translatable("confluxmap.screen.structure_candidates.choose_variant"));
        this.parent = parent;
        this.type = type;
        this.selected = selected;
        this.onSelect = onSelect;
    }

    static List<OptionalInt> options(final StructureIndex.StructureType type) {
        final List<OptionalInt> options = new ArrayList<>();
        options.add(OptionalInt.empty());
        for (final int variant : type.variantCodes()) {
            options.add(OptionalInt.of(variant));
        }
        return List.copyOf(options);
    }

    static String labelKey(
        final StructureIndex.StructureType type,
        final OptionalInt variant
    ) {
        return variant.isPresent()
            ? type.variantTranslationKey(variant.getAsInt())
            : "confluxmap.screen.structure_candidates.all_variants";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final List<OptionalInt> options = options(type);
        final int availableRows = Math.max(1, (height - 80) / (BUTTON_HEIGHT + GAP));
        final int columns = Math.max(1, (options.size() + availableRows - 1) / availableRows);
        final int rows = (options.size() + columns - 1) / columns;
        final int buttonWidth = Math.min(
            MAX_BUTTON_WIDTH,
            Math.max(1, (width - 24 - (columns - 1) * GAP) / columns)
        );
        final int gridWidth = columns * buttonWidth + (columns - 1) * GAP;
        final int gridLeft = (width - gridWidth) / 2;
        for (int index = 0; index < options.size(); index++) {
            final OptionalInt option = options.get(index);
            final String name = Texts.translatable(labelKey(type, option)).getString();
            final String prefix = option.equals(selected) ? "\u2713 " : "";
            final int column = index / rows;
            final int row = index % rows;
            addDrawableChild(Widgets.button(
                gridLeft + column * (buttonWidth + GAP),
                40 + row * (BUTTON_HEIGHT + GAP),
                buttonWidth,
                BUTTON_HEIGHT,
                Texts.literal(prefix + name),
                ignored -> select(option)
            ));
        }
        addDrawableChild(Widgets.button(
            width / 2 - 50,
            height - 28,
            100,
            BUTTON_HEIGHT,
            Texts.translatable("confluxmap.screen.structure_search.back"),
            ignored -> onClose()
        ));
    }

    private void select(final OptionalInt variant) {
        onSelect.accept(variant);
        onClose();
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = getTitle().getString();
        draw.drawTextWithShadow(
            this.textRenderer,
            title,
            width / 2f - this.textRenderer.getWidth(title) / 2f,
            16,
            0xFFFFFFFF
        );
    }
}
