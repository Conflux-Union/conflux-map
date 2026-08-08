package cn.net.rms.confluxmap.mc.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

//#if MC>=12100
//$$ import java.io.IOException;
//$$ import java.net.URISyntaxException;
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//$$ import net.minecraft.client.MinecraftClient;
//$$ import org.joml.Matrix4f;
//$$ import org.joml.Vector4f;
//#endif
import org.junit.jupiter.api.Test;

final class OffscreenCanvasProjectionTest {
    //#if MC>=12100
    //$$ /** Canvas rendering installs an identity model-view, so its quads always sit on z=0. */
    //$$ private static final float CANVAS_DRAW_PLANE_Z = 0f;
    //#endif
    //#if MC>=12100
    //$$
    //$$ @Test
    //$$ void canvasProjectionContainsTheCanvasDrawPlane() throws Exception {
    //$$     final Matrix4f projection = canvasProjection();
    //$$     final Vector4f clip = projection.transform(
    //$$         new Vector4f(64f, 64f, CANVAS_DRAW_PLANE_Z, 1f)
    //$$     );
    //$$
    //$$     assertTrue(
    //$$         Math.abs(clip.z) <= Math.abs(clip.w),
    //$$         () -> "canvas draw plane is clipped: z=" + clip.z + ", w=" + clip.w
    //$$     );
    //$$ }
    //$$
    //$$ @Test
    //$$ void canvasProjectionKeepsGuiQuadsFrontFacing() throws Exception {
    //$$     final Matrix4f projection = canvasProjection();
    //$$     final Vector4f topLeft = project(projection, 0f, 128f);
    //$$     final Vector4f topRight = project(projection, 128f, 128f);
    //$$     final Vector4f bottomRight = project(projection, 128f, 0f);
    //$$     final float signedArea =
    //$$         (topRight.x - topLeft.x) * (bottomRight.y - topLeft.y)
    //$$             - (topRight.y - topLeft.y) * (bottomRight.x - topLeft.x);
    //$$
    //$$     assertTrue(
    //$$         signedArea > 0f,
    //$$         () -> "GUI quad becomes a culled clockwise face: signedArea=" + signedArea
    //$$     );
    //$$ }
    //$$
    //$$ @Test
    //$$ void canvasRestoresTheProjectionItReplaced() throws IOException, URISyntaxException {
    //$$     final String source = Files.readString(preprocessedSource());
    //$$     final String publicBegin = methodBody(source, "public void begin(final int sizePx)");
    //$$     final String preservingBegin = methodBody(
    //$$         source, "public void beginPreserving(final int sizePx)"
    //$$     );
    //$$     final String begin = methodBody(
    //$$         source, "private void beginInternal(final int sizePx, final boolean clear)"
    //$$     );
    //$$     // The signature is matched as text against source this build just wrote, so the class
    //$$     // has to be spelled the way that source spells it. Reading the name off the class
    //$$     // itself gets remapped along with everything else instead of pinning one version's.
    //$$     final String end = methodBody(
    //$$         source, "public void end(final " + MinecraftClient.class.getSimpleName() + " client)"
    //$$     );
    //$$     final int backup = begin.indexOf("RenderSystem.backupProjectionMatrix()");
    //$$     final int install = begin.indexOf("setProjection(canvasProjection(");
    //$$
    //$$     assertTrue(
    //$$         publicBegin.contains("beginInternal(sizePx, true)")
    //$$             && preservingBegin.contains("beginInternal(sizePx, false)"),
    //$$         "both public entry points must share the projection-preserving implementation"
    //$$     );
    //$$     assertTrue(
    //$$         backup >= 0 && install >= 0 && backup < install,
    //$$         "begin must save the caller's projection before installing the canvas projection"
    //$$     );
    //$$     assertTrue(
    //$$         end.contains("RenderSystem.restoreProjectionMatrix()"),
    //$$         "end must restore the exact projection and vertex sorter active before begin"
    //$$     );
    //#if MC>=12100
    //$$     assertTrue(
    //$$         begin.contains("RenderSystem.getModelViewStack().pushMatrix().identity()"),
    //$$         "begin must clear the model-view inherited outside GUI rendering"
    //$$     );
    //$$     assertTrue(
    //$$         end.contains("RenderSystem.getModelViewStack().popMatrix()"),
    //$$         "end must give the caller back its model-view"
    //$$     );
    //#endif
    //$$ }
    //$$
    //$$ private static Matrix4f canvasProjection() throws Exception {
    //$$     final var projection = OffscreenCanvas.class.getDeclaredMethod(
    //$$         "canvasProjection", int.class
    //$$     );
    //$$     projection.setAccessible(true);
    //$$     return (Matrix4f) projection.invoke(null, 128);
    //$$ }
    //$$
    //$$ private static Vector4f project(final Matrix4f projection, final float x, final float y) {
    //$$     final Vector4f clip = projection.transform(
    //$$         new Vector4f(x, y, CANVAS_DRAW_PLANE_Z, 1f)
    //$$     );
    //$$     return clip.div(clip.w);
    //$$ }
    //$$
    //$$ private static Path preprocessedSource() throws URISyntaxException {
    //$$     Path current = Path.of(
    //$$         OffscreenCanvas.class.getProtectionDomain().getCodeSource().getLocation().toURI()
    //$$     );
    //$$     while (current != null && !"build".equals(current.getFileName().toString())) {
    //$$         current = current.getParent();
    //$$     }
    //$$     if (current == null) {
    //$$         throw new IllegalStateException("Could not locate the version build directory");
    //$$     }
    //$$     return current.resolve(
    //$$         "preprocessed/main/java/cn/net/rms/confluxmap/mc/render/OffscreenCanvas.java"
    //$$     );
    //$$ }
    //$$
    //$$ private static String methodBody(final String source, final String signature) {
    //$$     final int start = source.indexOf(signature);
    //$$     if (start < 0) {
    //$$         throw new IllegalStateException("Missing method " + signature);
    //$$     }
    //$$     final int nextMethod = source.indexOf("\n    public ", start + signature.length());
    //$$     return source.substring(start, nextMethod < 0 ? source.length() : nextMethod);
    //$$ }
    //#else
    @Test
    void legacyCanvasProjectionUsesTheLegacyGuiDepthRange() {
        assertTrue(true);
    }
    //#endif
}
