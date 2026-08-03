package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import java.math.BigDecimal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;

/** Explicit preview and confirmation boundary for every outward waypoint share. */
public final class WaypointShareConfirmScreen extends ConfluxScreen {
    public enum Target { PUBLIC, CHAT }

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_TEXT_COLOR = 0xFFB8B8B8;
    private static final int ERROR_TEXT_COLOR = 0xFFFF7777;

    private final Screen parent;
    private final Waypoint waypoint;
    private Target target;
    private final SharedWaypointClient sharedWaypoints;
    private final String confluxPreview;
    private final String xaeroPreview;
    private ButtonWidget confirmButton;
    private String errorKey;

    public WaypointShareConfirmScreen(final Screen parent, final Waypoint waypoint, final Target target) {
        super(Texts.translatable("confluxmap.screen.waypoint.share"));
        this.parent = parent;
        this.waypoint = waypoint.copy();
        this.target = target;
        this.sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
        // Chat sharing previews the exact outgoing messages, so name truncation, coordinate
        // flooring and Xaero color snapping are visible before anything is sent.
        String conflux = null;
        String xaero = null;
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
        this.confluxPreview = conflux;
        this.xaeroPreview = xaero;
    }

    @Override
    protected void init() {
        confirmButton = null;
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (target == Target.PUBLIC && !availability.visible()) {
            target = Target.CHAT;
        }

        final int centerX = width / 2;
        if (availability.visible()) {
            final ButtonWidget publicTarget = addDrawableChild(Widgets.button(
                centerX - 104,
                38,
                100,
                20,
                targetLabel(Target.PUBLIC),
                ignored -> selectTarget(Target.PUBLIC)
            ));
            publicTarget.active = !availability.disabledByServer();
            setDisabledTooltip(
                publicTarget,
                availability.disabledByServer()
                    ? "confluxmap.shared_waypoints.disabled_by_server"
                    : null
            );
        }
        addDrawableChild(Widgets.button(
            availability.visible() ? centerX + 4 : centerX - 50,
            38,
            100,
            20,
            targetLabel(Target.CHAT),
            ignored -> selectTarget(Target.CHAT)
        ));
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
        setEnterAction(() -> confirmButton != null && confirmButton.active, this::confirm);
    }

    private Text targetLabel(final Target candidate) {
        final String value = Texts.translatable(
            candidate == Target.PUBLIC
                ? "confluxmap.screen.waypoint.publish"
                : "confluxmap.screen.waypoint.send_chat"
        ).getString();
        return Texts.literal(candidate == target ? "[" + value + "]" : value);
    }

    private void selectTarget(final Target selected) {
        if (selected == Target.PUBLIC && sharedWaypoints.availability().disabledByServer()) {
            return;
        }
        target = selected;
        errorKey = null;
        clearChildren();
        init();
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
        if (target != Target.PUBLIC) {
            return;
        }
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (!availability.visible()) {
            target = Target.CHAT;
            clearChildren();
            init();
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
        setDisabledTooltip(
            confirmButton,
            availability.disabledByServer()
                ? "confluxmap.shared_waypoints.disabled_by_server"
                : null
        );
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
        MinecraftAccess.sendChatMessage(client, confluxPreview);
        MinecraftAccess.sendChatMessage(client, xaeroPreview);
        onClose();
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 18, TEXT_COLOR);
        if (target == Target.CHAT && confluxPreview != null && xaeroPreview != null) {
            int y = drawWrapped(draw, Texts.translatable(
                "confluxmap.screen.waypoint.preview.chat_messages"
            ).getString(), 68, MUTED_TEXT_COLOR);
            y = drawWrapped(draw, confluxPreview, y + 4, TEXT_COLOR);
            y = drawWrapped(draw, xaeroPreview, y + 6, TEXT_COLOR);
            drawCentered(draw, Texts.translatable(
                "confluxmap.screen.waypoint.preview.audience_chat"
            ).getString(), y + 10, MUTED_TEXT_COLOR);
        } else {
            drawCentered(draw, Texts.translatable(
                "confluxmap.screen.waypoint.preview.name", waypoint.name
            ).getString(), 68, TEXT_COLOR);
            drawCentered(draw, Texts.translatable(
                "confluxmap.screen.waypoint.preview.dimension", waypoint.dimensionId.toString()
            ).getString(), 84, TEXT_COLOR);
            drawCentered(draw, Texts.translatable(
                "confluxmap.screen.waypoint.preview.coords",
                formatCoordinate(waypoint.x),
                formatCoordinate(waypoint.y),
                formatCoordinate(waypoint.z)
            ).getString(), 100, TEXT_COLOR);
            drawCentered(draw, Texts.translatable(
                target == Target.PUBLIC
                    ? "confluxmap.screen.waypoint.preview.audience_public"
                    : "confluxmap.screen.waypoint.preview.audience_chat"
            ).getString(), 124, MUTED_TEXT_COLOR);
            if (target == Target.PUBLIC) {
                drawCentered(
                    draw,
                    Texts.translatable("confluxmap.screen.waypoint.public_immutable").getString(),
                    140,
                    MUTED_TEXT_COLOR
                );
            }
        }
        if (errorKey != null) {
            drawCentered(draw, Texts.translatable(errorKey).getString(), height - 50, ERROR_TEXT_COLOR);
        }
    }

    private void drawCentered(final GuiDraw draw, final String value, final int y, final int color) {
        final String text = this.textRenderer.trimToWidth(value, Math.max(40, width - 32));
        draw.drawTextWithShadow(this.textRenderer, text, width / 2f - this.textRenderer.getWidth(text) / 2f, y, color);
    }

    /** Draws every wrapped line of {@code value} and returns the y below the last line. */
    private int drawWrapped(final GuiDraw draw, final String value, final int y, final int color) {
        int lineY = y;
        for (final OrderedText line : this.textRenderer.wrapLines(StringVisitable.plain(value), Math.max(40, width - 32))) {
            draw.drawTextWithShadow(
                this.textRenderer, line, width / 2f - this.textRenderer.getWidth(line) / 2f, lineY, color
            );
            lineY += this.textRenderer.fontHeight + 1;
        }
        return lineY;
    }

    private static String formatCoordinate(final double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
