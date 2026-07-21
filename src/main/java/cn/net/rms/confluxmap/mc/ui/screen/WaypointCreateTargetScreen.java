package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointClientState;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import java.math.BigDecimal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;

/** Chooses the ownership/audience for a waypoint created from the fullscreen map. */
public final class WaypointCreateTargetScreen extends Screen {
    private final Screen parent;
    private final DimensionId dimensionId;
    private final double x;
    private final double y;
    private final double z;
    private final SharedWaypointClient sharedWaypoints;
    private ButtonWidget publicButton;

    public WaypointCreateTargetScreen(
        final Screen parent,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z
    ) {
        super(new TranslatableText("confluxmap.screen.waypoint.choose_target"));
        this.parent = parent;
        this.dimensionId = dimensionId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
    }

    @Override
    protected void init() {
        final int left = width / 2 - 100;
        final int top = Math.max(48, height / 2 - 54);
        addDrawableChild(new ButtonWidget(
            left, top, 200, 20,
            new TranslatableText("confluxmap.screen.waypoint.create_local"),
            button -> MinecraftClient.getInstance().setScreen(
                WaypointEditScreen.forCreate(parent, dimensionId, x, y, z)
            )
        ));
        publicButton = addDrawableChild(new ButtonWidget(
            left, top + 24, 200, 20,
            new TranslatableText("confluxmap.screen.waypoint.create_public"),
            button -> MinecraftClient.getInstance().setScreen(
                WaypointEditScreen.forPublicCreate(parent, dimensionId, x, y, z)
            )
        ));
        refreshPublicButton();
        addDrawableChild(new ButtonWidget(
            left, top + 48, 200, 20,
            new TranslatableText("confluxmap.screen.waypoint.create_chat"),
            button -> MinecraftClient.getInstance().setScreen(
                WaypointEditScreen.forChatCreate(parent, dimensionId, x, y, z)
            )
        ));
        addDrawableChild(new ButtonWidget(
            left, top + 78, 200, 20,
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
        refreshPublicButton();
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        renderBackground(matrices);
        final String title = textRenderer.trimToWidth(getTitle().getString(), Math.max(40, width - 24));
        textRenderer.drawWithShadow(
            matrices, title, width / 2f - textRenderer.getWidth(title) / 2f, 20, 0xFFFFFFFF
        );
        final String coords = textRenderer.trimToWidth(new TranslatableText(
            "confluxmap.screen.waypoint.preview.coords",
            formatCoordinate(x), formatCoordinate(y), formatCoordinate(z)
        ).getString(), Math.max(40, width - 24));
        textRenderer.drawWithShadow(
            matrices, coords, width / 2f - textRenderer.getWidth(coords) / 2f, 34, 0xFFB8B8B8
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

    private void refreshPublicButton() {
        if (publicButton != null) {
            publicButton.active = sharedWaypoints.state() == SharedWaypointClientState.State.ENABLED
                && sharedWaypoints.isSynchronized();
        }
    }

    private static String formatCoordinate(final double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
