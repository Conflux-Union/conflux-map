package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.compat.Texts;
import java.math.BigDecimal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;

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
    private final String confluxPreview;
    private final String xaeroPreview;
    private ButtonWidget confirmButton;
    private String errorKey;

    public WaypointShareConfirmScreen(final Screen parent, final Waypoint waypoint, final Target target) {
        super(Texts.translatable(
            target == Target.PUBLIC
                ? "confluxmap.screen.waypoint.confirm_public"
                : "confluxmap.screen.waypoint.confirm_chat"
        ));
        this.parent = parent;
        this.waypoint = waypoint.copy();
        this.target = target;
        this.sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
        // Chat sharing previews the exact outgoing messages, so name truncation, coordinate
        // flooring and Xaero color snapping are visible before anything is sent.
        String conflux = null;
        String xaero = null;
        if (target == Target.CHAT) {
            try {
                conflux = WaypointChatCodec.format(
                    this.waypoint.name, this.waypoint.dimensionId,
                    this.waypoint.x, this.waypoint.y, this.waypoint.z
                );
                xaero = WaypointChatCodec.formatXaero(
                    this.waypoint.name, this.waypoint.dimensionId,
                    this.waypoint.x, this.waypoint.y, this.waypoint.z, this.waypoint.colorArgb
                );
            } catch (final IllegalArgumentException e) {
                conflux = null;
                xaero = null;
                errorKey = "confluxmap.screen.waypoint.invalid_share";
            }
        }
        this.confluxPreview = conflux;
        this.xaeroPreview = xaero;
    }

    @Override
    protected void init() {
        confirmButton = null;
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (target == Target.PUBLIC && !availability.enabled()) {
            return;
        }

        final int centerX = width / 2;
        confirmButton = addDrawableChild(Widgets.button(
            centerX - 104,
            height - 32,
            100,
            20,
            Texts.translatable(
                target == Target.PUBLIC
                    ? "confluxmap.screen.waypoint.publish"
                    : "confluxmap.screen.waypoint.send_chat"
            ),
            button -> confirm()
        ));
        if (target == Target.PUBLIC) {
            updatePublicButton(availability);
        } else {
            confirmButton.active = confluxPreview != null && xaeroPreview != null;
        }
        addDrawableChild(Widgets.button(
            centerX + 4,
            height - 32,
            100,
            20,
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
        if (target != Target.PUBLIC) {
            return;
        }
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (!availability.enabled()) {
            onClose();
            return;
        }
        if (confirmButton != null) {
            updatePublicButton(availability);
        }
    }

    private void updatePublicButton(final SharedWaypointAvailability availability) {
        final boolean shared = sharedWaypoints.isLocationShared(waypoint);
        final boolean pending = sharedWaypoints.isCreatePending(waypoint);
        confirmButton.active = availability.ready() && !shared && !pending;
        confirmButton.setMessage(Texts.translatable(
            shared
                ? "confluxmap.screen.waypoint.already_shared"
                : pending
                    ? "confluxmap.screen.waypoint.publish_pending"
                    : "confluxmap.screen.waypoint.publish"
        ));
    }

    private void confirm() {
        errorKey = null;
        if (target == Target.PUBLIC) {
            if (sharedWaypoints.isLocationShared(waypoint)) {
                errorKey = "confluxmap.screen.waypoint.duplicate_location";
                return;
            }
            if (sharedWaypoints.isCreatePending(waypoint)) {
                errorKey = "confluxmap.screen.waypoint.publish_pending_message";
                return;
            }
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
        if (confluxPreview == null || xaeroPreview == null) {
            errorKey = "confluxmap.screen.waypoint.invalid_share";
            return;
        }
        client.player.sendChatMessage(confluxPreview);
        client.player.sendChatMessage(xaeroPreview);
        onClose();
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        renderBackground(matrices);
        drawCentered(matrices, getTitle().getString(), 22, TEXT_COLOR);
        if (target == Target.CHAT && confluxPreview != null && xaeroPreview != null) {
            int y = drawWrapped(matrices, Texts.translatable(
                "confluxmap.screen.waypoint.preview.chat_messages"
            ).getString(), 50, MUTED_TEXT_COLOR);
            y = drawWrapped(matrices, confluxPreview, y + 4, TEXT_COLOR);
            y = drawWrapped(matrices, xaeroPreview, y + 6, TEXT_COLOR);
            drawCentered(matrices, Texts.translatable(
                "confluxmap.screen.waypoint.preview.audience_chat"
            ).getString(), y + 10, MUTED_TEXT_COLOR);
        } else {
            drawCentered(matrices, Texts.translatable(
                "confluxmap.screen.waypoint.preview.name", waypoint.name
            ).getString(), 50, TEXT_COLOR);
            drawCentered(matrices, Texts.translatable(
                "confluxmap.screen.waypoint.preview.dimension", waypoint.dimensionId.toString()
            ).getString(), 66, TEXT_COLOR);
            drawCentered(matrices, Texts.translatable(
                "confluxmap.screen.waypoint.preview.coords",
                formatCoordinate(waypoint.x),
                formatCoordinate(waypoint.y),
                formatCoordinate(waypoint.z)
            ).getString(), 82, TEXT_COLOR);
            drawCentered(matrices, Texts.translatable(
                target == Target.PUBLIC
                    ? "confluxmap.screen.waypoint.preview.audience_public"
                    : "confluxmap.screen.waypoint.preview.audience_chat"
            ).getString(), 106, MUTED_TEXT_COLOR);
            if (target == Target.PUBLIC) {
                drawCentered(
                    matrices,
                    Texts.translatable("confluxmap.screen.waypoint.public_immutable").getString(),
                    122,
                    MUTED_TEXT_COLOR
                );
            }
        }
        if (errorKey != null) {
            drawCentered(matrices, Texts.translatable(errorKey).getString(), height - 50, ERROR_TEXT_COLOR);
        }
        super.render(matrices, mouseX, mouseY, tickDelta);
    }

    private void drawCentered(final MatrixStack matrices, final String value, final int y, final int color) {
        final String text = textRenderer.trimToWidth(value, Math.max(40, width - 32));
        textRenderer.drawWithShadow(matrices, text, width / 2f - textRenderer.getWidth(text) / 2f, y, color);
    }

    /** Draws every wrapped line of {@code value} and returns the y below the last line. */
    private int drawWrapped(final MatrixStack matrices, final String value, final int y, final int color) {
        int lineY = y;
        for (final OrderedText line : textRenderer.wrapLines(StringVisitable.plain(value), Math.max(40, width - 32))) {
            textRenderer.drawWithShadow(
                matrices, line, width / 2f - textRenderer.getWidth(line) / 2f, lineY, color
            );
            lineY += textRenderer.fontHeight + 1;
        }
        return lineY;
    }

    private static String formatCoordinate(final double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
