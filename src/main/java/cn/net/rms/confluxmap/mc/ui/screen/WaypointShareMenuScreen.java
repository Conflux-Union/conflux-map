package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointClientState;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;

/** Explicit choice between server publication and ordinary chat sharing. */
public final class WaypointShareMenuScreen extends Screen {
    private final Screen parent;
    private final Waypoint waypoint;
    private final SharedWaypointClient sharedWaypoints;
    private ButtonWidget publishButton;

    public WaypointShareMenuScreen(final Screen parent, final Waypoint waypoint) {
        super(new TranslatableText("confluxmap.screen.waypoint.share"));
        this.parent = parent;
        this.waypoint = waypoint.copy();
        this.sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
    }

    @Override
    protected void init() {
        final int left = width / 2 - 100;
        final int top = Math.max(54, height / 2 - 42);
        publishButton = addDrawableChild(new ButtonWidget(
            left, top, 200, 20,
            new TranslatableText("confluxmap.screen.waypoint.publish"),
            button -> MinecraftClient.getInstance().setScreen(
                new WaypointShareConfirmScreen(parent, waypoint, WaypointShareConfirmScreen.Target.PUBLIC)
            )
        ));
        refreshPublishButton();
        addDrawableChild(new ButtonWidget(
            left, top + 24, 200, 20,
            new TranslatableText("confluxmap.screen.waypoint.send_chat"),
            button -> MinecraftClient.getInstance().setScreen(
                new WaypointShareConfirmScreen(parent, waypoint, WaypointShareConfirmScreen.Target.CHAT)
            )
        ));
        addDrawableChild(new ButtonWidget(
            left, top + 54, 200, 20,
            new TranslatableText("confluxmap.screen.waypoint.cancel"),
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
        refreshPublishButton();
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        renderBackground(matrices);
        final String title = textRenderer.trimToWidth(getTitle().getString() + ": " + waypoint.name, Math.max(40, width - 32));
        textRenderer.drawWithShadow(
            matrices, title, width / 2f - textRenderer.getWidth(title) / 2f, 24, 0xFFFFFFFF
        );
        final String status = textRenderer.trimToWidth(
            new TranslatableText(statusKey()).getString(), Math.max(40, width - 24)
        );
        textRenderer.drawWithShadow(
            matrices,
            status,
            width / 2f - textRenderer.getWidth(status) / 2f,
            height - 18,
            0xFFB8B8B8
        );
        super.render(matrices, mouseX, mouseY, tickDelta);
    }

    private String statusKey() {
        return switch (sharedWaypoints.state()) {
            case UNKNOWN, HANDSHAKE -> "confluxmap.shared_waypoints.status.connecting";
            case UNSUPPORTED -> "confluxmap.shared_waypoints.status.unsupported";
            case SUPPORTED_DISABLED -> "confluxmap.shared_waypoints.status.disabled";
            case ENABLED -> sharedWaypoints.isSynchronized()
                ? "confluxmap.shared_waypoints.status.enabled"
                : "confluxmap.shared_waypoints.status.syncing";
        };
    }

    private void refreshPublishButton() {
        if (publishButton != null) {
            publishButton.active = sharedWaypoints.state() == SharedWaypointClientState.State.ENABLED
                && sharedWaypoints.isSynchronized();
        }
    }
}
