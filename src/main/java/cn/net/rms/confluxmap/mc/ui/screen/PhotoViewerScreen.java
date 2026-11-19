package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MouseButtons;
import cn.net.rms.confluxmap.core.waypoint.library.PhotoRotation;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.text.Text;

/** Shared display state for the screenshot and waypoint image viewers. */
abstract class PhotoViewerScreen extends ConfluxScreen {
    private static final double SNAP_ENTER = 3;
    private static final double SNAP_EXIT = 6;
    private static final double PIVOT_DEAD_ZONE = 8;
    private int x, y, w, h;
    private int dragButton = -1;
    private double panX, panY, rawRotation;
    private double snapped = Double.NaN;
    private double savedRotation;
    private float zoom = 1;

    PhotoViewerScreen(Text title) { super(title); }

    protected abstract boolean imageReady();

    protected static boolean gesturesEnabled() {
        return ConfluxMapClient.get().waypointListMemory().options().photoGestures;
    }

    protected final void resetView(double rotation) {
        panX = panY = 0;
        zoom = 1;
        dragButton = -1;
        rawRotation = savedRotation = rotation;
        snapped = Double.NaN;
    }

    protected final double rotation() {
        return PhotoRotation.normalize(Double.isNaN(snapped) ? rawRotation : snapped);
    }

    protected final boolean rotationChanged() { return rotation() != savedRotation; }
    protected final void markRotationSaved(double rotation) { savedRotation = rotation; }

    protected final void zoom(double steps) {
        zoom = (float) Math.max(1, Math.min(8, zoom * Math.pow(1.5, steps)));
    }

    protected final void drawPhoto(GuiDraw draw, WaypointPhotoTexture texture, int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        texture.draw(draw, x, y, w, h, zoom, panX, panY, rotation());
    }

    //#if MC>=260100
    //$$ @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubleClick) {
    //$$     return startDrag(click.x(), click.y(), click.button()) || super.mouseClicked(click, doubleClick);
    //$$ }
    //$$ @Override public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent click, double dx, double dy) {
    //$$     return dragPhoto(click.x(), click.y(), click.button(), dx, dy) || super.mouseDragged(click, dx, dy);
    //$$ }
    //$$ @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent click) {
    //$$     return finishDrag(click.button()) || super.mouseReleased(click);
    //$$ }
    //#elseif MC>=12109
    //$$ @Override public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubleClick) {
    //$$     return startDrag(click.x(), click.y(), click.button()) || super.mouseClicked(click, doubleClick);
    //$$ }
    //$$ @Override public boolean mouseDragged(net.minecraft.client.gui.Click click, double dx, double dy) {
    //$$     return dragPhoto(click.x(), click.y(), click.button(), dx, dy) || super.mouseDragged(click, dx, dy);
    //$$ }
    //$$ @Override public boolean mouseReleased(net.minecraft.client.gui.Click click) {
    //$$     return finishDrag(click.button()) || super.mouseReleased(click);
    //$$ }
    //#else
    @Override public boolean mouseClicked(double x, double y, int button) {
        return startDrag(x, y, button) || super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return dragPhoto(x, y, button, dx, dy) || super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        return finishDrag(button) || super.mouseReleased(x, y, button);
    }
    //#endif

    //#if MC>=12002
    //$$ @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
    //$$     return zoomByWheel(vertical) || super.mouseScrolled(x, y, horizontal, vertical);
    //$$ }
    //#else
    @Override public boolean mouseScrolled(double x, double y, double amount) {
        return zoomByWheel(amount) || super.mouseScrolled(x, y, amount);
    }
    //#endif

    private boolean zoomByWheel(double amount) {
        if (isClosing() || amount == 0 || !controlPressed()) return false;
        zoom(amount);
        return true;
    }

    private boolean startDrag(final double mouseX, final double mouseY, final int button) {
        if (canDrag() && (button == MouseButtons.LEFT || button == MouseButtons.RIGHT)
            && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
            dragButton = button;
            return true;
        }
        return false;
    }

    private boolean dragPhoto(final double mouseX, final double mouseY, final int button, final double dx, final double dy) {
        if (!canDrag() || button != dragButton) return false;
        if (dragButton == MouseButtons.LEFT) {
            panX = Math.max(-w * 0.45, Math.min(w * 0.45, panX + dx));
            panY = Math.max(-h * 0.45, Math.min(h * 0.45, panY + dy));
        } else {
            rotateDrag(mouseX - x - w / 2.0 - panX, mouseY - y - h / 2.0 - panY, dx, dy);
        }
        return true;
    }

    private boolean finishDrag(final int button) {
        if (button != dragButton) return false;
        dragButton = -1;
        rawRotation = rotation();
        snapped = Double.NaN;
        return true;
    }

    private boolean canDrag() {
        return !isClosing() && gesturesEnabled() && imageReady();
    }

    //#if MC>=260300
    //$$ private static boolean controlPressed() {
    //$$     return com.mojang.blaze3d.platform.InputConstants.isKeyDown(com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL)
    //$$         || com.mojang.blaze3d.platform.InputConstants.isKeyDown(com.mojang.blaze3d.platform.InputConstants.KEY_RCONTROL);
    //$$ }
    //#elseif MC>=260100
    //$$ private static boolean controlPressed() {
    //$$     final var window = net.minecraft.client.Minecraft.getInstance().getWindow();
    //$$     return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL)
    //$$         || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, com.mojang.blaze3d.platform.InputConstants.KEY_RCONTROL);
    //$$ }
    //#elseif MC>=12109
    //$$ private static boolean controlPressed() {
    //$$     final var window = net.minecraft.client.MinecraftClient.getInstance().getWindow();
    //$$     return net.minecraft.client.util.InputUtil.isKeyPressed(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL)
    //$$         || net.minecraft.client.util.InputUtil.isKeyPressed(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL);
    //$$ }
    //#else
    private static boolean controlPressed() {
        final long window = net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle();
        return net.minecraft.client.util.InputUtil.isKeyPressed(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL)
            || net.minecraft.client.util.InputUtil.isKeyPressed(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL);
    }
    //#endif

    private void rotateDrag(double x, double y, double dx, double dy) {
        double previousX = x - dx, previousY = y - dy;
        if (Math.hypot(x, y) < PIVOT_DEAD_ZONE || Math.hypot(previousX, previousY) < PIVOT_DEAD_ZONE) return;
        double cross = previousX * y - previousY * x;
        double dot = previousX * x + previousY * y;
        rawRotation += Math.toDegrees(Math.atan2(cross, dot));
        if (!Double.isNaN(snapped) && Math.abs(rawRotation - snapped) <= SNAP_EXIT) return;
        double nearest = Math.rint(rawRotation / 90) * 90;
        snapped = Math.abs(rawRotation - nearest) <= SNAP_ENTER ? nearest : Double.NaN;
    }
}
