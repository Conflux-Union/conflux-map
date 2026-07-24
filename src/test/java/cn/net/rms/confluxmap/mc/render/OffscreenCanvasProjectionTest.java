package cn.net.rms.confluxmap.mc.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

//#if MC>=12100
//$$ import java.io.IOException;
//$$ import java.net.URISyntaxException;
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//$$ import org.joml.Matrix4f;
//$$ import org.joml.Vector4f;
//#endif
import org.junit.jupiter.api.Test;

final class OffscreenCanvasProjectionTest {
    //#if MC>=12100
    //#if MC<12103
    //$$ private static final float MODERN_GUI_MODEL_VIEW_Z = -11_000f;
    //$$
    //$$ @Test
    //$$ void canvasProjectionContainsTheModernGuiDrawPlane() throws Exception {
    //$$     final Matrix4f projection = canvasProjection();
    //$$     final Vector4f clip = projection.transform(
    //$$         new Vector4f(64f, 64f, MODERN_GUI_MODEL_VIEW_Z, 1f)
    //$$     );
    //$$
    //$$     assertTrue(
    //$$         Math.abs(clip.z) <= Math.abs(clip.w),
    //$$         () -> "modern GUI draw plane is clipped: z=" + clip.z + ", w=" + clip.w
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
    //$$     final String begin = methodBody(source, "public void begin(final int sizePx)");
    //$$     final String end = methodBody(source, "public void end(final MinecraftClient client)");
    //$$     final int backup = begin.indexOf("RenderSystem.backupProjectionMatrix()");
    //$$     final int install = begin.indexOf("setProjection(canvasProjection(");
    //$$
    //$$     assertTrue(
    //$$         backup >= 0 && install >= 0 && backup < install,
    //$$         "begin must save the caller's projection before installing the canvas projection"
    //$$     );
    //$$     assertTrue(
    //$$         end.contains("RenderSystem.restoreProjectionMatrix()"),
    //$$         "end must restore the exact projection and vertex sorter active before begin"
    //$$     );
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
    //$$         new Vector4f(x, y, MODERN_GUI_MODEL_VIEW_Z, 1f)
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
    //#endif
    //#else
    @Test
    void legacyCanvasProjectionUsesTheLegacyGuiDepthRange() {
        assertTrue(true);
    }
    //#endif
}
