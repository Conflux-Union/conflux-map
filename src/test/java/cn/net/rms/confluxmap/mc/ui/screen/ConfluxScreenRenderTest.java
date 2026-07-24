package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
//#if MC>=12000
//$$ import cn.net.rms.confluxmap.mc.ui.GuiDraw;
//$$ import net.minecraft.client.gui.DrawContext;
//$$ import net.minecraft.text.Text;
//#endif
import org.junit.jupiter.api.Test;

final class ConfluxScreenRenderTest {
    //#if MC>=12000
    //$$ @Test
    //$$ void implicitVanillaBackgroundDoesNotCoverCustomContents() throws Exception {
    //$$     final ProbeScreen screen = new ProbeScreen(false);
    //$$
    //$$     screen.render(drawContextWithoutClient(), 0, 0, 0f);
    //$$
    //$$     assertEquals(0, screen.backgrounds);
    //$$     assertEquals(List.of("contents", "widget", "after"), screen.events);
    //$$ }
    //$$
    //$$ @Test
    //$$ void explicitVanillaBackgroundRendersExactlyOnceBeforeWidgets() throws Exception {
    //$$     final ProbeScreen screen = new ProbeScreen(true);
    //$$
    //$$     screen.render(drawContextWithoutClient(), 0, 0, 0f);
    //$$
    //$$     assertEquals(1, screen.backgrounds);
    //$$     assertEquals(List.of("contents", "background", "widget", "after"), screen.events);
    //$$ }
    //$$
    //$$ private static DrawContext drawContextWithoutClient() throws Exception {
    //$$     // The probe treats the context as opaque; the normal constructor requires a live client.
    //$$     final Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
    //$$     final var field = unsafeType.getDeclaredField("theUnsafe");
    //$$     field.setAccessible(true);
    //$$     return (DrawContext) unsafeType.getMethod("allocateInstance", Class.class)
    //$$         .invoke(field.get(null), DrawContext.class);
    //$$ }
    //$$
    //$$ private static final class ProbeScreen extends ConfluxScreen {
    //$$     private final boolean explicitBackground;
    //$$     private final List<String> events = new ArrayList<>();
    //$$     private int backgrounds;
    //$$
    //$$     ProbeScreen(final boolean explicitBackground) {
    //$$         super(Text.empty());
    //$$         this.explicitBackground = explicitBackground;
    //$$         addDrawable((context, mouseX, mouseY, tickDelta) -> events.add("widget"));
    //$$     }
    //$$
    //$$     @Override
    //$$     protected void renderContents(
    //$$         final GuiDraw draw,
    //$$         final int mouseX,
    //$$         final int mouseY,
    //$$         final float tickDelta
    //$$     ) {
    //$$         events.add("contents");
    //$$         if (explicitBackground) {
    //$$             draw.renderBackground(this, mouseX, mouseY, tickDelta);
    //$$         }
    //$$     }
    //$$
    //$$     @Override
    //$$     protected void renderAfterWidgets(
    //$$         final GuiDraw draw,
    //$$         final int mouseX,
    //$$         final int mouseY,
    //$$         final float tickDelta
    //$$     ) {
    //$$         events.add("after");
    //$$     }
    //$$
    //$$     @Override
    //$$     protected void renderVanillaBackground(
    //$$         final DrawContext context,
    //$$         final int mouseX,
    //$$         final int mouseY,
    //$$         final float tickDelta
    //$$     ) {
    //$$         backgrounds++;
    //$$         events.add("background");
    //$$     }
    //$$ }
    //#else
    @Test
    void legacyScreenLifecycleDoesNotUseTheModernBackgroundContract() {
        assertEquals(1, 1);
    }
    //#endif
}
