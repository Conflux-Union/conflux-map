package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointMarkerStyle;
import cn.net.rms.confluxmap.core.waypoint.WaypointSet;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import cn.net.rms.confluxmap.core.waypoint.list.EditDraft;
import cn.net.rms.confluxmap.core.waypoint.list.GroupRef;
import cn.net.rms.confluxmap.core.waypoint.list.LocalWaypointListModel;
import cn.net.rms.confluxmap.core.waypoint.list.SharedWaypointListModel;
import cn.net.rms.confluxmap.core.waypoint.list.WaypointListModel;
import cn.net.rms.confluxmap.core.waypoint.list.WaypointRow;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#endif
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/**
 * Create/edit form for one waypoint: name, X/Y/Z (raw local coordinates in
 * {@link #dimensionId}), cross-dimension visibility with an optional Y override
 * for the linked-dimension view, a color palette, and a group selector - the
 * local store's waypoint sets or the shared server's groups. The dimension
 * itself and the normal/death type are fixed at creation and not editable
 * here, per the implementation brief. Saves go through the unified list
 * models; only the archived-world store form writes the store directly.
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
    private static final int CUSTOM_COLOR_WIDTH = 68;

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
    private final LocalWaypointListModel localModel;
    private final SharedWaypointListModel sharedModel;
    private final WaypointStore boundLocalStore;
    private final Supplier<WaypointStore> localStoreSupplier;
    /** True when an archived/view world's store was explicitly bound instead of the live session. */
    private final boolean explicitLocalStore;
    private final boolean openedFromHotkey;
    /** Unified-template row of the waypoint being edited; the model routes updates by id. */
    private final WaypointRow editingRow;

    private TextFieldWidget nameField;
    private TextFieldWidget markerLabelField;
    private TextFieldWidget xField;
    private TextFieldWidget yField;
    private TextFieldWidget zField;
    private TextFieldWidget crossDimensionYField;
    private ButtonWidget setButton;
    private ButtonWidget groupButton;
    private ButtonWidget markerModeButton;
    private ButtonWidget iconButton;
    private ButtonWidget clearIconButton;
    private ButtonWidget deleteButton;
    private ButtonWidget doneButton;
    private List<String> setNames = List.of(WaypointSet.DEFAULT_NAME);
    private List<GroupRef> sharedGroups = List.of();
    private int selectedSetIndex;
    private int selectedGroupIndex;
    private final WaypointColorSelection colorSelection;
    private boolean crossDimensionVisible;
    private String selectedIconItemId = "";
    private String draftName;
    private String draftX;
    private String draftY;
    private String draftZ;
    private String draftGroup;
    private String draftMarkerLabel = "";
    private String draftCrossDimensionY = "";
    private WaypointMarkerMode markerMode = WaypointMarkerMode.TEXT;
    private boolean deleteConfirmationPending;
    private String errorKey;

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
        final boolean crossDimensionVisible,
        final CreateTarget createTarget,
        final Supplier<WaypointStore> localStoreSupplier,
        final boolean openedFromHotkey,
        final WaypointRow editingRow
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
        this.crossDimensionVisible = crossDimensionVisible;
        this.createTarget = createTarget;
        this.openedFromHotkey = openedFromHotkey;
        this.editingRow = editingRow;
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.sharedWaypoints = app.sharedWaypoints();
        this.localModel = app.localWaypointListModel();
        this.sharedModel = app.sharedWaypointListModel();
        this.explicitLocalStore = createTarget == CreateTarget.LOCAL && localStoreSupplier != null;
        this.localStoreSupplier = createTarget == CreateTarget.LOCAL
            ? localStoreSupplier == null
                ? () -> app.waypointService().current()
                : localStoreSupplier
            : () -> null;
        this.boundLocalStore = this.localStoreSupplier.get();
        this.colorSelection = new WaypointColorSelection(color);
        this.draftName = name;
        this.draftX = formatCoord(x);
        this.draftY = formatCoord(y);
        this.draftZ = formatCoord(z);
        this.draftGroup = this.initialGroup;
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
            "", x, y, z, PRESET_COLORS[5], initialSetName, true, false,
            CreateTarget.LOCAL, null, false, null
        );
    }

    /** Local create form bound to an explicitly selected archived world's store. */
    static WaypointEditScreen forCreate(
        final Screen parent,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final Supplier<WaypointStore> localStoreSupplier
    ) {
        return new WaypointEditScreen(
            parent, null, dimensionId, Waypoint.Type.NORMAL, System.currentTimeMillis(),
            "", x, y, z, PRESET_COLORS[5], WaypointSet.DEFAULT_NAME, true, false,
            CreateTarget.LOCAL, localStoreSupplier, false, null
        );
    }

    /** Local create form opened directly by the gameplay hotkey. */
    public static WaypointEditScreen forHotkeyCreate(
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z
    ) {
        return new WaypointEditScreen(
            null, null, dimensionId, Waypoint.Type.NORMAL, System.currentTimeMillis(),
            "", x, y, z, PRESET_COLORS[5], WaypointSet.DEFAULT_NAME, true, false,
            CreateTarget.LOCAL, null, true, null
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
            name, x, y, z, PRESET_COLORS[5], "", true, false,
            CreateTarget.LOCAL, null, false, null
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
            "", x, y, z, PRESET_COLORS[5], "", true, false, target, null, false, null
        );
    }

    public static WaypointEditScreen forEdit(final Screen parent, final Waypoint waypoint) {
        final WaypointEditScreen screen = new WaypointEditScreen(
            parent, waypoint.id, waypoint.dimensionId, waypoint.type, waypoint.createdAtEpochMs,
            waypoint.name, waypoint.x, waypoint.y, waypoint.z, waypoint.colorArgb, waypoint.group,
            waypoint.visible, waypoint.crossDimensionVisible,
            CreateTarget.LOCAL, null, false, wrapLocal(waypoint)
        );
        return withCrossDimensionY(withMarkerStyle(screen, waypoint.iconItemId, waypoint.markerLabel),
            waypoint.crossDimensionY
        );
    }

    static WaypointEditScreen forEdit(
        final Screen parent,
        final Waypoint waypoint,
        final Supplier<WaypointStore> localStoreSupplier
    ) {
        final WaypointEditScreen screen = new WaypointEditScreen(
            parent, waypoint.id, waypoint.dimensionId, waypoint.type, waypoint.createdAtEpochMs,
            waypoint.name, waypoint.x, waypoint.y, waypoint.z, waypoint.colorArgb, waypoint.group,
            waypoint.visible, waypoint.crossDimensionVisible,
            CreateTarget.LOCAL, localStoreSupplier, false, wrapLocal(waypoint)
        );
        return withCrossDimensionY(withMarkerStyle(screen, waypoint.iconItemId, waypoint.markerLabel),
            waypoint.crossDimensionY
        );
    }

    public static WaypointEditScreen forPublicEdit(final Screen parent, final WaypointRow row) {
        final WaypointEditScreen screen = new WaypointEditScreen(
            parent, row.id(), row.dimensionId(), row.type(), System.currentTimeMillis(),
            row.name(), row.x(), row.y(), row.z(), row.colorArgb(), row.groupKey(),
            true, row.crossDimensionVisible(),
            CreateTarget.PUBLIC, null, false, row
        );
        return withCrossDimensionY(withMarkerStyle(screen, row.iconItemId(), row.markerLabel()),
            ConfluxMapClient.get().config().sharedWaypointCrossDimensionY(row.id())
        );
    }

    public static WaypointEditScreen forPublicEdit(final Screen parent, final SharedWaypoint waypoint) {
        final ConfluxMapClient app = ConfluxMapClient.get();
        return forPublicEdit(parent, new WaypointRow(
            waypoint.id(), waypoint.name(), "", waypoint.dimensionId(),
            waypoint.x(), waypoint.y(), waypoint.z(), waypoint.colorArgb(), waypoint.type(),
            waypoint.iconItemId(), waypoint.markerLabel(),
            waypoint.groupId() == null ? GroupRef.UNGROUPED_KEY : waypoint.groupId().toString(),
            false, false,
            app.config().isSharedWaypointCrossDimensionVisible(waypoint.id()),
            ""
        ));
    }

    /** The model routes updates by id; the wrapper only carries identity and ownership. */
    private static WaypointRow wrapLocal(final Waypoint waypoint) {
        return new WaypointRow(
            waypoint.id, waypoint.name, "", waypoint.dimensionId,
            waypoint.x, waypoint.y, waypoint.z, waypoint.colorArgb, waypoint.type,
            waypoint.iconItemId, waypoint.markerLabel, waypoint.group,
            !waypoint.visible, false, waypoint.crossDimensionVisible, ""
        );
    }

    private static WaypointEditScreen withMarkerStyle(
        final WaypointEditScreen screen,
        final String iconItemId,
        final String markerLabel
    ) {
        screen.selectedIconItemId = iconItemId;
        screen.draftMarkerLabel = markerLabel;
        screen.markerMode = WaypointMarkerMode.initial(iconItemId);
        return screen;
    }

    private static WaypointEditScreen withCrossDimensionY(
        final WaypointEditScreen screen,
        final Double crossDimensionY
    ) {
        screen.draftCrossDimensionY =
            crossDimensionY != null && Double.isFinite(crossDimensionY)
                ? formatCoord(crossDimensionY)
                : "";
        return screen;
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

        nameField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, 34, fieldWidth, FIELD_HEIGHT, Text.of(""));
        nameField.setMaxLength(64);
        nameField.setText(draftName);
        addDrawableChild(nameField);
        if (openedFromHotkey) {
            deferInitialFocusUntilNextTick(nameField);
        } else {
            setInitialFocus(nameField);
        }

        final int coordWidth = 62;
        final int coordGap = 7;
        final int coordsLeft = centerX - (coordWidth * 3 + coordGap * 2) / 2;
        xField = numericField(coordsLeft, 68, coordWidth, draftX);
        yField = numericField(coordsLeft + coordWidth + coordGap, 68, coordWidth, draftY);
        zField = numericField(coordsLeft + (coordWidth + coordGap) * 2, 68, coordWidth, draftZ);
        addDrawableChild(xField);
        addDrawableChild(yField);
        addDrawableChild(zField);

        setButton = null;
        groupButton = null;
        if (createTarget == CreateTarget.LOCAL) {
            setNames = localSetNames();
            selectedSetIndex = Math.max(0, setNames.indexOf(draftGroup));
            setButton = addDrawableChild(Widgets.button(
                centerX - fieldWidth / 2,
                102,
                fieldWidth,
                FIELD_HEIGHT,
                selectedSetLabel(),
                button -> selectNextSet()
            ));
        } else if (createTarget == CreateTarget.PUBLIC) {
            sharedGroups = sharedModel.groupsForDisplay();
            selectedGroupIndex = Math.max(0, indexOfGroupKey(draftGroup));
            groupButton = addDrawableChild(Widgets.button(
                centerX - fieldWidth / 2,
                102,
                fieldWidth,
                FIELD_HEIGHT,
                selectedGroupLabel(),
                button -> selectNextSharedGroup()
            ));
            final boolean supported = sharedModel.supportsInFormGroupSelection();
            groupButton.active = supported;
            if (!supported) {
                setDisabledTooltip(groupButton, "confluxmap.shared_waypoints.group_mutations_unsupported");
            }
        }

        final int totalSwatchWidth = PRESET_COLORS.length * SWATCH_SIZE
            + (PRESET_COLORS.length - 1) * SWATCH_GAP;
        final int swatchLeft = centerX - totalSwatchWidth / 2;
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            final int color = PRESET_COLORS[i];
            final int x = swatchLeft + i * (SWATCH_SIZE + SWATCH_GAP);
            addColorSwatch(x, 128, () -> color, () -> colorSelection.selectPreset(color));
        }
        final int optionRowLeft = centerX - fieldWidth / 2;
        final int customButtonLeft = optionRowLeft + SWATCH_SIZE + SWATCH_GAP;
        addColorSwatch(
            optionRowLeft,
            150,
            colorSelection::custom,
            colorSelection::selectCustom
        );
        addDrawableChild(Widgets.button(
            customButtonLeft,
            150,
            CUSTOM_COLOR_WIDTH,
            SWATCH_SIZE,
            Texts.translatable("confluxmap.screen.waypoint.color_custom"),
            button -> MinecraftAccess.setScreen(
                MinecraftClient.getInstance(),
                new WaypointColorPickerScreen(
                    this,
                    colorSelection.custom(),
                    colorSelection::updateCustom
                )
            )
        ));

        if (createTarget != CreateTarget.CHAT) {
            addDrawableChild(Widgets.button(
                centerX,
                150,
                fieldWidth / 2,
                FIELD_HEIGHT,
                crossDimensionLabel(),
                button -> {
                    crossDimensionVisible = !crossDimensionVisible;
                    button.setMessage(crossDimensionLabel());
                    updateCrossDimensionYVisibility();
                }
            ));
            // The toggle text ("Cross-Dimension: ON") already fills the form's right half,
            // so the optional Y override sits just past the form edge rather than inside it;
            // it still fits the narrowest 320px GUI layout (field ends at centerX + 144).
            crossDimensionYField = numericField(centerX + 104, 150, 40, draftCrossDimensionY);
            crossDimensionYField.visible = crossDimensionVisible;
            addDrawableChild(crossDimensionYField);
            setHoverTooltip(
                crossDimensionYField,
                "confluxmap.screen.waypoint.cross_dimension_y"
            );
        } else {
            crossDimensionYField = null;
        }

        final boolean showMarkerStyle = createTarget != CreateTarget.CHAT;
        final boolean markerStyleSupported = createTarget == CreateTarget.LOCAL
            || createTarget == CreateTarget.PUBLIC && sharedWaypoints.supportsMarkerStyle();
        markerModeButton = addDrawableChild(Widgets.button(
            centerX - 100,
            180,
            80,
            FIELD_HEIGHT,
            markerModeLabel(),
            ignored -> selectNextMarkerMode()
        ));
        markerModeButton.active = markerStyleSupported;
        markerModeButton.visible = showMarkerStyle;
        markerLabelField = new TextFieldWidget(
            this.textRenderer,
            centerX - 16,
            180,
            116,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.marker_label")
        );
        markerLabelField.setMaxLength(6);
        markerLabelField.setText(draftMarkerLabel);
        final String[] lastValidMarkerLabel = {draftMarkerLabel};
        Widgets.setChangedListener(markerLabelField, value -> {
            if (validMarkerLabel(value)) {
                lastValidMarkerLabel[0] = value;
            } else {
                Widgets.setText(markerLabelField, lastValidMarkerLabel[0]);
            }
        });
        addDrawableChild(markerLabelField);
        iconButton = addDrawableChild(Widgets.button(
            centerX - 16,
            180,
            78,
            FIELD_HEIGHT,
            iconButtonMessage(),
            ignored -> openIconPicker()
        ));
        clearIconButton = addDrawableChild(Widgets.button(
            centerX + 66,
            180,
            34,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.icon_clear"),
            button -> {
                selectedIconItemId = "";
                button.active = false;
            }
        ));
        updateMarkerControls(showMarkerStyle, markerStyleSupported);
        if (!markerStyleSupported) {
            setDisabledTooltip(markerModeButton, "confluxmap.screen.waypoint.marker_style_unsupported");
            setDisabledTooltip(markerLabelField, "confluxmap.screen.waypoint.marker_style_unsupported");
            setDisabledTooltip(iconButton, "confluxmap.screen.waypoint.marker_style_unsupported");
            setDisabledTooltip(clearIconButton, "confluxmap.screen.waypoint.marker_style_unsupported");
        }

        final int footerY = height - 32;
        final boolean editing = editingId != null;
        final int footerLeft = centerX - (editing ? 100 : 104);
        final int footerButtonWidth = editing ? 64 : 100;
        if (editing) {
            deleteButton = addDrawableChild(Widgets.button(
                footerLeft,
                footerY,
                footerButtonWidth,
                FIELD_HEIGHT,
                deleteButtonMessage(),
                ignored -> onDelete()
            ));
            refreshDeleteButton();
        } else {
            deleteButton = null;
        }
        final int doneX = editing ? footerLeft + footerButtonWidth + 4 : centerX - 104;
        doneButton = addDrawableChild(Widgets.button(
            doneX, footerY, footerButtonWidth, FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.done"), b -> onDone()
        ));
        if (createTarget == CreateTarget.LOCAL) {
            final WaypointListModel.Permission localWrite = localWritePermission();
            doneButton.active = localWrite.granted();
            setDisabledTooltip(doneButton, localWrite.reasonKey());
        } else if (createTarget == CreateTarget.PUBLIC) {
            updatePublicDoneButton();
        }
        final int cancelX = editing ? doneX + footerButtonWidth + 4 : centerX + 4;
        addDrawableChild(Widgets.button(
            cancelX, footerY, footerButtonWidth, FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.cancel"), b -> onCancel()
        ));
        setEnterAction(() -> doneButton != null && doneButton.active, this::onDone);
    }

    private TextFieldWidget numericField(final int x, final int y, final int w, final String initial) {
        final TextFieldWidget field = new TextFieldWidget(this.textRenderer, x, y, w, FIELD_HEIGHT, Text.of(""));
        field.setMaxLength(32);
        //#if MC>=260100
        //$$ // 26.1 removed EditBox's text predicate. The responder plus a last-good value gives the
        //$$ // same "reject the keystroke" behaviour: setValue re-enters once with a valid string,
        //$$ // which takes the accepting branch and stops there.
        //$$ final String[] lastValid = {initial};
        //$$ field.setResponder(s -> {
        //$$     if (NUMERIC.matcher(s).matches()) {
        //$$         lastValid[0] = s;
        //$$     } else {
        //$$         field.setValue(lastValid[0]);
        //$$     }
        //$$ });
        //#else
        field.setTextPredicate(s -> NUMERIC.matcher(s).matches());
        //#endif
        field.setText(initial);
        return field;
    }

    private void addColorSwatch(
        final int x,
        final int y,
        final IntSupplier color,
        final Runnable onPress
    ) {
        //#if MC>=260100
        //$$ addRenderableWidget(new Button(
        //$$     x, y, SWATCH_SIZE, SWATCH_SIZE, Texts.literal(""),
        //$$     b -> onPress.run(), narration -> narration.get()
        //$$ ) {
        //$$     @Override
        //$$     protected void extractContents(
        //$$         final GuiGraphicsExtractor context,
        //$$         final int mouseX,
        //$$         final int mouseY,
        //$$         final float delta
        //$$     ) {
        //$$         if (useVanillaButtonStyle()) {
        //$$             extractDefaultSprite(context);
        //$$         }
        //$$         renderColorSwatch(GuiDraw.of(context), this, color.getAsInt());
        //$$     }
        //$$ });
        //#elseif MC>=12111
        //$$ addDrawableChild(new ButtonWidget(
        //$$     x, y, SWATCH_SIZE, SWATCH_SIZE, Texts.literal(""),
        //$$     b -> onPress.run(), narration -> narration.get()
        //$$ ) {
        //$$     @Override
        //$$     protected void drawIcon(
        //$$         final DrawContext context,
        //$$         final int mouseX,
        //$$         final int mouseY,
        //$$         final float delta
        //$$     ) {
        //$$         if (useVanillaButtonStyle()) {
        //$$             drawButton(context);
        //$$         }
        //$$         renderColorSwatch(GuiDraw.of(context), this, color.getAsInt());
        //$$     }
        //$$ });
        //#elseif MC>=12002
        //$$ addDrawableChild(new ButtonWidget(
        //$$     x, y, SWATCH_SIZE, SWATCH_SIZE, Text.of(""),
        //$$     b -> onPress.run(), narration -> narration.get()
        //$$ ) {
        //$$     @Override
        //$$     protected void renderWidget(
        //$$         final DrawContext context,
        //$$         final int mouseX,
        //$$         final int mouseY,
        //$$         final float delta
        //$$     ) {
        //$$         if (useVanillaButtonStyle()) {
        //$$             super.renderWidget(context, mouseX, mouseY, delta);
        //$$         }
        //$$         renderColorSwatch(GuiDraw.of(context), this, color.getAsInt());
        //$$     }
        //$$ });
        //#elseif MC>=12000
        //$$ addDrawableChild(new ButtonWidget(
        //$$     x, y, SWATCH_SIZE, SWATCH_SIZE, Text.of(""),
        //$$     b -> onPress.run(), narration -> narration.get()
        //$$ ) {
        //$$     @Override
        //$$     protected void renderButton(
        //$$         final DrawContext context,
        //$$         final int mouseX,
        //$$         final int mouseY,
        //$$         final float delta
        //$$     ) {
        //$$         if (useVanillaButtonStyle()) {
        //$$             super.renderButton(context, mouseX, mouseY, delta);
        //$$         }
        //$$         renderColorSwatch(GuiDraw.of(context), this, color.getAsInt());
        //$$     }
        //$$ });
        //#else
        addDrawableChild(new ButtonWidget(
            x, y, SWATCH_SIZE, SWATCH_SIZE, Text.of(""), b -> onPress.run()
        ) {
            @Override
            public void renderButton(
                final MatrixStack matrices,
                final int mouseX,
                final int mouseY,
                final float delta
            ) {
                if (useVanillaButtonStyle()) {
                    super.renderButton(matrices, mouseX, mouseY, delta);
                }
                renderColorSwatch(GuiDraw.of(matrices), this, color.getAsInt());
            }
        });
        //#endif
    }

    private void renderColorSwatch(final GuiDraw draw, final ButtonWidget button, final int color) {
        final MatrixStack matrices = draw.matrices();
        final int x = Widgets.x(button);
        final int y = Widgets.y(button);
        if (useVanillaButtonStyle()) {
            final int inset = 3;
            final int swatchSize = button.getWidth() - inset * 2;
            GuiShapes.fillRect(
                matrices, x + inset, y + inset, swatchSize, swatchSize,
                color | 0xFF000000
            );
            if (color == colorSelection.selected()) {
                GuiShapes.fillRect(
                    matrices, x + inset, y + inset, swatchSize, 1, 0xFFFFFFFF
                );
                GuiShapes.fillRect(
                    matrices, x + inset, y + inset + swatchSize - 1,
                    swatchSize, 1, 0xFFFFFFFF
                );
                GuiShapes.fillRect(
                    matrices, x + inset, y + inset, 1, swatchSize, 0xFFFFFFFF
                );
                GuiShapes.fillRect(
                    matrices, x + inset + swatchSize - 1, y + inset,
                    1, swatchSize, 0xFFFFFFFF
                );
            }
            return;
        }
        GuiShapes.fillRect(matrices, x, y, button.getWidth(), button.getHeight(), color | 0xFF000000);
        if (color == colorSelection.selected()) {
            GuiShapes.fillRect(matrices, x - 2, y - 2, button.getWidth() + 4, 2, 0xFFFFFFFF);
            GuiShapes.fillRect(matrices, x - 2, y + button.getHeight(), button.getWidth() + 4, 2, 0xFFFFFFFF);
            GuiShapes.fillRect(matrices, x - 2, y - 2, 2, button.getHeight() + 4, 0xFFFFFFFF);
            GuiShapes.fillRect(matrices, x + button.getWidth(), y - 2, 2, button.getHeight() + 4, 0xFFFFFFFF);
        }
    }

    private static boolean useVanillaButtonStyle() {
        final ConfluxMapClient app = ConfluxMapClient.get();
        return app != null && app.uiResourceTheme() != null
            && app.uiResourceTheme().useVanillaButtonStyle();
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

    private void selectNextSharedGroup() {
        selectedGroupIndex = (selectedGroupIndex + 1) % sharedGroups.size();
        if (groupButton != null) {
            groupButton.setMessage(selectedGroupLabel());
        }
    }

    private Text selectedGroupLabel() {
        final GroupRef group = sharedGroups.get(selectedGroupIndex);
        return group.isUngrouped()
            ? Texts.translatable("confluxmap.screen.waypoints.group_ungrouped")
            : Text.of(group.displayName());
    }

    private int indexOfGroupKey(final String key) {
        for (int index = 0; index < sharedGroups.size(); index++) {
            if (sharedGroups.get(index).key().equals(key)) {
                return index;
            }
        }
        return -1;
    }

    private Text markerModeLabel() {
        return Texts.translatable(markerMode.translationKey());
    }

    private static Text iconButtonMessage() {
        return Texts.literal("");
    }

    private void selectNextMarkerMode() {
        markerMode = markerMode.next();
        markerModeButton.setMessage(markerModeLabel());
        updateMarkerControls(true, markerModeButton.active);
    }

    private void updateMarkerControls(
        final boolean showMarkerStyle,
        final boolean markerStyleSupported
    ) {
        final boolean textMode = markerMode == WaypointMarkerMode.TEXT;
        markerLabelField.active = markerStyleSupported;
        markerLabelField.visible = showMarkerStyle && textMode;
        iconButton.active = markerStyleSupported;
        iconButton.visible = showMarkerStyle && !textMode;
        clearIconButton.active = markerStyleSupported && !selectedIconItemId.isEmpty();
        clearIconButton.visible = showMarkerStyle && !textMode;
    }

    private void openIconPicker() {
        captureDraft();
        MinecraftAccess.setScreen(
            MinecraftClient.getInstance(),
            new WaypointIconPickerScreen(this, itemId -> selectedIconItemId = itemId)
        );
    }

    private void updateCrossDimensionYVisibility() {
        if (crossDimensionYField != null) {
            crossDimensionYField.visible = crossDimensionVisible;
        }
    }

    private void captureDraft() {
        draftName = nameField.getText();
        draftX = xField.getText();
        draftY = yField.getText();
        draftZ = zField.getText();
        draftGroup = selectedDraftGroup();
        draftMarkerLabel = markerLabelField.getText();
        if (crossDimensionYField != null) {
            draftCrossDimensionY = crossDimensionYField.getText();
        }
    }

    @Override
    //#if MC>=12111
    //$$ public void resize(final int width, final int height) {
    //#else
    public void resize(final MinecraftClient client, final int width, final int height) {
    //#endif
        if (nameField != null) {
            captureDraft();
        }
        //#if MC>=12111
        //$$ super.resize(width, height);
        //#else
        super.resize(client, width, height);
        //#endif
    }

    private String selectedSetName() {
        if (createTarget != CreateTarget.LOCAL || setNames.isEmpty()) {
            return WaypointSet.DEFAULT_NAME;
        }
        return setNames.get(selectedSetIndex);
    }

    private String selectedGroupKey() {
        if (createTarget != CreateTarget.PUBLIC || sharedGroups.isEmpty()) {
            return GroupRef.UNGROUPED_KEY;
        }
        return sharedGroups.get(selectedGroupIndex).key();
    }

    private String selectedDraftGroup() {
        return createTarget == CreateTarget.PUBLIC ? selectedGroupKey() : selectedSetName();
    }

    private static String formatCoord(final double value) {
        if (!Double.isFinite(value)) {
            return "";
        }
        if (value == 0.0) {
            return "0";
        }
        final String plain = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return plain.length() <= 24 ? plain : Double.toString(value);
    }

    private WaypointListModel.Permission localWritePermission() {
        if (explicitLocalStore) {
            return boundLocalStore != null && boundLocalStore.persistenceWritable()
                ? WaypointListModel.Permission.allow()
                : WaypointListModel.Permission.denied("confluxmap.screen.waypoints.read_only");
        }
        return editingRow == null
            ? localModel.canCreateWaypoint()
            : localModel.canEditWaypoint(editingRow);
    }

    private WaypointListModel.Permission publicWritePermission() {
        return editingRow == null
            ? sharedModel.canCreateWaypoint()
            : sharedModel.canEditWaypoint(editingRow);
    }

    private Waypoint buildWaypoint(
        final WaypointFormValidation.Values values,
        final String iconItemId,
        final String markerLabel,
        final String groupKey,
        final Double crossDimensionY
    ) {
        final Waypoint waypoint = new Waypoint(
            editingId == null ? UUID.randomUUID() : editingId,
            values.name(), dimensionId, values.x(), values.y(), values.z(),
            colorSelection.selected(), groupKey,
            initialVisible, crossDimensionVisible, type, createdAtEpochMs
        );
        waypoint.crossDimensionY = crossDimensionY;
        waypoint.iconItemId = iconItemId;
        waypoint.markerLabel = markerLabel;
        return waypoint;
    }

    private void onDone() {
        if (createTarget == CreateTarget.LOCAL) {
            if (!localWritePermission().granted()) {
                return;
            }
        } else if (createTarget == CreateTarget.PUBLIC && !publicWritePermission().granted()) {
            return;
        }
        final var validationError = WaypointFormValidation.error(
            nameField.getText(), xField.getText(), yField.getText(), zField.getText()
        );
        if (validationError.isPresent()) {
            errorKey = validationError.get() == WaypointFormValidation.Error.NAME_REQUIRED
                ? "confluxmap.screen.waypoint.error.name_required"
                : "confluxmap.screen.waypoint.error.invalid_coordinates";
            return;
        }
        errorKey = null;
        if (crossDimensionYField != null
            && !WaypointFormValidation.optionalCoordinateValid(crossDimensionYField.getText())) {
            errorKey = "confluxmap.screen.waypoint.error.invalid_cross_dimension_y";
            return;
        }
        final WaypointFormValidation.Values values = WaypointFormValidation.values(
            nameField.getText(), xField.getText(), yField.getText(), zField.getText()
        );
        final Double crossDimensionY = crossDimensionVisible && crossDimensionYField != null
            ? WaypointFormValidation.optionalCoordinate(crossDimensionYField.getText())
            : null;
        final String iconItemId;
        final String markerLabel;
        try {
            iconItemId = WaypointMarkerStyle.iconItemId(markerMode.iconItemId(selectedIconItemId));
            markerLabel = WaypointMarkerStyle.markerLabel(markerMode.markerLabel(markerLabelField.getText()));
        } catch (final IllegalArgumentException e) {
            errorKey = "confluxmap.screen.waypoint.error.invalid_marker_label";
            return;
        }
        final String groupKey = selectedDraftGroup();
        if (editingId == null && createTarget != CreateTarget.LOCAL) {
            // Creates for an outward audience still cross the share/publish confirm boundary;
            // the public draft carries the selected group into the publish action.
            final WaypointShareConfirmScreen.Target target = createTarget == CreateTarget.PUBLIC
                ? WaypointShareConfirmScreen.Target.PUBLIC
                : WaypointShareConfirmScreen.Target.CHAT;
            final Waypoint preview = buildWaypoint(values, iconItemId, markerLabel, groupKey, crossDimensionY);
            final EditDraft publicDraft = createTarget == CreateTarget.PUBLIC
                ? new EditDraft(
                    values.name(), dimensionId, values.x(), values.y(), values.z(),
                    colorSelection.selected(), type, iconItemId, markerLabel,
                    crossDimensionVisible, crossDimensionY, groupKey)
                : null;
            MinecraftAccess.setScreen(
                MinecraftClient.getInstance(),
                new WaypointShareConfirmScreen(parent, preview, target, publicDraft)
            );
            return;
        }
        final EditDraft draft = new EditDraft(
            values.name(), dimensionId, values.x(), values.y(), values.z(),
            colorSelection.selected(), type, iconItemId, markerLabel,
            crossDimensionVisible, crossDimensionY, groupKey
        );
        if (createTarget == CreateTarget.PUBLIC) {
            if (!sharedModel.updateWaypoint(editingRow, draft)) {
                final WaypointListModel.Permission permission = publicWritePermission();
                errorKey = permission.granted()
                    ? "confluxmap.screen.waypoint.public_unavailable"
                    : permission.reasonKey();
                return;
            }
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
            return;
        }
        if (explicitLocalStore) {
            // The bound store is an archived world's, outside the local model's session store.
            final WaypointStore store = boundLocalStore;
            if (store != null) {
                final Waypoint waypoint =
                    buildWaypoint(values, iconItemId, markerLabel, groupKey, crossDimensionY);
                if (editingId == null) {
                    store.add(waypoint);
                } else {
                    store.update(waypoint);
                }
            }
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
            return;
        }
        final boolean applied = editingId == null
            ? localModel.createWaypoint(draft)
            : localModel.updateWaypoint(editingRow, draft);
        if (!applied && !localWritePermission().granted()) {
            errorKey = localWritePermission().reasonKey();
            return;
        }
        // An applied=false with permission granted means the target vanished mid-edit;
        // closing mirrors the store's historical silent no-op update.
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    private void onCancel() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    private Text deleteButtonMessage() {
        return Texts.translatable(
            deleteConfirmationPending
                ? "confluxmap.screen.waypoints.confirm"
                : "confluxmap.screen.waypoints.delete"
        );
    }

    private void onDelete() {
        if (!deleteConfirmationPending) {
            deleteConfirmationPending = true;
            deleteButton.setMessage(deleteButtonMessage());
            return;
        }
        if (createTarget == CreateTarget.PUBLIC) {
            if (!sharedModel.deleteWaypoint(editingRow)) {
                final WaypointListModel.Permission permission = sharedModel.canDeleteWaypoint(editingRow);
                errorKey = permission.granted()
                    ? "confluxmap.screen.waypoint.public_unavailable"
                    : permission.reasonKey();
                deleteConfirmationPending = false;
                deleteButton.setMessage(deleteButtonMessage());
                refreshDeleteButton();
                return;
            }
        } else if (explicitLocalStore) {
            final WaypointStore store = boundLocalStore;
            if (store == null || store != localStoreSupplier.get() || !store.persistenceWritable()) {
                refreshDeleteButton();
                return;
            }
            store.remove(editingId);
        } else {
            localModel.deleteWaypoint(editingRow);
        }
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    public void tick() {
        super.tick();
        if (createTarget == CreateTarget.LOCAL
            && boundLocalStore != localStoreSupplier.get()) {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
            return;
        }
        if (createTarget == CreateTarget.PUBLIC) {
            final SharedWaypointAvailability availability = sharedWaypoints.availability();
            if (!availability.visible()) {
                MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
                return;
            }
            updatePublicDoneButton();
        }
        refreshDeleteButton();
        refreshDoneButton();
    }

    private void refreshDeleteButton() {
        if (deleteButton == null) {
            return;
        }
        if (createTarget == CreateTarget.PUBLIC) {
            final WaypointListModel.Permission permission = sharedModel.canDeleteWaypoint(editingRow);
            deleteButton.active = permission.granted();
            setDisabledTooltip(deleteButton, permission.reasonKey());
            return;
        }
        deleteButton.active = localWritePermission().granted();
    }

    private void refreshDoneButton() {
        if (doneButton == null || nameField == null || xField == null || yField == null
            || zField == null || markerLabelField == null) {
            return;
        }
        final boolean localAvailable = createTarget != CreateTarget.LOCAL || localWritePermission().granted();
        final boolean publicAvailable = createTarget != CreateTarget.PUBLIC
            || publicWritePermission().granted();
        doneButton.active = localAvailable && publicAvailable
            && WaypointFormValidation.error(
                nameField.getText(), xField.getText(), yField.getText(), zField.getText()
            ).isEmpty()
            && (crossDimensionYField == null
                || WaypointFormValidation.optionalCoordinateValid(crossDimensionYField.getText()))
            && validMarkerLabel(markerMode.markerLabel(markerLabelField.getText()));
    }

    private static boolean validMarkerLabel(final String value) {
        try {
            WaypointMarkerStyle.markerLabel(value);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    private void updatePublicDoneButton() {
        final WaypointListModel.Permission permission = publicWritePermission();
        doneButton.active = permission.granted();
        setDisabledTooltip(doneButton, permission.reasonKey());
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCenteredLabel(draw, getTitle().getString(), 8);
        drawCenteredLabel(draw, Texts.translatable("confluxmap.screen.waypoint.name").getString(), 24);
        drawCenteredLabel(draw, Texts.translatable("confluxmap.screen.waypoint.coords").getString(), 58);
        if (createTarget == CreateTarget.LOCAL) {
            drawCenteredLabel(draw, Texts.translatable("confluxmap.screen.waypoint.set").getString(), 92);
        } else if (createTarget == CreateTarget.PUBLIC) {
            drawCenteredLabel(draw, Texts.translatable("confluxmap.screen.waypoint.group").getString(), 92);
        }
        drawCenteredLabel(draw, Texts.translatable("confluxmap.screen.waypoint.color").getString(), 118);
        if (createTarget != CreateTarget.CHAT) {
            drawCenteredLabel(
                draw,
                Texts.translatable("confluxmap.screen.waypoint.marker_style").getString(),
                170
            );
        }
        if (errorKey != null) {
            drawCenteredLabel(draw, Texts.translatable(errorKey).getString(), height - 50);
        }
    }

    @Override
    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        drawSelectedItemIcon(draw);
    }

    private void drawSelectedItemIcon(final GuiDraw draw) {
        if (iconButton == null || !iconButton.visible) {
            return;
        }
        final ItemStack stack = WaypointMarkerRenderer.itemIcon(selectedIconItemId);
        if (stack.isEmpty()) {
            return;
        }
        draw.drawItemIcon(
            MinecraftClient.getInstance(),
            stack,
            Widgets.x(iconButton) + iconButton.getWidth() / 2f,
            Widgets.y(iconButton) + iconButton.getHeight() / 2f,
            16
        );
    }

    private void drawCenteredLabel(final GuiDraw draw, final String text, final int y) {
        final int textWidth = this.textRenderer.getWidth(text);
        draw.drawTextWithShadow(this.textRenderer, text, width / 2f - textWidth / 2f, y, 0xFFFFFFFF);
    }

    private Text crossDimensionLabel() {
        return Texts.translatable(
            "confluxmap.screen.waypoint.cross_dimension",
            Texts.translatable(
                crossDimensionVisible ? "confluxmap.value.on" : "confluxmap.value.off"
            ).getString()
        );
    }
}
