package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.waypoint.WaypointDescription;
import cn.net.rms.confluxmap.core.waypoint.image.WaypointDescriptionTemplates;
import cn.net.rms.confluxmap.mc.net.waypointimage.WaypointImageClient;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.TextureBinds;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.widget.MultilineTextField;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Standalone notes editor behind the edit screen's "Notes…" corner button:
 * a multiline description field, a 48px preview thumbnail, screenshot and
 * paste capture, template insertion, and the completion callback that hands
 * (description, imageId) back to the editor draft.
 */
public final class WaypointDescriptionScreen extends ConfluxScreen {
    private static final int WIDTH = 320;
    private static final int THUMBNAIL = 48;
    private static final int FIELD_LINES = 6;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MARGIN = 8;

    private final Screen parent;
    private final BiConsumer<String, String> onResult;
    private final String initialDescription;
    private final String initialImageId;
    private MultilineTextField notes;
    private String imageId;
    private boolean templatePopupOpen;
    private String errorMessage;
    private int left;
    private int top;
    private net.minecraft.client.util.math.MatrixStack matrices;

    public WaypointDescriptionScreen(
        final Screen parent,
        final String initialDescription,
        final String initialImageId,
        final BiConsumer<String, String> onResult
    ) {
        super(Texts.translatable("confluxmap.screen.waypoint.notes.title"));
        this.parent = parent;
        this.initialDescription = initialDescription == null ? "" : initialDescription;
        this.initialImageId = initialImageId == null ? "" : initialImageId;
        this.imageId = this.initialImageId;
        this.onResult = onResult;
    }

    @Override
    protected void init() {
        notes = new MultilineTextField(this.textRenderer);
        notes.setText(initialDescription);
        left = (this.width - WIDTH) / 2;
        top = Math.max(20, (this.height - contentHeight()) / 2);
        notes.setGeometry(left + MARGIN, notesTop(), WIDTH - 2 * MARGIN, notesHeight());

        final int actionY = notesTop() + notesHeight() + 4;
        final int buttonWidth = (WIDTH - 2 * MARGIN - 3 * 4) / 4;
        addDrawableChild(Widgets.button(
            left + MARGIN, actionY, buttonWidth, BUTTON_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.notes.screenshot"),
            button -> WaypointCaptureCoordinator.begin(
                notes.text(), imageId,
                (description, newImageId) -> MinecraftAccess.setScreen(
                    MinecraftClient.getInstance(),
                    new WaypointDescriptionScreen(parent, description, newImageId, onResult)
                )
            )
        ));
        addDrawableChild(Widgets.button(
            left + MARGIN + (buttonWidth + 4), actionY, buttonWidth, BUTTON_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.notes.paste"),
            button -> pasteFromClipboard()
        ));
        addDrawableChild(Widgets.button(
            left + MARGIN + (buttonWidth + 4) * 2, actionY, buttonWidth, BUTTON_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.notes.templates"),
            button -> templatePopupOpen = !templatePopupOpen
        ));
        addDrawableChild(Widgets.button(
            left + MARGIN + (buttonWidth + 4) * 3, actionY, buttonWidth, BUTTON_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.notes.clear"),
            button -> {
                notes.setText("");
                imageId = "";
            }
        ));

        final int footerY = thumbnailTop() + THUMBNAIL + 8;
        addDrawableChild(Widgets.button(
            left + WIDTH / 2 - 102, footerY, 100, BUTTON_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.notes.done"),
            button -> complete()
        ));
        addDrawableChild(Widgets.button(
            left + WIDTH / 2 + 2, footerY, 100, BUTTON_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.notes.cancel"),
            button -> requestClose(parent)
        ));
    }

    private int notesTop() {
        return top + 24;
    }

    private int notesHeight() {
        return FIELD_LINES * (this.textRenderer.fontHeight + 2) + 8;
    }

    private int thumbnailTop() {
        return notesTop() + notesHeight() + 4 + BUTTON_HEIGHT + 4;
    }

    private int contentHeight() {
        return 24 + notesHeight() + 4 + BUTTON_HEIGHT + 4 + THUMBNAIL + 8 + BUTTON_HEIGHT + 8;
    }

