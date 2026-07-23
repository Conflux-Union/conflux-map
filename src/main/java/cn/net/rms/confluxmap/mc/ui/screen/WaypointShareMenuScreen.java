package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.compat.Texts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;

/** Explicit choice between server publication and ordinary chat sharing. */
public final class WaypointShareMenuScreen extends Screen {
    private final Screen parent;
    private final Waypoint waypoint;
    private final SharedWaypointClient sharedWaypoints;
    private ButtonWidget publishButton;
    private SharedWaypointAvailability sharedAvailability;

    public WaypointShareMenuScreen(final Screen parent, final Waypoint waypoint) {
        super(Texts.translatable("confluxmap.screen.waypoint.share"));
        this.parent = parent;
        this.waypoint = waypoint.copy();
        this.sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        sharedAvailability = sharedWaypoints.availability();
        publishButton = null;

        final int left = width / 2 - 100;
        final int top = Math.max(54, height / 2 - (sharedAvailability.enabled() ? 42 : 30));
        int buttonY = top;
        if (sharedAvailability.enabled()) {
            publishButton = addDrawableChild(Widgets.button(
                left, buttonY, 200, 20,
                Texts.translatable("confluxmap.screen.waypoint.publish"),
                button -> MinecraftClient.getInstance().setScreen(
                    new WaypointShareConfirmScreen(parent, waypoint, WaypointShareConfirmScreen.Target.PUBLIC)
                )
            ));
            updatePublishButton();
            buttonY += 24;
        }
        addDrawableChild(Widgets.button(
            left, buttonY, 200, 20,
            Texts.translatable("confluxmap.screen.waypoint.send_chat"),
            button -> MinecraftClient.getInstance().setScreen(
                new WaypointShareConfirmScreen(parent, waypoint, WaypointShareConfirmScreen.Target.CHAT)
            )
        ));
        buttonY += 30;
        addDrawableChild(Widgets.button(
            left, buttonY, 200, 20,
            Texts.translatable("confluxmap.screen.waypoint.cancel"),
            button -> onClose()
        ));
    }

    @Override
    public void onClose() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (sharedAvailability == null || availability.enabled() != sharedAvailability.enabled()) {
            rebuild();
            return;
        }
        sharedAvailability = availability;
        if (publishButton != null) {
            updatePublishButton();
        }
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        renderBackground(matrices);
        final String title = textRenderer.trimToWidth(getTitle().getString() + ": " + waypoint.name, Math.max(40, width - 32));
        textRenderer.drawWithShadow(
            matrices, title, width / 2f - textRenderer.getWidth(title) / 2f, 24, 0xFFFFFFFF
        );
        if (sharedAvailability != null && sharedAvailability.enabled()) {
            final String status = textRenderer.trimToWidth(
                Texts.translatable(statusKey()).getString(), Math.max(40, width - 24)
            );
            textRenderer.drawWithShadow(
                matrices,
                status,
                width / 2f - textRenderer.getWidth(status) / 2f,
                height - 18,
                0xFFB8B8B8
            );
        }
        super.render(matrices, mouseX, mouseY, tickDelta);
    }

    private String statusKey() {
        if (sharedWaypoints.isLocationShared(waypoint)) {
            return "confluxmap.screen.waypoint.duplicate_location";
        }
        if (sharedWaypoints.isCreatePending(waypoint)) {
            return "confluxmap.screen.waypoint.publish_pending_message";
        }
        return sharedAvailability.ready()
            ? "confluxmap.shared_waypoints.status.enabled"
            : "confluxmap.shared_waypoints.status.syncing";
    }

    private void updatePublishButton() {
        final boolean shared = sharedWaypoints.isLocationShared(waypoint);
        final boolean pending = sharedWaypoints.isCreatePending(waypoint);
        publishButton.active = sharedAvailability.ready() && !shared && !pending;
        publishButton.setMessage(Texts.translatable(
            shared
                ? "confluxmap.screen.waypoint.already_shared"
                : pending
                    ? "confluxmap.screen.waypoint.publish_pending"
                    : "confluxmap.screen.waypoint.publish"
        ));
    }
}
