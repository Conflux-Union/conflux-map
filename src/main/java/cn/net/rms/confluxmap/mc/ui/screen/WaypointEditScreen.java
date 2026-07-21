package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

/**
 * Create/edit form for one waypoint: name, X/Y/Z (raw local coordinates in
 * {@link #dimensionId}, never dimension-converted - see {@code DimensionScale}),
 * an 8-swatch color palette, and a free-text group. The dimension itself and
 * the normal/death type are fixed at creation and not editable here, per the
 * implementation brief.
 */
public final class WaypointEditScreen extends Screen {
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

    private TextFieldWidget nameField;
    private TextFieldWidget xField;
    private TextFieldWidget yField;
    private TextFieldWidget zField;
    private TextFieldWidget groupField;
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
        super(new TranslatableText(titleKey(editingId, createTarget)));
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
        this.initialGroup = group;
        this.initialVisible = visible;
        this.createTarget = createTarget;
        this.selectedColor = color;
    }

    /** Prefilled with {@code x/y/z}, taken as-is in the dimension the player is currently viewing/standing in. */
    public static WaypointEditScreen forCreate(
        final Screen parent, final DimensionId dimensionId, final double x, final double y, final double z
    ) {
        return forCreate(parent, dimensionId, "", x, y, z);
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

        groupField = new TextFieldWidget(textRenderer, centerX - fieldWidth / 2, 114, fieldWidth, FIELD_HEIGHT, Text.of(""));
        groupField.setMaxLength(32);
        groupField.setText(initialGroup);
        addDrawableChild(groupField);

        final int totalSwatchWidth = PRESET_COLORS.length * SWATCH_SIZE + (PRESET_COLORS.length - 1) * SWATCH_GAP;
        final int swatchLeft = centerX - totalSwatchWidth / 2;
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            final int color = PRESET_COLORS[i];
            final int x = swatchLeft + i * (SWATCH_SIZE + SWATCH_GAP);
            addDrawableChild(new ButtonWidget(x, 150, SWATCH_SIZE, SWATCH_SIZE, Text.of(""), b -> selectedColor = color) {
                @Override
                public void renderButton(final MatrixStack matrices, final int mouseX, final int mouseY, final float delta) {
                    RenderUtil.fillRect(matrices, this.x, this.y, this.getWidth(), this.getHeight(), color | 0xFF000000);
                    if (color == selectedColor) {
                        RenderUtil.fillRect(matrices, this.x - 2, this.y - 2, this.getWidth() + 4, 2, 0xFFFFFFFF);
                        RenderUtil.fillRect(matrices, this.x - 2, this.y + this.getHeight(), this.getWidth() + 4, 2, 0xFFFFFFFF);
                        RenderUtil.fillRect(matrices, this.x - 2, this.y - 2, 2, this.getHeight() + 4, 0xFFFFFFFF);
                        RenderUtil.fillRect(matrices, this.x + this.getWidth(), this.y - 2, 2, this.getHeight() + 4, 0xFFFFFFFF);
                    }
                }
            });
        }

        addDrawableChild(new ButtonWidget(
            centerX - 104, height - 32, 100, FIELD_HEIGHT, new TranslatableText("confluxmap.screen.waypoint.done"), b -> onDone()
        ));
        addDrawableChild(new ButtonWidget(
            centerX + 4, height - 32, 100, FIELD_HEIGHT, new TranslatableText("confluxmap.screen.waypoint.cancel"), b -> onCancel()
        ));
    }

    private TextFieldWidget numericField(final int x, final int y, final int w, final String initial) {
        final TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, w, FIELD_HEIGHT, Text.of(""));
        field.setMaxLength(32);
        field.setTextPredicate(s -> NUMERIC.matcher(s).matches());
        field.setText(initial);
        return field;
    }

    private static String formatCoord(final double value) {
        if (value == 0.0) {
            return "0";
        }
        final String plain = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return plain.length() <= 24 ? plain : Double.toString(value);
    }

    private void onDone() {
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
            name, dimensionId, x, y, z, selectedColor, groupField.getText().trim(),
            initialVisible, type, createdAtEpochMs
        );
        if (editingId == null && createTarget != CreateTarget.LOCAL) {
            final WaypointShareConfirmScreen.Target target = createTarget == CreateTarget.PUBLIC
                ? WaypointShareConfirmScreen.Target.PUBLIC
                : WaypointShareConfirmScreen.Target.CHAT;
            MinecraftClient.getInstance().setScreen(new WaypointShareConfirmScreen(parent, waypoint, target));
            return;
        }
        final WaypointService service = ConfluxMapClient.get().waypointService();
        final WaypointStore store = service.current();
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

    private static double parseOr(final String text, final double fallback) {
        try {
            return Double.parseDouble(text);
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        renderBackground(matrices);
        drawCenteredLabel(matrices, getTitle().getString(), 18);
        drawCenteredLabel(matrices, new TranslatableText("confluxmap.screen.waypoint.name").getString(), 32);
        drawCenteredLabel(matrices, new TranslatableText("confluxmap.screen.waypoint.coords").getString(), 68);
        drawCenteredLabel(matrices, new TranslatableText("confluxmap.screen.waypoint.group").getString(), 104);
        drawCenteredLabel(matrices, new TranslatableText("confluxmap.screen.waypoint.color").getString(), 140);
        super.render(matrices, mouseX, mouseY, tickDelta);
    }

    private void drawCenteredLabel(final MatrixStack matrices, final String text, final int y) {
        final int textWidth = textRenderer.getWidth(text);
        textRenderer.drawWithShadow(matrices, text, width / 2f - textWidth / 2f, y, 0xFFFFFFFF);
    }
}