    private void pasteFromClipboard() {
        final WaypointImageClient images = ConfluxMapClient.get().waypointImages();
        if (images == null) {
            errorMessage = "confluxmap.screen.waypoint.notes.error.paste";
            return;
        }
        images.pasteFromClipboard((width, height, argb) -> {
            if (argb == null) {
                errorMessage = "confluxmap.screen.waypoint.notes.error.paste";
                return;
            }
            final UUID newImageId = UUID.randomUUID();
            images.seedDraftTexture(newImageId.toString(), width, height, argb);
            images.uploadDraftImage(newImageId, argb, width, height, status -> {});
            imageId = newImageId.toString();
            errorMessage = null;
        });
    }

    private void insertTemplate(final WaypointDescriptionTemplates.Template template) {
        final String label = clientLanguageChinese() ? template.zh() : template.en();
        final MultilineTextField.State state = notes.state();
        for (final char chr : label.toCharArray()) {
            state.insert(chr, WaypointDescription.MAX_CODE_POINTS);
        }
        state.insert(' ', WaypointDescription.MAX_CODE_POINTS);
        templatePopupOpen = false;
        notes.setFocused(true);
    }

    private static boolean clientLanguageChinese() {
        return MinecraftAccess.language(MinecraftClient.getInstance()).startsWith("zh");
    }

    private static String labelFor(final WaypointDescriptionTemplates.Template template) {
        return clientLanguageChinese() ? template.zh() : template.en();
    }

    private void complete() {
        try {
            onResult.accept(
                WaypointDescription.normalize(notes.text()),
                WaypointDescription.normalizeImageId(imageId)
            );
        } catch (final IllegalArgumentException e) {
            errorMessage = "confluxmap.screen.waypoint.notes.error.invalid";
        }
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        matrices = draw.matrices();
        draw.drawTextWithShadow(this.textRenderer, title, left + MARGIN, top + 8, 0xFFFFFF);
        notes.render(draw, draw.matrices(), 0xFFFFFF);
        drawThumbnail(draw, mouseX, mouseY);
        if (templatePopupOpen) {
            drawTemplatePopup(draw);
        }
        if (errorMessage != null) {
            draw.drawTextWithShadow(
                this.textRenderer,
                Texts.translatable(errorMessage),
                left + MARGIN, top + contentHeight() - 2, 0xFFFF5555
            );
        }
    }

    private void drawThumbnail(final GuiDraw draw, final int mouseX, final int mouseY) {
        final int x = left + MARGIN;
        final int y = thumbnailTop();
        GuiShapes.fillRect(matrices, x - 1, y - 1, THUMBNAIL + 2, THUMBNAIL + 2, 0xFF8B8B8B);
        final WaypointImageClient images = ConfluxMapClient.get().waypointImages();
        final java.util.Optional<WaypointImageClient.Thumbnail> thumbnail =
            images == null || imageId.isEmpty()
                ? java.util.Optional.empty()
                : images.thumbnail(imageId);
        if (thumbnail.isPresent()) {
            bindThumbnail(thumbnail.get().texture());
            TextureBinds.beginTexturedQuads();
            GuiShapes.drawQuad(matrices, x, y, THUMBNAIL, THUMBNAIL, 0f, 0f, 1f, 1f);
        } else {
            GuiShapes.fillRect(matrices, x, y, THUMBNAIL, THUMBNAIL, 0xFF3F3F3F);
            if (!imageId.isEmpty()) {
                draw.drawTextWithShadow(
                    this.textRenderer,
                    Texts.translatable("confluxmap.screen.waypoint.notes.loading"),
                    x + 4, y + THUMBNAIL / 2 - 4, 0xFFAAAAAA
                );
            }
        }
        final int labelX = x + THUMBNAIL + 8;
        int row = 0;
        for (final String line : wrappedNotes()) {
            if (row >= FIELD_LINES + 2) {
                break;
            }
            draw.drawTextWithShadow(
                this.textRenderer, line, labelX, y + 2 + row * (this.textRenderer.fontHeight + 2), 0xFFDDDDDD
            );
            row++;
        }
    }

    private void bindThumbnail(final NativeImageBackedTexture texture) {
        //#if MC>=12108
        //$$ TextureBinds.bindTexture(texture.getGlTextureView());
        //#elseif MC>=12105
        //$$ TextureBinds.bindTexture(texture.getGlTexture());
        //#else
        TextureBinds.bindTexture(texture.getGlId());
        //#endif
    }

