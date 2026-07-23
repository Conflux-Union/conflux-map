package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointClientState;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointSet;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#endif
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * Create/edit form for one waypoint: name, X/Y/Z (raw local coordinates in
 * {@link #dimensionId}; renderers only display it in that exact dimension),
 * an 8-swatch color palette, and a local waypoint-set selector. The dimension itself and
 * the normal/death type are fixed at creation and not editable here, per the
 * implementation brief.
 */
public final class WaypointEditScreen extends ConfluxScreen {
    private enum CreateTarget { LOCAL, PUBLIC, CHAT }

    private static final Pattern NUMERIC = Pattern.compile("[+-]?\\d*(?:\\.\\d*)?(?:[eE][+-]?\\d*)?");
    private static final int[] PRESET_COLORS = {
        0xFFE74C3C, 0xFFE67E22, 0xFFF1C40F, 0xFF2ECC71,
        0xFF1ABC9C, 0xFF3498DB, 0xFF9B59B6, 0xFFECF0F1
    };
    private static final int FIELD_HEIGHT = 20;
    private static final int SWATCH_SIZE = 20;
    private static final int SWATCH_GAP = 6;

    private final Screen parent;
    private final UUID editingId;
    private final DimensionId dimensionId;
    private final Waypoint.Type type;
    private final long createdAtEpochMs;
    private final double initialX;
    private final double initialY;
    private final double initialZ;
    private final String initialName;
    private final String initialGroup;
    private final int initialColor;
    private final boolean initialVisible;
    private final CreateTarget createTarget;
    private final SharedWaypointClient sharedWaypoints;
    private final WaypointStore boundLocalStore;

    private TextFieldWidget nameField;
    private TextFieldWidget xField;
    private TextFieldWidget yField;
    private TextFieldWidget zField;
    private ButtonWidget setButton;
    private ButtonWidget doneButton;
    private List<String> setNames = List.of(WaypointSet.DEFAULT_NAME);
    private int selectedSetIndex;
    private int selectedColor;

    private WaypointEditScreen(
        final Screen parent,
        final UUID editingId,
        final DimensionId dimensionId,
        final Waypoint.Type type,
        final long createdAtEpochMs,
        final String name,
        final double x,
        final double y,
        final double z,
        final int color,
        final String group,
        final boolean visible,
        final CreateTarget createTarget
    ) {
        super(Texts.translatable(titleKey(editingId, createTarget)));
        this.parent = parent;
        this.editingId = editingId;
        this.dimensionId = dimensionId;
        this.type = type;
        this.createdAtEpochMs = createdAtEpochMs;
        this.initialName = name;
        this.initialX = x;
        this.initialY = y;
        this.initialZ = z;
        this.initialColor = color;
        this.initialGroup = group == null ? WaypointSet.DEFAULT_NAME : group;
        this.initialVisible = visible;
        this.createTarget = createTarget;
        this.sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
        this.boundLocalStore = createTarget == CreateTarget.LOCAL
            ? ConfluxMapClient.get().waypointService().current()
            : null;
        this.selectedColor = color;
    }

    /** Prefilled with {@code x/y/z}, taken as-is in the dimension the player is currently viewing/standing in. */
    public static WaypointEditScreen forCreate(
        final Screen parent, final DimensionId dimensionId, final double x, final double y, final double z
    ) {
        return forCreate(parent, dimensionId, x, y, z, WaypointSet.DEFAULT_NAME);
    }

    /** Local create form preselecting one of the current world's waypoint sets. */
    public static WaypointEditScreen forCreate(
        final Screen parent,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final String initialSetName
    ) {
        return new WaypointEditScreen(
            parent, null, dimensionId, Waypoint.Type.NORMAL, System.currentTimeMillis(),
            "", x, y, z, PRESET_COLORS[5], initialSetName, true, CreateTarget.LOCAL
        );
    }

    /** Prefilled import form; saving still requires an explicit user action. */
    public static WaypointEditScreen forCreate(
        final Screen parent,
        final DimensionId dimensionId,
        final String name,
        final double x,
        final double y,
        final double z
    ) {
        return new WaypointEditScreen(
            parent, null, dimensionId, Waypoint.Type.NORMAL, System.currentTimeMillis(),
            name, x, y, z, PRESET_COLORS[5], "", true, CreateTarget.LOCAL
        );
    }

    public static WaypointEditScreen forPublicCreate(
        final Screen parent, final DimensionId dimensionId, final double x, final double y, final double z
    ) {
        return forTarget(parent, dimensionId, x, y, z, CreateTarget.PUBLIC);
    }

    public static WaypointEditScreen forChatCreate(
        final Screen parent, final DimensionId dimensionId, final double x, final double y, final double z
    ) {
        return forTarget(parent, dimensionId, x, y, z, CreateTarget.CHAT);
    }

    private static WaypointEditScreen forTarget(
        final Screen parent,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final CreateTarget target
    ) {
        return new WaypointEditScreen(
            parent, null, dimensionId, Waypoint.Type.NORMAL, System.currentTimeMillis(),
            "", x, y, z, PRESET_COLORS[5], "", true, target
        );
    }

    public static WaypointEditScreen forEdit(final Screen parent, final Waypoint waypoint) {
        return new WaypointEditScreen(
            parent, waypoint.id, waypoint.dimensionId, waypoint.type, waypoint.createdAtEpochMs,
            waypoint.name, waypoint.x, waypoint.y, waypoint.z, waypoint.colorArgb, waypoint.group,
            waypoint.visible, CreateTarget.LOCAL
        );
    }

    private static String titleKey(final UUID editingId, final CreateTarget target) {
        if (editingId != null) {
            return "confluxmap.screen.waypoint.edit";
        }
        return switch (target) {
            case LOCAL -> "confluxmap.screen.waypoint.create";
            case PUBLIC -> "confluxmap.screen.waypoint.create_public";
            case CHAT -> "confluxmap.screen.waypoint.create_chat";
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int centerX = width / 2;
        final int fieldWidth = 200;

        nameField = new TextFieldWidget(textRenderer, centerX - fieldWidth / 2, 42, fieldWidth, FIELD_HEIGHT, Text.of(""));
        nameField.setMaxLength(64);
        nameField.setText(initialName);
        addDrawableChild(nameField);

        final int coordWidth = 62;
        final int coordGap = 7;
        final int coordsLeft = centerX - (coordWidth * 3 + coordGap * 2) / 2;
        xField = numericField(coordsLeft, 78, coordWidth, formatCoord(initialX));
        yField = numericField(coordsLeft + coordWidth + coordGap, 78, coordWidth, formatCoord(initialY));
        zField = numericField(coordsLeft + (coordWidth + coordGap) * 2, 78, coordWidth, formatCoord(initialZ));
        addDrawableChild(xField);
        addDrawableChild(yField);
        addDrawableChild(zField);

        setButton = null;
        if (createTarget == CreateTarget.LOCAL) {
            setNames = localSetNames();
            selectedSetIndex = Math.max(0, setNames.indexOf(initialGroup));
            setButton = addDrawableChild(Widgets.button(
                centerX - fieldWidth / 2,
                114,
                fieldWidth,
                FIELD_HEIGHT,
                selectedSetLabel(),
                button -> selectNextSet()
            ));
        }

        final int totalSwatchWidth = PRESET_COLORS.length * SWATCH_SIZE + (PRESET_COLORS.length - 1) * SWATCH_GAP;
        final int swatchLeft = centerX - totalSwatchWidth / 2;
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            final int color = PRESET_COLORS[i];
            final int x = swatchLeft + i * (SWATCH_SIZE + SWATCH_GAP);
            //#if MC>=11904
            //$$ addDrawableChild(new ButtonWidget(
            //$$     x, 150, SWATCH_SIZE, SWATCH_SIZE, Text.of(""),
            //$$     b -> selectedColor = color, narration -> narration.get()
            //$$ ) {
            //$$     @Override
            //$$     protected void renderWidget(
            //$$         final DrawContext context,
            //$$         final int mouseX,
            //$$         final int mouseY,
            //$$         final float delta
            //$$     ) {
            //$$         renderColorSwatch(GuiDraw.of(context), this, color);
            //$$     }
            //$$ });
            //#else
            addDrawableChild(new ButtonWidget(x, 150, SWATCH_SIZE, SWATCH_SIZE, Text.of(""), b -> selectedColor = color) {
                @Override
                public void renderButton(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
                    renderColorSwatch(GuiDraw.of(matrices), this, color);
                }
            });
            //#endif
        }

        doneButton = addDrawableChild(Widgets.button(
            centerX - 104, height - 32, 100, FIELD_HEIGHT, Texts.translatable("confluxmap.screen.waypoint.done"), b -> onDone()
        ));
        if (createTarget == CreateTarget.LOCAL) {
            doneButton.active = boundLocalStore != null && boundLocalStore.persistenceWritable();
        }
        addDrawableChild(Widgets.button(
            centerX + 4, height - 32, 100, FIELD_HEIGHT, Texts.translatable("confluxmap.screen.waypoint.cancel"), b -> onCancel()
        ));
    }

    private TextFieldWidget numericField(final int x, final int y, final int w, final String initial) {
        final TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, w, FIELD_HEIGHT, Text.of(""));
        field.setMaxLength(32);
        field.setTextPredicate(s -> NUMERIC.matcher(s).matches());
        field.setText(initial);
        return field;
    }

    private void renderColorSwatch(final GuiDraw draw, final ButtonWidget button, final int color) {
        final MatrixStack matrices = draw.matrices();
        final int x = Widgets.x(button);
        final int y = Widgets.y(button);
        RenderUtil.fillRect(matrices, x, y, button.getWidth(), button.getHeight(), color | 0xFF000000);
        if (color == selectedColor) {
            RenderUtil.fillRect(matrices, x - 2, y - 2, button.getWidth() + 4, 2, 0xFFFFFFFF);
            RenderUtil.fillRect(matrices, x - 2, y + button.getHeight(), button.getWidth() + 4, 2, 0xFFFFFFFF);
            RenderUtil.fillRect(matrices, x - 2, y - 2, 2, button.getHeight() + 4, 0xFFFFFFFF);
            RenderUtil.fillRect(matrices, x + button.getWidth(), y - 2, 2, button.getHeight() + 4, 0xFFFFFFFF);
        }
    }

    private List<String> localSetNames() {
        final List<String> names = new ArrayList<>();
        names.add(WaypointSet.DEFAULT_NAME);
        final WaypointStore store = boundLocalStore;
        if (store != null) {
            for (final WaypointSet set : store.sets()) {
                if (!names.contains(set.name())) {
                    names.add(set.name());
                }
            }
        }
        if (!initialGroup.isEmpty() && !names.contains(initialGroup)) {
            names.add(initialGroup);
        }
        return List.copyOf(names);
    }

    private void selectNextSet() {
        selectedSetIndex = (selectedSetIndex + 1) % setNames.size();
        if (setButton != null) {
            setButton.setMessage(selectedSetLabel());
        }
    }

    private Text selectedSetLabel() {
        final String name = selectedSetName();
        return name.isEmpty()
            ? Texts.translatable("confluxmap.screen.waypoint.set_default")
            : Text.of(name);
    }

    private String selectedSetName() {
        if (createTarget != CreateTarget.LOCAL || setNames.isEmpty()) {
            return WaypointSet.DEFAULT_NAME;
        }
        return setNames.get(selectedSetIndex);
    }

    private static String formatCoord(final double value) {
        if (value == 0.0) {
            return "0";
        }
        final String plain = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return plain.length() <= 24 ? plain : Double.toString(value);
    }

    private void onDone() {
        if (createTarget == CreateTarget.LOCAL
            && (boundLocalStore == null
                || boundLocalStore != ConfluxMapClient.get().waypointService().current()
                || !boundLocalStore.persistenceWritable())) {
            return;
        }
        final String name = nameField.getText().trim();
        if (name.isEmpty()) {
            return;
        }
        final double x = parseOr(xField.getText(), initialX);
        final double y = parseOr(yField.getText(), initialY);
        final double z = parseOr(zField.getText(), initialZ);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return;
        }
        final Waypoint waypoint = new Waypoint(
            editingId == null ? UUID.randomUUID() : editingId,
            name, dimensionId, x, y, z, selectedColor, selectedSetName(),
            initialVisible, type, createdAtEpochMs
        );
        if (editingId == null && createTarget != CreateTarget.LOCAL) {
            final WaypointShareConfirmScreen.Target target = createTarget == CreateTarget.PUBLIC
                ? WaypointShareConfirmScreen.Target.PUBLIC
                : WaypointShareConfirmScreen.Target.CHAT;
            MinecraftClient.getInstance().setScreen(new WaypointShareConfirmScreen(parent, waypoint, target));
            return;
        }
        final WaypointStore store = boundLocalStore;
        if (store != null) {
            if (editingId == null) {
                store.add(waypoint);
            } else {
                store.update(waypoint);
            }
        }
        MinecraftClient.getInstance().setScreen(parent);
    }

    private void onCancel() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public void tick() {
        super.tick();
        if (createTarget == CreateTarget.LOCAL
            && boundLocalStore != ConfluxMapClient.get().waypointService().current()) {
            MinecraftClient.getInstance().setScreen(parent);
            return;
        }
        if (createTarget == CreateTarget.PUBLIC
            && sharedWaypoints.state() != SharedWaypointClientState.State.ENABLED) {
            MinecraftClient.getInstance().setScreen(parent);
        }
    }

    private static double parseOr(final String text, final double fallback) {
        try {
            return Double.parseDouble(text);
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCenteredLabel(draw, getTitle().getString(), 18);
        drawCenteredLabel(draw, Texts.translatable("confluxmap.screen.waypoint.name").getString(), 32);
        drawCenteredLabel(draw, Texts.translatable("confluxmap.screen.waypoint.coords").getString(), 68);
        if (createTarget == CreateTarget.LOCAL) {
            drawCenteredLabel(draw, Texts.translatable("confluxmap.screen.waypoint.set").getString(), 104);
        }
        drawCenteredLabel(draw, Texts.translatable("confluxmap.screen.waypoint.color").getString(), 140);
    }

    private void drawCenteredLabel(final GuiDraw draw, final String text, final int y) {
        final int textWidth = textRenderer.getWidth(text);
        draw.drawTextWithShadow(textRenderer, text, width / 2f - textWidth / 2f, y, 0xFFFFFFFF);
    }
}
