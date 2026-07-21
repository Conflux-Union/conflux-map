package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;

/** Explicit confirmation boundary for deleting a set and every waypoint assigned to it. */
final class WaypointSetDeleteConfirmScreen extends Screen {
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_TEXT_COLOR = 0xFFB8B8B8;
    private static final int WARNING_TEXT_COLOR = 0xFFFFAA55;
    private static final int ERROR_TEXT_COLOR = 0xFFFF7777;

    private final Screen parent;
    private final WaypointStore boundStore;
    private final String setName;
    private final Runnable onSuccess;
    private int waypointCount;
    private ButtonWidget confirmButton;
    private String errorKey;

    WaypointSetDeleteConfirmScreen(
        final Screen parent,
        final WaypointStore boundStore,
        final String setName,
        final int waypointCount,
        final Runnable onSuccess
    ) {
        super(new TranslatableText("confluxmap.screen.waypoint_set.delete"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.boundStore = Objects.requireNonNull(boundStore, "boundStore");
        this.setName = Objects.requireNonNull(setName, "setName");
        if (waypointCount < 0) {
            throw new IllegalArgumentException("waypointCount must not be negative");
        }
        this.waypointCount = waypointCount;
        this.onSuccess = Objects.requireNonNull(onSuccess, "onSuccess");
    }

    @Override
    protected void init() {
        final int centerX = width / 2;
        confirmButton = addDrawableChild(new ButtonWidget(
            centerX - 104,
            height - 32,
            100,
            20,
            new TranslatableText("confluxmap.screen.waypoint_set.delete.confirm"),
            button -> confirmDelete()
        ));
        addDrawableChild(new ButtonWidget(
            centerX + 4,
            height - 32,
            100,
            20,
            new TranslatableText("confluxmap.screen.waypoint.cancel"),
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
        MinecraftClient.getInstance().setScreen(parent);
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
        if (availabilityError != null) {
            errorKey = availabilityError;
        }
    }

    private String availabilityErrorKey() {
        final WaypointStore currentStore = ConfluxMapClient.get().waypointService().current();
        if (currentStore == null || currentStore != boundStore) {
            return "confluxmap.screen.waypoint_set.error.unavailable";
        }
        if (!boundStore.persistenceWritable()) {
            return "confluxmap.screen.waypoint_set.error.read_only";
        }
        final boolean setExists = boundStore.sets().stream()
            .anyMatch(set -> set.name().equals(setName));
        if (!setExists) {
            return "confluxmap.screen.waypoint_set.error.not_found";
        }
        waypointCount = boundStore.waypointCount(setName);
        return null;
    }

    private void confirmDelete() {
        errorKey = availabilityErrorKey();
        if (errorKey != null) {
            return;
        }

        final WaypointStore.DeleteSetResult result = boundStore.deleteSet(setName);
        if (result.result() == WaypointStore.MutationResult.APPLIED) {
            onSuccess.run();
            onClose();
            return;
        }
        errorKey = mutationErrorKey(result.result());
    }

    private static String mutationErrorKey(final WaypointStore.MutationResult result) {
        return switch (result) {
            case READ_ONLY -> "confluxmap.screen.waypoint_set.error.read_only";
            case SET_NOT_FOUND -> "confluxmap.screen.waypoint_set.error.not_found";
            default -> "confluxmap.screen.waypoint_set.error.unavailable";
        };
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        renderBackground(matrices);
        drawCentered(matrices, getTitle().getString(), 24, TEXT_COLOR);
        drawCentered(matrices, new TranslatableText(
            "confluxmap.screen.waypoint_set.delete.name", setName
        ).getString(), 54, TEXT_COLOR);
        drawCentered(matrices, new TranslatableText(
            "confluxmap.screen.waypoint_set.delete.members", waypointCount
        ).getString(), 74, MUTED_TEXT_COLOR);
        drawCentered(
            matrices,
            new TranslatableText("confluxmap.screen.waypoint_set.delete.warning").getString(),
            94,
            WARNING_TEXT_COLOR
        );
        if (errorKey != null) {
            drawCentered(matrices, new TranslatableText(errorKey).getString(), height - 50, ERROR_TEXT_COLOR);
        }
        super.render(matrices, mouseX, mouseY, tickDelta);
    }

    private void drawCentered(final MatrixStack matrices, final String value, final int y, final int color) {
        final String text = textRenderer.trimToWidth(value, Math.max(40, width - 32));
        textRenderer.drawWithShadow(matrices, text, width / 2f - textRenderer.getWidth(text) / 2f, y, color);
    }
}
