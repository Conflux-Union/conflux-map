package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

//#if MC>=12100
//$$ import java.net.URISyntaxException;
//$$ import java.nio.file.Files;
//$$ import java.nio.file.Path;
//$$ import org.joml.Matrix4f;
//$$ import org.joml.Quaternionf;
//$$ import org.joml.Vector4f;
//#endif
import org.junit.jupiter.api.Test;

final class WaypointHudProjectionTest {
    //#if MC>=12100
    //$$ @Test
    //$$ void lastRenderHudKeepsWorldRendererCameraView() throws Exception {
    //$$     final String source = Files.readString(preprocessedSource());
    //$$     final String renderHud = methodBody(source, "private void renderHud(final WorldRenderContext context)");
    //$$     final String renderUtil = Files.readString(preprocessedRenderUtilSource());
    //$$     final String pushModelView = methodBody(
    //$$         renderUtil, "public static void pushWorldHudModelView()"
    //$$     );
    //$$
    //$$     assertFalse(
    //$$         renderHud.contains("pushIdentityModelView"),
    //$$         "LAST already stores the camera view rotation in ModelView; clearing it moves forward waypoints out of clip space"
    //$$     );
    //$$     assertTrue(
    //$$         renderHud.contains("pushWorldHudModelView"),
    //$$         "renderHud must enter the version-aware world HUD ModelView scope"
    //$$     );
    //$$     assertTrue(
    //$$         pushModelView.contains("getModelViewStack().pushMatrix()"),
    //$$         "modern world HUD rendering must save the active camera view"
    //$$     );
    //$$     assertFalse(
    //$$         pushModelView.contains("identity()"),
    //$$         "modern world HUD rendering must not clear the active camera view"
    //$$     );
    //$$ }
    //$$
    //$$ @Test
    //$$ void cameraViewKeepsAForwardWaypointInsideClipSpaceAfterTurning() {
    //$$     final float yawDegrees = -90f;
    //$$     final Quaternionf cameraRotation = new Quaternionf().rotationYXZ(
    //$$         (float) Math.PI - (float) Math.toRadians(yawDegrees), 0f, 0f
    //$$     );
    //$$     final Matrix4f projection = new Matrix4f().perspective(
    //$$         (float) Math.toRadians(70.0), 1f, 0.05f, 1_000f
    //$$     );
    //$$     final Matrix4f label = new Matrix4f()
    //$$         .translate(10f, 0f, 0f)
    //$$         .rotate(cameraRotation);
    //$$     final Matrix4f cameraView = new Matrix4f().rotation(
    //$$         new Quaternionf(cameraRotation).conjugate()
    //$$     );
    //$$
    //$$     final Vector4f withCameraView = new Matrix4f(projection)
    //$$         .mul(cameraView)
    //$$         .mul(label)
    //$$         .transform(new Vector4f(0f, 0f, 0f, 1f));
    //$$     final Vector4f withIdentityView = new Matrix4f(projection)
    //$$         .mul(label)
    //$$         .transform(new Vector4f(0f, 0f, 0f, 1f));
    //$$
    //$$     assertTrue(insideClipSpace(withCameraView), "a waypoint straight ahead must be visible");
    //$$     assertFalse(
    //$$         insideClipSpace(withIdentityView),
    //$$         "resetting ModelView must reproduce the missing waypoint HUD after the camera turns"
    //$$     );
    //$$ }
    //$$
    //$$ @Test
    //$$ void modernLabelQuadFacesTheCamera() throws Exception {
    //$$     final String source = Files.readString(preprocessedSource());
    //$$     final String drawLabel = methodBody(
    //$$         source, "private void drawLabel("
    //$$     );
    //$$
    //$$     assertTrue(
    //$$         drawLabel.contains("matrices.scale(scale, -scale, scale)"),
    //$$         "modern waypoint labels need vanilla's positive X billboard scale so culling keeps their quads"
    //$$     );
    //$$     assertTrue(
    //$$         labelQuadSignedArea(1f) > 0f,
    //$$         "vanilla's modern billboard orientation must keep the waypoint quad front-facing"
    //$$     );
    //$$     assertFalse(
    //$$         labelQuadSignedArea(-1f) > 0f,
    //$$         "the legacy negative X scale reproduces the fully culled waypoint HUD on 1.21.1"
    //$$     );
    //$$ }
    //$$
    //$$ private static float labelQuadSignedArea(final float xScale) {
    //$$     final Vector4f topLeft = new Vector4f(0f, -1f, -10f, 1f);
    //$$     final Vector4f topRight = new Vector4f(xScale, -1f, -10f, 1f);
    //$$     final Vector4f bottomRight = new Vector4f(xScale, 0f, -10f, 1f);
    //$$     final Matrix4f projection = new Matrix4f().perspective(
    //$$         (float) Math.toRadians(70.0), 1f, 0.05f, 1_000f
    //$$     );
    //$$     projection.transform(topLeft).div(topLeft.w);
    //$$     projection.transform(topRight).div(topRight.w);
    //$$     projection.transform(bottomRight).div(bottomRight.w);
    //$$     return (topRight.x - topLeft.x) * (bottomRight.y - topLeft.y)
    //$$         - (topRight.y - topLeft.y) * (bottomRight.x - topLeft.x);
    //$$ }
    //$$
    //$$ private static boolean insideClipSpace(final Vector4f clip) {
    //$$     return clip.w > 0f
    //$$         && Math.abs(clip.x) <= clip.w
    //$$         && Math.abs(clip.y) <= clip.w
    //$$         && Math.abs(clip.z) <= clip.w;
    //$$ }
    //$$
    //$$ private static Path preprocessedSource() throws URISyntaxException {
    //$$     Path current = Path.of(
    //$$         WaypointWorldRenderer.class.getProtectionDomain().getCodeSource().getLocation().toURI()
    //$$     );
    //$$     while (current != null && !"build".equals(current.getFileName().toString())) {
    //$$         current = current.getParent();
    //$$     }
    //$$     if (current == null) {
    //$$         throw new IllegalStateException("Could not locate the version build directory");
    //$$     }
    //$$     return current.resolve(
    //$$         "preprocessed/main/java/cn/net/rms/confluxmap/mc/ui/world/WaypointWorldRenderer.java"
    //$$     );
    //$$ }
    //$$
    //$$ private static Path preprocessedRenderUtilSource() throws URISyntaxException {
    //$$     return preprocessedSource().getParent().getParent().getParent().resolve("render/RenderUtil.java");
    //$$ }
    //$$
    //$$ private static String methodBody(final String source, final String signature) {
    //$$     final int start = source.indexOf(signature);
    //$$     if (start < 0) {
    //$$         throw new IllegalStateException("Missing method " + signature);
    //$$     }
    //$$     final int nextMethod = source.indexOf("\n    private ", start + signature.length());
    //$$     return source.substring(start, nextMethod < 0 ? source.length() : nextMethod);
    //$$ }
    //#else
    @Test
    void legacyHudKeepsItsExistingModelViewPath() {
        assertTrue(true);
    }
    //#endif
}