    private List<String> wrappedNotes() {
        final List<String> wrapped = new ArrayList<>();
        for (final String line : notes.text().split("\n", -1)) {
            for (final net.minecraft.text.OrderedText ordered
                : this.textRenderer.wrapLines(Texts.literal(line), WIDTH - 2 * MARGIN - THUMBNAIL - 8)) {
                final StringBuilder out = new StringBuilder();
                ordered.accept((index, style, codePoint) -> {
                    out.appendCodePoint(codePoint);
                    return true;
                });
                wrapped.add(out.toString());
            }
        }
        return wrapped;
    }

    private int popupX() {
        final int buttonWidth = (WIDTH - 2 * MARGIN - 3 * 4) / 4;
        return left + MARGIN + (buttonWidth + 4) * 2;
    }

    private int popupY() {
        return notesTop() + notesHeight() + 4 + BUTTON_HEIGHT + 2;
    }

    private void drawTemplatePopup(final GuiDraw draw) {
        final List<WaypointDescriptionTemplates.Template> templates =
            WaypointDescriptionTemplates.builtIn();
        final int popupX = popupX();
        final int popupY = popupY();
        final int height = templates.size() * (BUTTON_HEIGHT + 2) + 2;
        GuiShapes.fillRect(matrices, popupX, popupY, 110, height, 0xFF8B8B8B);
        GuiShapes.fillRect(matrices, popupX + 1, popupY + 1, 108, height - 2, 0xE0000000 | 0x1C6BA0);
        for (int i = 0; i < templates.size(); i++) {
            draw.drawTextWithShadow(
                this.textRenderer, labelFor(templates.get(i)), popupX + 6,
                popupY + 4 + i * (BUTTON_HEIGHT + 2), 0xFFFFFF
            );
        }
    }

    //#if MC>=12109
    //$$ @Override
    //$$ public boolean mouseClicked(final net.minecraft.client.gui.Click click, final boolean doubledClick) {
    //$$     if (handleClick(click.x(), click.y(), click.button())) {
    //$$         return true;
    //$$     }
    //$$     return super.mouseClicked(click, doubledClick);
    //$$ }
    //#else
    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (handleClick(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //#endif

    private boolean handleClick(final double mouseX, final double mouseY, final int button) {
        if (button != 0) {
            return false;
        }
        if (templatePopupOpen) {
            final List<WaypointDescriptionTemplates.Template> templates =
                WaypointDescriptionTemplates.builtIn();
            final int popupX = popupX();
            final int popupY = popupY();
            final int popupHeight = templates.size() * (BUTTON_HEIGHT + 2) + 2;
            if (mouseX >= popupX && mouseX < popupX + 110
                && mouseY >= popupY && mouseY < popupY + popupHeight) {
                final int index = (int) ((mouseY - popupY - 2) / (BUTTON_HEIGHT + 2));
                if (index >= 0 && index < templates.size()) {
                    insertTemplate(templates.get(index));
                    return true;
                }
            }
            templatePopupOpen = false;
        }
        return notes.mouseClicked(mouseX, mouseY, button);
    }

    //#if MC>=12109
    //$$ @Override
    //$$ public boolean keyPressed(final net.minecraft.client.input.KeyInput input) {
    //$$     if (notes.keyPressed(input.key())) {
    //$$         return true;
    //$$     }
    //$$     return super.keyPressed(input);
    //$$ }
    //#else
    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (notes.keyPressed(keyCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //#endif

    //#if MC>=260100
    //$$ @Override
    //$$ public boolean charTyped(final net.minecraft.client.input.CharacterEvent input) {
    //$$     if (notes.insertString(input.codepointAsString())) {
    //$$         return true;
    //$$     }
    //$$     return super.charTyped(input);
    //$$ }
    //#elseif MC>=12109
    //$$ @Override
    //$$ public boolean charTyped(final net.minecraft.client.input.CharInput input) {
    //$$     if (notes.insertString(new String(Character.toChars(input.codepoint())))) {
    //$$         return true;
    //$$     }
    //$$     return super.charTyped(input);
    //$$ }
    //#else
    @Override
    public boolean charTyped(final char chr, final int modifiers) {
        if (notes.charTyped(chr, modifiers)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }
    //#endif

    //#if MC>=12002
    //$$ @Override
    //$$ public boolean mouseScrolled(
    //$$     final double mouseX,
    //$$     final double mouseY,
    //$$     final double horizontalAmount,
    //$$     final double amount
    //$$ ) {
    //$$     if (notes.mouseScrolled(mouseX, mouseY, amount)) {
    //$$         return true;
    //$$     }
    //$$     return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
    //$$ }
    //#else
    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (notes.mouseScrolled(mouseX, mouseY, amount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }
    //#endif

}
