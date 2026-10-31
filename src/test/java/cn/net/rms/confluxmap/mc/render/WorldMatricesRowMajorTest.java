package cn.net.rms.confluxmap.mc.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

//#if MC>=11904
//$$ import org.joml.Matrix4f;
//$$ import org.joml.Vector4f;
//#endif
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link WorldMatrices} row-major dump against JOML's own convention: JOML's
 * mXY() getters address column X, row Y, so reading them as row-then-column stores every
 * matrix transposed, and the HUD item-label projection then culls every in-front anchor.
 */
final class WorldMatricesRowMajorTest {
    //#if MC>=11904
    //$$ @Test
    //$$ void rowMajorDumpMatchesJomlTransformForProjectionAndView() {
    //$$     final Matrix4f projection = new Matrix4f().perspective(
    //$$         (float) Math.toRadians(70.0), 16f / 9f, 0.05f, 1_000f
    //$$     );
    //$$     final Matrix4f view = new Matrix4f().rotationYXZ(1.1f, -0.3f, 0f);
    //$$     final Vector4f anchor = new Vector4f(3f, -2f, -12f, 1f);
    //$$
    //$$     final double[] projectionDump = new double[16];
    //$$     WorldMatrices.writeRowMajor(projection, projectionDump);
    //$$     final double[] viewDump = new double[16];
    //$$     WorldMatrices.writeRowMajor(view, viewDump);
    //$$
    //$$     assertRowsMatchTransform(projectionDump, projection, anchor);
    //$$     assertRowsMatchTransform(viewDump, view, anchor);
    //$$ }
    //$$
    //$$ @Test
    //$$ void perspectiveDumpKeepsTheClipWRowInRowThree() {
    //$$     final Matrix4f projection = new Matrix4f().perspective(
    //$$         (float) Math.toRadians(70.0), 16f / 9f, 0.05f, 1_000f
    //$$     );
    //$$     final double[] dump = new double[16];
    //$$     WorldMatrices.writeRowMajor(projection, dump);
    //$$     // The clip-W row is row 3: (0, 0, -1, 0) puts the forward view distance in W,
    //$$     // which is what the HUD projection's in-front cull and focal math rely on.
    //$$     assertEquals(0.0, dump[12], 1.0e-9, "row 3 column 0");
    //$$     assertEquals(0.0, dump[13], 1.0e-9, "row 3 column 1");
    //$$     assertEquals(-1.0, dump[14], 1.0e-9, "row 3 column 2");
    //$$     assertEquals(0.0, dump[15], 1.0e-9, "row 3 column 3");
    //$$ }
    //$$
    //$$ private static void assertRowsMatchTransform(
    //$$     final double[] dump,
    //$$     final Matrix4f matrix,
    //$$     final Vector4f anchor
    //$$ ) {
    //$$     final Vector4f expected = matrix.transform(new Vector4f(anchor));
    //$$     for (int row = 0; row < 4; row++) {
    //$$         final double dot = dump[row * 4] * anchor.x
    //$$             + dump[row * 4 + 1] * anchor.y
    //$$             + dump[row * 4 + 2] * anchor.z
    //$$             + dump[row * 4 + 3] * anchor.w;
    //$$         assertEquals(expected.get(row), dot, 1.0e-6, "row " + row);
    //$$     }
    //$$ }
    //#endif
}
