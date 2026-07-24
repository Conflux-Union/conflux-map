package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.compat.Texts;
import java.math.BigDecimal;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;

/** Chooses the ownership/audience for a waypoint created from the fullscreen map. */
public final class WaypointCreateTargetScreen extends ConfluxScreen {
    private final Screen parent;
    private final DimensionId dimensionId;
    private final double x;
    private final double y;
    private final double z;
    private final SharedWaypointClient sharedWaypoints;
    private ButtonWidget publicButton;
    private SharedWaypointAvailability sharedAvailability;

    public WaypointCreateTargetScreen(
        final Screen parent,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z
    ) {
        super(Texts.translatable("confluxmap.screen.waypoint.choose_target"));
        this.parent = parent;
        this.dimensionId = dimensionId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sharedWaypoints = ConfluxMapClient.get().sharedWaypoints();
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        sharedAvailability = sharedWaypoints.availability();
        publicButton = null;

        final int left = width / 2 - 100;
        final int top = Math.max(48, height / 2 - (sharedAvailability.enabled() ? 54 : 42));
        int buttonY = top;
        addDrawableChild(Widgets.button(
            left, buttonY, 200, 20,
            Texts.translatable("confluxmap.screen.waypoint.create_local"),
            button -> MinecraftClient.getInstance().setScreen(
                WaypointEditScreen.forCreate(parent, dimensionId, x, y, z)
            )
        ));
        buttonY += 24;
        if (sharedAvailability.enabled()) {
            publicButton = addDrawableChild(Widgets.button(
                left, buttonY, 200, 20,
                Texts.translatable("confluxmap.screen.waypoint.create_public"),
                button -> MinecraftClient.getInstance().setScreen(
                    WaypointEditScreen.forPublicCreate(parent, dimensionId, x, y, z)
                )
            ));
            publicButton.active = sharedAvailability.ready();
            buttonY += 24;
        }
        addDrawableChild(Widgets.button(
            left, buttonY, 200, 20,
            Texts.translatable("confluxmap.screen.waypoint.create_chat"),
            button -> MinecraftClient.getInstance().setScreen(
                WaypointEditScreen.forChatCreate(parent, dimensionId, x, y, z)
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
        if (publicButton != null) {
            publicButton.active = availability.ready();
        }
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = textRenderer.trimToWidth(getTitle().getString(), Math.max(40, width - 24));
        draw.drawTextWithShadow(
            textRenderer, title, width / 2f - textRenderer.getWidth(title) / 2f, 20, 0xFFFFFFFF
        );
        final String coords = textRenderer.trimToWidth(Texts.translatable(
            "confluxmap.screen.waypoint.preview.coords",
            formatCoordinate(x), formatCoordinate(y), formatCoordinate(z)
        ).getString(), Math.max(40, width - 24));
        draw.drawTextWithShadow(
            textRenderer, coords, width / 2f - textRenderer.getWidth(coords) / 2f, 34, 0xFFB8B8B8
        );
        if (sharedAvailability != null && sharedAvailability.enabled()) {
            final String status = textRenderer.trimToWidth(
                Texts.translatable(statusKey()).getString(), Math.max(40, width - 24)
            );
            draw.drawTextWithShadow(
                textRenderer,
                status,
                width / 2f - textRenderer.getWidth(status) / 2f,
                height - 18,
                0xFFB8B8B8
            );
        }
    }

    private String statusKey() {
        return sharedAvailability.ready()
            ? "confluxmap.shared_waypoints.status.enabled"
            : "confluxmap.shared_waypoints.status.syncing";
    }

    private static String formatCoordinate(final double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
