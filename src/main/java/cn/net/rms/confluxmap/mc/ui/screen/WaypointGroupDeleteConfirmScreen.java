package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.waypoint.list.GroupRef;
import cn.net.rms.confluxmap.core.waypoint.list.WaypointListModel;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Explicit confirmation boundary for deleting one group of the unified
 * waypoint list template. The model's {@code groupDeleteBehavior} picks both
 * the copy and the outcome: local sets own their members and delete them with
 * the set, shared groups only hold references and release their members back
 * to ungrouped (one broadcast delta clears every subscribed client's
 * references).
 */
final class WaypointGroupDeleteConfirmScreen extends ConfluxScreen {
    /** Which flavor of group is being deleted; see {@link WaypointGroupNamingScreen}. */
    private enum Noun { SET, GROUP }

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_TEXT_COLOR = 0xFFB8B8B8;
    private static final int WARNING_TEXT_COLOR = 0xFFFFAA55;
    private static final int ERROR_TEXT_COLOR = 0xFFFF7777;

    private final Screen parent;
    private final WaypointListModel model;
    private final BooleanSupplier mutationsSupported;
    private final Noun noun;
    private final GroupRef group;
    private final Runnable onSuccess;
    private int memberCount;
    private ButtonWidget confirmButton;
    private String errorKey;

    WaypointGroupDeleteConfirmScreen(
        final Screen parent,
        final WaypointListModel model,
        final BooleanSupplier mutationsSupported,
        final GroupRef group,
        final Runnable onSuccess
    ) {
        super(Texts.translatable(nounOf(model.groupDeleteBehavior()) == Noun.SET
            ? "confluxmap.screen.waypoint_set.delete"
            : "confluxmap.screen.shared_group.delete"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.model = Objects.requireNonNull(model, "model");
        this.mutationsSupported = Objects.requireNonNull(mutationsSupported, "mutationsSupported");
        this.noun = nounOf(model.groupDeleteBehavior());
        if (group == null || group.isUngrouped()) {
            throw new IllegalArgumentException("a real, deletable group is required");
        }
        this.group = group;
        this.onSuccess = Objects.requireNonNull(onSuccess, "onSuccess");
    }

    private static Noun nounOf(final WaypointListModel.GroupDeleteBehavior behavior) {
        return behavior == WaypointListModel.GroupDeleteBehavior.CASCADE_MEMBERS ? Noun.SET : Noun.GROUP;
    }

    @Override
    protected void init() {
        final int centerX = width / 2;
        confirmButton = addDrawableChild(Widgets.button(
            centerX - 104,
            height - 32,
            100,
            20,
            Texts.translatable(noun == Noun.SET
                ? "confluxmap.screen.waypoint_set.delete.confirm"
                : "confluxmap.screen.shared_group.delete.confirm"
            ),
            button -> confirmDelete()
        ));
        addDrawableChild(Widgets.button(
            centerX + 4,
            height - 32,
            100,
            20,
            Texts.translatable("confluxmap.screen.waypoint.cancel"),
            button -> onClose()
        ));
        refreshAvailability();
    }

    @Override
    public void tick() {
        super.tick();
        refreshAvailability();
    }

    @Override
    public void onClose() {
        requestClose(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void refreshAvailability() {
        final String availabilityError = availabilityErrorKey();
        if (confirmButton != null) {
            confirmButton.active = availabilityError == null;
        }
        errorKey = availabilityError;
    }

    private String availabilityErrorKey() {
        if (!mutationsSupported.getAsBoolean()) {
            return "confluxmap.shared_waypoints.group_mutations_unsupported";
        }
        final GroupRef current = model.groupByKey(group.key());
        if (current == null) {
            return noun == Noun.SET
                ? "confluxmap.screen.waypoint_set.error.not_found"
                : "confluxmap.screen.shared_group.error.not_found";
        }
        final WaypointListModel.Permission permission = model.canDeleteGroup(current);
        if (permission.granted()) {
            memberCount = model.memberCount(current.key());
            return null;
        }
        // The local model's only denial is store writability; keep the set-specific copy.
        return noun == Noun.SET
            ? "confluxmap.screen.waypoint_set.error.read_only"
            : permission.reasonKey();
    }

    private void confirmDelete() {
        errorKey = availabilityErrorKey();
        if (errorKey != null) {
            return;
        }
        final GroupRef current = model.groupByKey(group.key());
        if (current == null || !model.deleteGroup(current)) {
            errorKey = noun == Noun.SET
                ? "confluxmap.screen.waypoint_set.error.unavailable"
                : "confluxmap.screen.shared_group.error.unavailable";
            return;
        }
        onSuccess.run();
        onClose();
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 24, TEXT_COLOR);
        drawCentered(draw, Texts.translatable(noun == Noun.SET
            ? "confluxmap.screen.waypoint_set.delete.name"
            : "confluxmap.screen.shared_group.delete.name",
            group.displayName()
        ).getString(), 54, TEXT_COLOR);
        drawCentered(draw, Texts.translatable(noun == Noun.SET
            ? "confluxmap.screen.waypoint_set.delete.members"
            : "confluxmap.screen.shared_group.delete.members",
            memberCount
        ).getString(), 74, MUTED_TEXT_COLOR);
        drawCentered(
            draw,
            Texts.translatable(noun == Noun.SET
                ? "confluxmap.screen.waypoint_set.delete.warning"
                : "confluxmap.screen.shared_group.delete.warning"
            ).getString(),
            94,
            WARNING_TEXT_COLOR
        );
        if (errorKey != null) {
            drawCentered(draw, Texts.translatable(errorKey).getString(), height - 50, ERROR_TEXT_COLOR);
        }
    }

    private void drawCentered(final GuiDraw draw, final String value, final int y, final int color) {
        final String text = this.textRenderer.trimToWidth(value, Math.max(40, width - 32));
        draw.drawTextWithShadow(this.textRenderer, text, width / 2f - this.textRenderer.getWidth(text) / 2f, y, color);
    }
}
