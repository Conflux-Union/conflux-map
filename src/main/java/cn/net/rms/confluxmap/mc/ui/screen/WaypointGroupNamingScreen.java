package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.waypoint.list.GroupRef;
import cn.net.rms.confluxmap.core.waypoint.list.WaypointListModel;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Create/rename form for one group of the unified waypoint list template. The
 * backing model decides whether "group" names a player-owned local set or a
 * server-shared group; only titles, the name limit, and the error copy follow
 * that distinction. Shared submits close on a successful send - the
 * authoritative outcome arrives through the shared-waypoint result channel.
 */
final class WaypointGroupNamingScreen extends ConfluxScreen {
    /**
     * Which flavor of group is being named. The model's delete behavior is the
     * honest discriminator: local sets own their members (cascade), shared
     * groups only hold references (ungroup).
     */
    private enum Noun { SET, GROUP }

    private static final int FIELD_WIDTH = 220;
    private static final int FIELD_HEIGHT = 20;
    private static final int LOCAL_NAME_LIMIT = 32;
    private static final int SHARED_NAME_LIMIT = 64;

    private final Screen parent;
    private final WaypointListModel model;
    private final BooleanSupplier mutationsSupported;
    private final Noun noun;
    private final GroupRef existingGroup;
    private final Consumer<String> onSuccess;
    private TextFieldWidget nameField;
    private ButtonWidget doneButton;
    private String errorKey;

    WaypointGroupNamingScreen(
        final Screen parent,
        final WaypointListModel model,
        final BooleanSupplier mutationsSupported,
        final GroupRef existingGroup,
        final Consumer<String> onSuccess
    ) {
        super(Texts.translatable(existingGroup == null
            ? createTitleKey(model.groupDeleteBehavior())
            : renameTitleKey(model.groupDeleteBehavior())));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.model = Objects.requireNonNull(model, "model");
        this.mutationsSupported = Objects.requireNonNull(mutationsSupported, "mutationsSupported");
        this.noun = nounOf(model.groupDeleteBehavior());
        this.existingGroup = existingGroup;
        this.onSuccess = Objects.requireNonNull(onSuccess, "onSuccess");
    }

    private static Noun nounOf(final WaypointListModel.GroupDeleteBehavior behavior) {
        return behavior == WaypointListModel.GroupDeleteBehavior.CASCADE_MEMBERS ? Noun.SET : Noun.GROUP;
    }

    private static String createTitleKey(final WaypointListModel.GroupDeleteBehavior behavior) {
        return nounOf(behavior) == Noun.SET
            ? "confluxmap.screen.waypoint_set.create"
            : "confluxmap.screen.shared_group.create";
    }

    private static String renameTitleKey(final WaypointListModel.GroupDeleteBehavior behavior) {
        return nounOf(behavior) == Noun.SET
            ? "confluxmap.screen.waypoint_set.rename"
            : "confluxmap.screen.shared_group.rename";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int centerX = width / 2;
        nameField = new TextFieldWidget(
            this.textRenderer,
            centerX - FIELD_WIDTH / 2,
            Math.max(58, height / 2 - 28),
            FIELD_WIDTH,
            FIELD_HEIGHT,
            Text.of("")
        );
        nameField.setMaxLength(noun == Noun.SET ? LOCAL_NAME_LIMIT : SHARED_NAME_LIMIT);
        nameField.setText(existingGroup == null ? "" : existingGroup.displayName());
        addDrawableChild(nameField);
        setInitialFocus(nameField);

        final int buttonY = Widgets.y(nameField) + 30;
        doneButton = addDrawableChild(Widgets.button(
            centerX - 104,
            buttonY,
            100,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.done"),
            button -> submit()
        ));
        addDrawableChild(Widgets.button(
            centerX + 4,
            buttonY,
            100,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.cancel"),
            button -> onClose()
        ));
        setEnterAction(() -> doneButton != null && doneButton.active, this::submit);
        refreshDoneButton();
    }

    @Override
    public void tick() {
        Widgets.tick(nameField);
        refreshDoneButton();
    }

    @Override
    public void onClose() {
        requestClose(parent);
    }

    private void refreshDoneButton() {
        if (doneButton != null && nameField != null) {
            doneButton.active = !nameField.getText().trim().isEmpty() && permissionErrorKey() == null;
        }
    }

    private String permissionErrorKey() {
        if (!mutationsSupported.getAsBoolean()) {
            return "confluxmap.shared_waypoints.group_mutations_unsupported";
        }
        final WaypointListModel.Permission permission = existingGroup == null
            ? model.canCreateGroup()
            : model.canRenameGroup(existingGroup);
        if (permission.granted()) {
            return null;
        }
        // The local model's only denial is store writability; keep the set-specific copy.
        return noun == Noun.SET
            ? "confluxmap.screen.waypoint_set.error.read_only"
            : permission.reasonKey();
    }

    private void submit() {
        errorKey = null;
        final String requestedName = nameField.getText().trim();
        if (requestedName.isEmpty()) {
            return;
        }
        // Mirrors the store's set-name rule so the invalid-name copy stays reachable;
        // the field's length limit already covers the length half of it.
        if (noun == Noun.SET && !validSetName(requestedName)) {
            errorKey = "confluxmap.screen.waypoint_set.error.invalid_name";
            return;
        }
        errorKey = permissionErrorKey();
        if (errorKey != null) {
            return;
        }
        final boolean applied = existingGroup == null
            ? model.createGroup(requestedName)
            : model.renameGroup(existingGroup, requestedName);
        if (!applied) {
            errorKey = noun == Noun.SET && model.groupByKey(requestedName) != null
                ? "confluxmap.screen.waypoint_set.error.exists"
                : noun == Noun.SET
                    ? "confluxmap.screen.waypoint_set.error.unavailable"
                    : "confluxmap.screen.shared_group.error.unavailable";
            return;
        }
        onSuccess.accept(requestedName);
        onClose();
    }

    private static boolean validSetName(final String name) {
        if (name.isEmpty() || name.length() > LOCAL_NAME_LIMIT) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 24, 0xFFFFFFFF);
        drawCentered(
            draw,
            Texts.translatable(noun == Noun.SET
                ? "confluxmap.screen.waypoint_set.name"
                : "confluxmap.screen.shared_group.name"
            ).getString(),
            Widgets.y(nameField) - 12,
            0xFFCCCCCC
        );
        final String permissionError = permissionErrorKey();
        if (permissionError != null) {
            drawCentered(draw, Texts.translatable(permissionError).getString(), Widgets.y(nameField) + 56, 0xFFFF7777);
        } else if (errorKey != null) {
            drawCentered(draw, Texts.translatable(errorKey).getString(), Widgets.y(nameField) + 56, 0xFFFF7777);
        }
    }

    private void drawCentered(final GuiDraw draw, final String value, final int y, final int color) {
        final String text = this.textRenderer.trimToWidth(value, Math.max(40, width - 32));
        draw.drawTextWithShadow(this.textRenderer, text, width / 2f - this.textRenderer.getWidth(text) / 2f, y, color);
    }
}
