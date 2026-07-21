package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import java.math.BigDecimal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.TranslatableText;

/** Explicit preview and confirmation boundary for every outward waypoint share. */
public final class WaypointShareConfirmScreen extends Screen {
    public enum Target { PUBLIC, CHAT }

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_TEXT_COLOR = 0xFFB8B8B8;
    private static final int ERROR_TEXT_COLOR = 0xFFFF7777;

    private final Screen parent;
    private final Waypoint waypoint;
    private final Target target;
    private final SharedWaypointClient sharedWaypoints;
    private ButtonWidget confirmButton;
    private String errorKey;

    public WaypointShareConfirmScreen(final Screen parent, final Waypoint waypoint, final Target target) {
        super(new TranslatableText(
            target == Target.PUBLIC
                ? "confluxmap.screen.waypoint.confirm_public"
                : "confluxmap.screen.waypoint.confirm_chat"
        ));
        this.parent = parent;
        this.waypoint = waypoint.copy();
        this.target = target;
        this.sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
    }

    @Override
    protected void init() {
        confirmButton = null;
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (target == Target.PUBLIC && !availability.enabled()) {
            return;
        }

        final int centerX = width / 2;
        confirmButton = addDrawableChild(new ButtonWidget(
            centerX - 104,
            height - 32,
            100,
            20,
            new TranslatableText(
                target == Target.PUBLIC
                    ? "confluxmap.screen.waypoint.publish"
                    : "confluxmap.screen.waypoint.send_chat"
            ),
            button -> confirm()
        ));
        if (target == Target.PUBLIC) {
            confirmButton.active = availability.ready();
        }
        addDrawableChild(new ButtonWidget(
            centerX + 4,
            height - 32,
            100,
            20,
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
        super.tick();
        if (target != Target.PUBLIC) {
            return;
        }
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (!availability.enabled()) {
            onClose();
            return;
        }
        if (confirmButton != null) {
            confirmButton.active = availability.ready();
        }
    }

    private void confirm() {
        errorKey = null;
        if (target == Target.PUBLIC) {
            if (!sharedWaypoints.create(waypoint)) {
                errorKey = "confluxmap.screen.waypoint.public_unavailable";
                return;
            }
            onClose();
            return;
        }

        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            errorKey = "confluxmap.screen.waypoint.chat_unavailable";
            return;
        }
        try {
            client.player.sendChatMessage(WaypointChatCodec.format(
                waypoint.name,
                waypoint.dimensionId,
                waypoint.x,
                waypoint.y,
                waypoint.z
            ));
            onClose();
        } catch (final IllegalArgumentException e) {
            errorKey = "confluxmap.screen.waypoint.invalid_share";
        }
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        renderBackground(matrices);
        drawCentered(matrices, getTitle().getString(), 22, TEXT_COLOR);
        drawCentered(matrices, new TranslatableText(
            "confluxmap.screen.waypoint.preview.name", waypoint.name
        ).getString(), 50, TEXT_COLOR);
        drawCentered(matrices, new TranslatableText(
            "confluxmap.screen.waypoint.preview.dimension", waypoint.dimensionId.toString()
        ).getString(), 66, TEXT_COLOR);
        drawCentered(matrices, new TranslatableText(
            "confluxmap.screen.waypoint.preview.coords",
            formatCoordinate(waypoint.x),
            formatCoordinate(waypoint.y),
            formatCoordinate(waypoint.z)
        ).getString(), 82, TEXT_COLOR);
        drawCentered(matrices, new TranslatableText(
            target == Target.PUBLIC
                ? "confluxmap.screen.waypoint.preview.audience_public"
                : "confluxmap.screen.waypoint.preview.audience_chat"
        ).getString(), 106, MUTED_TEXT_COLOR);
        if (target == Target.PUBLIC) {
            drawCentered(
                matrices,
                new TranslatableText("confluxmap.screen.waypoint.public_immutable").getString(),
                122,
                MUTED_TEXT_COLOR
            );
        }
        if (errorKey != null) {
            drawCentered(matrices, new TranslatableText(errorKey).getString(), height - 50, ERROR_TEXT_COLOR);
        }
        super.render(matrices, mouseX, mouseY, tickDelta);
    }

    private void drawCentered(final MatrixStack matrices, final String value, final int y, final int color) {
        final String text = textRenderer.trimToWidth(value, Math.max(40, width - 32));
        textRenderer.drawWithShadow(matrices, text, width / 2f - textRenderer.getWidth(text) / 2f, y, color);
    }

    private static String formatCoordinate(final double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
