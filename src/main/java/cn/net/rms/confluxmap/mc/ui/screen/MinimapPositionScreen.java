package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.MinimapPlacement;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.MinecraftClient;
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//$$ import net.minecraft.client.input.KeyInput;
//#endif
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

/** Explicit edit mode for dragging the minimap without consuming normal gameplay clicks. */
public final class MinimapPositionScreen extends ConfluxScreen {
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_GAP = 6;
    private static final int BUTTON_HEIGHT = 20;
    private static final int OUTLINE_COLOR = 0xFFFFD83D;
    private static final int OUTLINE_SHADOW = 0xB0000000;

    private final Screen parent;
    private final ConfluxConfig config;
    private final ConfigIo configIo;

    private MinimapPlacement.Drag drag;

    public MinimapPositionScreen(
        final Screen parent,
        final ConfluxConfig config,
        final ConfigIo configIo
    ) {
        super(Texts.translatable("confluxmap.screen.minimap_position.title"));
        this.parent = parent;
        this.config = config;
        this.configIo = configIo;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int buttonWidth = Math.min(BUTTON_WIDTH, Math.max(1, (width - BUTTON_GAP - 16) / 2));
        final int totalWidth = buttonWidth * 2 + BUTTON_GAP;
        final int x = width / 2 - totalWidth / 2;
        final int y = height - BUTTON_HEIGHT - 8;
        addDrawableChild(Widgets.button(
            x,
            y,
            buttonWidth,
            BUTTON_HEIGHT,
            Texts.translatable("confluxmap.screen.minimap_position.reset"),
            button -> resetPosition()
        ));
        addDrawableChild(Widgets.button(
            x + buttonWidth + BUTTON_GAP,
            y,
            buttonWidth,
            BUTTON_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.done"),
            button -> onClose()
        ));
    }

    @Override
    public void onClose() {
        configIo.save(config);
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    //#if MC>=12109
    //$$ public boolean keyPressed(final KeyInput input) {
    //$$     final int keyCode = input.key();
    //#else
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
    //#endif
        int deltaX = 0;
        int deltaY = 0;
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT:
                deltaX = -1;
                break;
            case GLFW.GLFW_KEY_RIGHT:
                deltaX = 1;
                break;
            case GLFW.GLFW_KEY_UP:
                deltaY = -1;
                break;
            case GLFW.GLFW_KEY_DOWN:
                deltaY = 1;
                break;
            default:
                break;
        }
        if (deltaX != 0 || deltaY != 0) {
            applyPosition(MinimapPlacement.nudge(
                width,
                height,
                config.minimapSize,
                currentPosition(),
                deltaX,
                deltaY
            ));
            return true;
        }
        //#if MC>=12109
        //$$ return super.keyPressed(input);
        //#else
        return super.keyPressed(keyCode, scanCode, modifiers);
        //#endif
    }

    @Override
    //#if MC>=12109
    //$$ public boolean mouseClicked(final Click click, final boolean doubledClick) {
    //$$     final double mouseX = click.x();
    //$$     final double mouseY = click.y();
    //$$     final int button = click.button();
    //#else
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
    //#endif
        //#if MC>=12109
        //$$ if (super.mouseClicked(click, doubledClick)) {
        //#else
        if (super.mouseClicked(mouseX, mouseY, button)) {
        //#endif
            return true;
        }
        if (button != 0) {
            return false;
        }
        drag = MinimapPlacement.startDrag(currentLayout(), mouseX, mouseY);
        return drag != null;
    }

    @Override
    //#if MC>=12109
    //$$ public boolean mouseDragged(final Click click, final double deltaX, final double deltaY) {
    //$$     final double mouseX = click.x();
    //$$     final double mouseY = click.y();
    //$$     final int button = click.button();
    //#else
    public boolean mouseDragged(
        final double mouseX,
        final double mouseY,
        final int button,
        final double deltaX,
        final double deltaY
    ) {
    //#endif
        if (button == 0 && drag != null) {
            applyPosition(MinimapPlacement.dragTo(width, height, config.minimapSize, drag, mouseX, mouseY));
            return true;
        }
        //#if MC>=12109
        //$$ return super.mouseDragged(click, deltaX, deltaY);
        //#else
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        //#endif
    }

    @Override
    //#if MC>=12109
    //$$ public boolean mouseReleased(final Click click) {
    //$$     final int button = click.button();
    //#else
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
    //#endif
        if (button == 0 && drag != null) {
            drag = null;
            return true;
        }
        //#if MC>=12109
        //$$ return super.mouseReleased(click);
        //#else
        return super.mouseReleased(mouseX, mouseY, button);
        //#endif
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        final MinimapPlacement.Layout layout = currentLayout();
        draw.fill(layout.x() - 2, layout.y() - 2, layout.x() + layout.size() + 2, layout.y() - 1, OUTLINE_SHADOW);
        draw.fill(layout.x() - 2, layout.y() + layout.size() + 1, layout.x() + layout.size() + 2,
            layout.y() + layout.size() + 2, OUTLINE_SHADOW);
        draw.fill(layout.x() - 2, layout.y() - 2, layout.x() - 1, layout.y() + layout.size() + 2, OUTLINE_SHADOW);
        draw.fill(layout.x() + layout.size() + 1, layout.y() - 2, layout.x() + layout.size() + 2,
            layout.y() + layout.size() + 2, OUTLINE_SHADOW);
        draw.fill(layout.x() - 1, layout.y() - 1, layout.x() + layout.size() + 1, layout.y(), OUTLINE_COLOR);
        draw.fill(layout.x() - 1, layout.y() + layout.size(), layout.x() + layout.size() + 1,
            layout.y() + layout.size() + 1, OUTLINE_COLOR);
        draw.fill(layout.x() - 1, layout.y() - 1, layout.x(), layout.y() + layout.size() + 1, OUTLINE_COLOR);
        draw.fill(layout.x() + layout.size(), layout.y() - 1, layout.x() + layout.size() + 1,
            layout.y() + layout.size() + 1, OUTLINE_COLOR);

        drawCentered(draw, getTitle().getString(), 8);
        drawCentered(draw, Texts.translatable("confluxmap.screen.minimap_position.instructions").getString(), 20);
    }

    private MinimapPlacement.Layout currentLayout() {
        return MinimapPlacement.resolve(
            width,
            height,
            config.minimapSize,
            config.minimapPositionX,
            config.minimapPositionY
        );
    }

    private MinimapPlacement.Position currentPosition() {
        return new MinimapPlacement.Position(config.minimapPositionX, config.minimapPositionY);
    }

    private void applyPosition(final MinimapPlacement.Position position) {
        config.minimapPositionX = position.x();
        config.minimapPositionY = position.y();
    }

    private void resetPosition() {
        applyPosition(new MinimapPlacement.Position(1.0, 0.0));
        drag = null;
    }

    private void drawCentered(final GuiDraw draw, final String text, final int y) {
        final int textWidth = this.textRenderer.getWidth(text);
        draw.fill(width / 2 - textWidth / 2 - 3, y - 2, width / 2 + textWidth / 2 + 3,
            y + this.textRenderer.fontHeight + 2, 0x90000000);
        draw.drawTextWithShadow(this.textRenderer, text, width / 2f - textWidth / 2f, y, 0xFFFFFFFF);
    }
}
