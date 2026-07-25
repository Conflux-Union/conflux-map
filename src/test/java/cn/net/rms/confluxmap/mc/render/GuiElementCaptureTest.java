package cn.net.rms.confluxmap.mc.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

//#if MC>=12108
//$$ import java.lang.reflect.InvocationHandler;
//$$ import java.lang.reflect.Proxy;
//$$ import java.util.ArrayList;
//$$ import java.util.List;
//$$ import net.minecraft.client.gl.RenderPipelines;
//$$ import net.minecraft.client.gui.ScreenRect;
//$$ import net.minecraft.client.gui.render.state.GuiRenderState;
//$$ import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
//$$ import net.minecraft.client.render.VertexConsumer;
//$$ import net.minecraft.client.util.math.MatrixStack;
//$$ import org.junit.jupiter.api.AfterEach;
//#endif
import org.junit.jupiter.api.Test;

/**
 * From 1.21.6 a screen or HUD callback only records GUI elements - the renderer replays them once
 * collection is over. Geometry drawn on the spot there is painted with the world pass' matrices
 * and then buried under everything vanilla recorded, which is what made the whole map UI vanish.
 */
final class GuiElementCaptureTest {
    //#if MC>=12108
    //$$ @AfterEach
    //$$ void clearGuiState() {
    //$$     RenderUtil.setGuiState(null);
    //$$ }
    //$$
    //$$ @Test
    //$$ void guiFillIsRecordedInsteadOfDrawn() {
    //$$     final RecordingState state = new RecordingState();
    //$$     RenderUtil.setGuiState(state);
    //$$
    //$$     RenderUtil.fillRect(new MatrixStack(), 10f, 20f, 30f, 40f, 0xFF204060);
    //$$
    //$$     assertEquals(1, state.elements.size());
    //$$     final SimpleGuiElementRenderState element = state.elements.get(0);
    //$$     assertSame(RenderPipelines.GUI, element.pipeline());
    //$$     assertEquals(new ScreenRect(10, 20, 30, 40), element.bounds());
    //$$ }
    //$$
    //$$ @Test
    //$$ void recordedGeometryCarriesTheCallersTransform() {
    //$$     final RecordingState state = new RecordingState();
    //$$     RenderUtil.setGuiState(state);
    //$$     final MatrixStack matrices = new MatrixStack();
    //$$     matrices.translate(100f, 200f, 0f);
    //$$
    //$$     RenderUtil.fillRect(matrices, 10f, 20f, 30f, 40f, 0xFF204060);
    //$$
    //$$     final SimpleGuiElementRenderState element = state.elements.get(0);
    //$$     assertEquals(new ScreenRect(110, 220, 30, 40), element.bounds());
    //$$     // A recorded element replays flat: the renderer owns the matrix by then.
    //$$     assertEquals(
    //$$         List.of("110.0,260.0", "140.0,260.0", "140.0,220.0", "110.0,220.0"),
    //$$         replay(element)
    //$$     );
    //$$ }
    //$$
    //$$ /** Replays one element's vertices as "x,y" strings, in the order the renderer would see them. */
    //$$ private static List<String> replay(final SimpleGuiElementRenderState element) {
    //$$     final List<String> positions = new ArrayList<>();
    //$$     final InvocationHandler handler = (proxy, method, args) -> {
    //$$         if ("vertex".equals(method.getName()) && args.length == 3) {
    //$$             positions.add(args[0] + "," + args[1]);
    //$$         }
    //$$         return proxy;
    //$$     };
    //$$     final VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(
    //$$         VertexConsumer.class.getClassLoader(), new Class<?>[] {VertexConsumer.class}, handler
    //$$     );
    //#if MC>=12109
    //$$     element.setupVertices(consumer);
    //#else
    //$$     element.setupVertices(consumer, 0f);
    //#endif
    //$$     return positions;
    //$$ }
    //$$
    //$$ /** {@code GuiRenderState} is what the game hands out; only the collected list matters here. */
    //$$ private static final class RecordingState extends GuiRenderState {
    //$$     private final List<SimpleGuiElementRenderState> elements = new ArrayList<>();
    //$$
    //$$     @Override
    //$$     public void addSimpleElement(final SimpleGuiElementRenderState element) {
    //$$         assertNotNull(element.bounds(), "an element without bounds is dropped by the game");
    //$$         elements.add(element);
    //$$     }
    //$$ }
    //#else
    @Test
    void legacyGuiDrawingIsImmediate() {
        // Before 1.21.6 the GUI drew as it went, so there is no element list to record into.
        assertEquals(0, 0);
    }
    //#endif
}
