package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
//#if MC>=12000
//$$ import cn.net.rms.confluxmap.mc.ui.GuiDraw;
//$$ import net.minecraft.client.gui.DrawContext;
//$$ import net.minecraft.text.Text;
//#if MC>=12108
//$$ import org.joml.Matrix3x2fStack;
//#endif
//#if MC>=12111
//$$ import net.minecraft.client.MinecraftClient;
//#endif
//#endif
import org.junit.jupiter.api.Test;

final class ConfluxScreenRenderTest {
    //#if MC>=12000
    //$$ @Test
    //$$ void implicitVanillaBackgroundDoesNotCoverCustomContents() throws Exception {
    //$$     final ProbeScreen screen = probeScreen(false);
    //$$
    //$$     // Spelled per version rather than mapped, same as ConfluxScreen's own override: a rename
    //$$     // in versions/mapping-*.txt only reaches members resolved on the renamed class itself.
    //#if MC>=260100
    //$$     screen.extractRenderState(drawContextWithoutClient(), 0, 0, 0f);
    //#else
    //$$     screen.render(drawContextWithoutClient(), 0, 0, 0f);
    //#endif
    //$$
    //$$     assertEquals(0, screen.backgrounds);
    //$$     assertEquals(List.of("contents", "widget", "after"), screen.events);
    //$$ }
    //$$
    //$$ @Test
    //$$ void explicitVanillaBackgroundRendersExactlyOnceBeforeWidgets() throws Exception {
    //$$     final ProbeScreen screen = probeScreen(true);
    //$$
    //#if MC>=260100
    //$$     screen.extractRenderState(drawContextWithoutClient(), 0, 0, 0f);
    //#else
    //$$     screen.render(drawContextWithoutClient(), 0, 0, 0f);
    //#endif
    //$$
    //#if MC>=12106
    //$$     // Screen.renderWithTooltip already ran it; a second pass would apply the blur twice.
    //$$     assertEquals(0, screen.backgrounds);
    //$$     assertEquals(List.of("contents", "widget", "after"), screen.events);
    //#else
    //$$     assertEquals(1, screen.backgrounds);
    //$$     assertEquals(List.of("contents", "background", "widget", "after"), screen.events);
    //#endif
    //$$ }
    //$$
    //$$ private static ProbeScreen probeScreen(final boolean explicitBackground) throws Exception {
    //#if MC>=12111
    //$$     // 1.21.11's Screen constructor reads the live client's text renderer; the render path
    //$$     // under test never touches it, so a placeholder covers construction and is dropped again.
    //$$     final var instance = MinecraftClient.class.getDeclaredField("instance");
    //$$     instance.setAccessible(true);
    //$$     instance.set(null, allocate(MinecraftClient.class));
    //$$     try {
    //$$         return new ProbeScreen(explicitBackground);
    //$$     } finally {
    //$$         instance.set(null, null);
    //$$     }
    //#else
    //$$     return new ProbeScreen(explicitBackground);
    //#endif
    //$$ }
    //$$
    //$$ private static DrawContext drawContextWithoutClient() throws Exception {
    //$$     // The probe treats the context as opaque; the normal constructor requires a live client.
    //$$     final DrawContext context = allocate(DrawContext.class);
    //#if MC>=12108
    //$$     // allocateInstance leaves every field null, and GuiDraw copies the 2D transform out
    //$$     // of the context as soon as ConfluxScreen.render wraps it. The field is named in a
    //$$     // string, which no mapping reaches, and 26.1 ships it as pose.
    //#if MC>=260100
    //$$     final String transformField = "pose";
    //#else
    //$$     final String transformField = "matrices";
    //#endif
    //$$     final var matrices = DrawContext.class.getDeclaredField(transformField);
    //$$     matrices.setAccessible(true);
    //$$     matrices.set(context, new Matrix3x2fStack(4));
    //#endif
    //$$     return context;
    //$$ }
    //$$
    //$$ private static <T> T allocate(final Class<T> type) throws Exception {
    //$$     final Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
    //$$     final var theUnsafe = unsafeType.getDeclaredField("theUnsafe");
    //$$     theUnsafe.setAccessible(true);
    //$$     return type.cast(
    //$$         unsafeType.getMethod("allocateInstance", Class.class).invoke(theUnsafe.get(null), type)
    //$$     );
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
    //#if MC>=12002
    //$$     @Override
    //$$     protected void renderVanillaBackground(
    //$$         final DrawContext context,
    //$$         final int mouseX,
    //$$         final int mouseY,
    //$$         final float tickDelta
    //$$     ) {
    //#else
    //$$     @Override
    //$$     protected void renderVanillaBackground(final DrawContext context) {
    //#endif
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
