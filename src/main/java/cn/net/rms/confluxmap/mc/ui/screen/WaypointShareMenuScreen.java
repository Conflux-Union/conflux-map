package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.compat.Texts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;

/** Explicit choice between server publication and ordinary chat sharing. */
public final class WaypointShareMenuScreen extends ConfluxScreen {
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
        final int top = Math.max(54, height / 2 - (sharedAvailability.visible() ? 42 : 30));
        int buttonY = top;
        if (sharedAvailability.visible()) {
            publishButton = addDrawableChild(Widgets.button(
                left, buttonY, 200, 20,
                Texts.translatable("confluxmap.screen.waypoint.publish"),
                button -> MinecraftAccess.setScreen(MinecraftClient.getInstance(),
                    new WaypointShareConfirmScreen(parent, waypoint, WaypointShareConfirmScreen.Target.PUBLIC)
                )
            ));
            updatePublishButton();
            buttonY += 24;
        }
        addDrawableChild(Widgets.button(
            left, buttonY, 200, 20,
            Texts.translatable("confluxmap.screen.waypoint.send_chat"),
            button -> MinecraftAccess.setScreen(MinecraftClient.getInstance(),
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
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (sharedAvailability == null || availability.visible() != sharedAvailability.visible()) {
            rebuild();
            return;
        }
        sharedAvailability = availability;
        if (publishButton != null) {
            updatePublishButton();
        }
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = this.textRenderer.trimToWidth(getTitle().getString() + ": " + waypoint.name, Math.max(40, width - 32));
        draw.drawTextWithShadow(
            this.textRenderer, title, width / 2f - this.textRenderer.getWidth(title) / 2f, 24, 0xFFFFFFFF
        );
        if (sharedAvailability != null && sharedAvailability.visible()) {
            final String status = this.textRenderer.trimToWidth(
                Texts.translatable(statusKey()).getString(), Math.max(40, width - 24)
            );
            draw.drawTextWithShadow(
                this.textRenderer,
                status,
                width / 2f - this.textRenderer.getWidth(status) / 2f,
                height - 18,
                0xFFB8B8B8
            );
        }
    }

    private String statusKey() {
        if (sharedAvailability.disabledByServer()) {
            return "confluxmap.shared_waypoints.disabled";
        }
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
        setDisabledTooltip(
            publishButton,
            sharedAvailability.disabledByServer()
                ? "confluxmap.shared_waypoints.disabled_by_server"
                : null
        );
        publishButton.setMessage(Texts.translatable(
            shared
                ? "confluxmap.screen.waypoint.already_shared"
                : pending
                    ? "confluxmap.screen.waypoint.publish_pending"
                    : "confluxmap.screen.waypoint.publish"
        ));
    }
}
