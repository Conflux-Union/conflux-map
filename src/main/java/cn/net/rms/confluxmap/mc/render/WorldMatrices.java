package cn.net.rms.confluxmap.mc.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.FloatBuffer;
//#if MC>=12108 && MC<12109
//$$ import net.minecraft.client.render.RawProjectionMatrix;
//#endif
//#if MC<12109
//#if MC>=12103
//$$ import com.mojang.blaze3d.systems.ProjectionType;
//#elseif MC>=12000
//$$ import com.mojang.blaze3d.systems.VertexSorter;
//#endif
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;

/**
 * World-pass matrix snapshots and reinstall brackets shared by the waypoint beam and label
 * passes: the view matrix captured inside the world pass and reinstalled at END (pre-1.21.9)
 * or at 26.1's renderLevel RETURN, the perspective projection swap that survives deferred
 * shader packs, and the HUD-scope ModelView bracket for the in-pass phases.
 */
public final class WorldMatrices {
    //#if MC>=12108 && MC<12109
    //$$ private static RawProjectionMatrix beamProjection;
    //#endif
    //#if MC<12109 || MC>=260100 && MC<260200
    // Private copy: the live matrix RenderSystem.getModelViewMatrix() returns is a stack
    // slot the next frame's world pass mutates in place, so the snapshot must not alias it.
    //#if MC>=11904
    //$$ private static final org.joml.Matrix4f worldViewMatrix = new org.joml.Matrix4f();
    //#else
    private static net.minecraft.util.math.Matrix4f worldViewMatrix;
    //#endif
    private static boolean worldViewCaptured;
    //#endif

    // HUD item-label frame staging: the projection arrives where vanilla installs it (the
    // LAST context before 1.21.9, the GameRenderer renderWorld/renderLevel capture on later
    // lines), the view arrives from the world-pass hooks; the view is captured last in every
    // frame, so it commits the pair into the HUD snapshot.
    private static final double[] hudProjectionFrame = new double[16];
    private static final double[] hudViewFrame = new double[16];
    //#if MC<11904
    private static final float[] hudMatrixScratch = new float[16];
    //#endif
    private static boolean hudProjectionCaptured;

    private WorldMatrices() {
    }

    /** Stages the world pass's projection for the HUD item-label snapshot (row-major doubles). */
    //#if MC<11904
    public static void captureHudProjection(final net.minecraft.util.math.Matrix4f projection) {
        final FloatBuffer floats = FloatBuffer.wrap(hudMatrixScratch);
        projection.writeRowMajor(floats);
        for (int i = 0; i < hudMatrixScratch.length; i++) {
            hudProjectionFrame[i] = hudMatrixScratch[i];
        }
        hudProjectionCaptured = true;
    }
    //#else
    //$$ public static void captureHudProjection(final org.joml.Matrix4f projection) {
    //$$     writeRowMajor(projection, hudProjectionFrame);
    //$$     hudProjectionCaptured = true;
    //$$ }
    //#endif

    /**
     * Captures the live world-pass camera view and, once this frame's projection has been
     * staged, commits both into the HUD item-label snapshot. Called from a world-pass hook
     * that runs after the projection is installed and before the HUD phase of the same frame.
     */
    public static void captureHudView() {
        //#if MC<11904
        final FloatBuffer floats = FloatBuffer.wrap(hudMatrixScratch);
        RenderSystem.getModelViewMatrix().writeRowMajor(floats);
        for (int i = 0; i < hudMatrixScratch.length; i++) {
            hudViewFrame[i] = hudMatrixScratch[i];
        }
        //#elseif MC>=260200
        //$$ writeRowMajor(RenderSystem.getModelViewMatrixCopy(), hudViewFrame);
        //#else
        //$$ writeRowMajor(RenderSystem.getModelViewMatrix(), hudViewFrame);
        //#endif
        if (hudProjectionCaptured) {
            WaypointHudFrameMatrices.update(hudProjectionFrame, hudViewFrame);
        }
    }

    /** Drops the HUD item-label snapshot and its staged projection (session/dimension change). */
    public static void clearHudFrame() {
        hudProjectionCaptured = false;
        WaypointHudFrameMatrices.clear();
    }

    //#if MC>=11904
    //$$ static void writeRowMajor(final org.joml.Matrix4f matrix, final double[] out) {
    //$$     // JOML's mXY() getters address column X, row Y, so the getters must be read
    //$$     // transposed here; reading m01() as "row 0, column 1" stores the whole matrix
    //$$     // transposed and turns the projection's clip-W row into its translation column.
    //$$     out[0] = matrix.m00();
    //$$     out[1] = matrix.m10();
    //$$     out[2] = matrix.m20();
    //$$     out[3] = matrix.m30();
    //$$     out[4] = matrix.m01();
    //$$     out[5] = matrix.m11();
    //$$     out[6] = matrix.m21();
    //$$     out[7] = matrix.m31();
    //$$     out[8] = matrix.m02();
    //$$     out[9] = matrix.m12();
    //$$     out[10] = matrix.m22();
    //$$     out[11] = matrix.m32();
    //$$     out[12] = matrix.m03();
    //$$     out[13] = matrix.m13();
    //$$     out[14] = matrix.m23();
    //$$     out[15] = matrix.m33();
    //$$ }
    //#endif


    //#if MC<12109 || MC>=260100 && MC<260200
    /**
     * Captures the world pass's global ModelView: vanilla installs the view matrix (camera
     * rotation, plus view bobbing/hurt tilt) on the global stack at the start of world
     * rendering and pops it again right before the render method returns - which is exactly
     * where {@code WorldRenderEvents.END} fires. A read-only listener (still inside the world
     * pass) calls this so the post-pass draws can reinstall the exact matrix.
     */
    public static void snapshotWorldViewModelView() {
        //#if MC>=11904
        //$$ worldViewMatrix.set(RenderSystem.getModelViewMatrix());
        //#else
        worldViewMatrix = RenderSystem.getModelViewMatrix().copy();
        //#endif
        worldViewCaptured = true;
    }

    /**
     * Installs the view matrix captured by {@link #snapshotWorldViewModelView()} as the global
     * ModelView. World-anchored waypoint geometry drawn at {@code WorldRenderEvents.END}
     * transforms through it, so without this the view rotation is missing and the geometry
     * lands in the wrong place on screen. Falls back to identity before the first capture.
     * Pairs with {@link #popModelView()}.
     */
    public static void pushWorldViewSnapshot() {
        //#if MC>=12005
        //$$ RenderSystem.getModelViewStack().pushMatrix().identity();
        //$$ if (worldViewCaptured) {
        //$$     RenderSystem.getModelViewStack().mul(worldViewMatrix);
        //$$ }
        //#else
        final MatrixStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.push();
        final var top = modelViewStack.peek().getModel();
        //#if MC>=11904
        //$$ // 1.19.4-1.20.4 keep the legacy MatrixStack but with JOML entries.
        //$$ top.identity().mul(worldViewMatrix);
        //#else
        top.loadIdentity();
        if (worldViewCaptured) {
            top.multiply(worldViewMatrix);
        }
        //#endif
        //#endif
        //#if MC<12103
        RenderSystem.applyModelViewMatrix();
        //#endif
    }
    //#endif

    //#if MC<12109
    /**
     * Saves the projection that was active on entry and installs the world pass's
     * perspective projection. Needed at {@code WorldRenderEvents.END}: deferred shader packs
     * finalize from the same method return and leave the global projection pointing at their
     * composite passes. {@link #restoreProjection()} puts the saved one back.
     *
     * <p>26.1 does not use this: its RETURN-time draw runs before anything swaps the world
     * projection away, so only the view snapshot above is reinstalled there.
     */
    public static void applyWorldProjection(
        //#if MC<12000
        final net.minecraft.util.math.Matrix4f projection
        //#else
        //$$ final org.joml.Matrix4f projection
        //#endif
    ) {
        // setProjectionMatrix overwrites without saving; restoreProjectionMatrix only puts
        // back what backupProjectionMatrix stored, so the save must be explicit here.
        RenderSystem.backupProjectionMatrix();
        //#if MC>=12108
        //$$ if (beamProjection == null) {
        //$$     beamProjection = new RawProjectionMatrix("Conflux Map waypoint projection");
        //$$ }
        //$$ RenderSystem.setProjectionMatrix(beamProjection.set(projection), ProjectionType.PERSPECTIVE);
        //#elseif MC>=12103
        //$$ RenderSystem.setProjectionMatrix(projection, ProjectionType.PERSPECTIVE);
        //#elseif MC>=12000
        //$$ RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_Z);
        //#else
        RenderSystem.setProjectionMatrix(projection);
        //#endif
    }

    /** Puts back the projection saved by {@link #applyWorldProjection}. */
    public static void restoreProjection() {
        RenderSystem.restoreProjectionMatrix();
    }
    //#endif

    //#if MC>=11904 && MC<12109
    //$$ /** Row-major dump of a matrix, for waypoint render diagnostics. */
    //$$ public static String debugMatrix(final org.joml.Matrix4f matrix) {
    //$$     return String.format(
    //$$         java.util.Locale.ROOT,
    //$$         "%.3f %.3f %.3f %.3f / %.3f %.3f %.3f %.3f / %.3f %.3f %.3f %.3f / %.3f %.3f %.3f %.3f",
    //$$         matrix.m00(), matrix.m01(), matrix.m02(), matrix.m03(),
    //$$         matrix.m10(), matrix.m11(), matrix.m12(), matrix.m13(),
    //$$         matrix.m20(), matrix.m21(), matrix.m22(), matrix.m23(),
    //$$         matrix.m30(), matrix.m31(), matrix.m32(), matrix.m33()
    //$$     );
    //$$ }
    //$$
    //$$ /** The world view matrix captured by {@link #snapshotWorldViewModelView()}, or null. */
    //$$ public static org.joml.Matrix4f capturedWorldViewMatrix() {
    //$$     return worldViewCaptured ? worldViewMatrix : null;
    //$$ }
    //$$
    //$$ /**
    //$$  * Where a camera-relative anchor lands on screen under the current waypoint pass's
    //$$  * projection and captured view. Exact on 1.20.5+, where the context matrix stack is
    //$$  * identity at END (the anchor's own translate is already folded into rel); on
    //$$  * 1.19.4-1.20.4 it ignores the context stack's rotation, so treat the screen
    //$$  * position there as approximate. Returns an empty string behind the camera.
    //$$  */
    //$$ public static String debugProjectAnchor(
    //$$     final org.joml.Matrix4f projection,
    //$$     final org.joml.Matrix4f view,
    //$$     final double relX,
    //$$     final double relY,
    //$$     final double relZ,
    //$$     final MinecraftClient client
    //$$ ) {
    //$$     final org.joml.Matrix4f mvp = new org.joml.Matrix4f(projection).mul(view);
    //$$     final org.joml.Vector4f clip = mvp.transform(new org.joml.Vector4f(
    //$$         (float) relX, (float) relY, (float) relZ, 1.0f));
    //$$     if (Math.abs(clip.w) <= 1.0e-6f) {
    //$$         return "";
    //$$     }
    //$$     final Window window = client.getWindow();
    //$$     return String.format(
    //$$         java.util.Locale.ROOT,
    //$$         " anchorRel=(%.2f, %.2f, %.2f) ndc=(%.3f, %.3f) screen=(%d, %d)",
    //$$         relX, relY, relZ, clip.x / clip.w, clip.y / clip.w,
    //$$         Math.round((clip.x / clip.w * 0.5f + 0.5f) * window.getFramebufferWidth()),
    //$$         Math.round((0.5f - clip.y / clip.w * 0.5f) * window.getFramebufferHeight())
    //$$     );
    //$$ }
    //#endif

    /** Saves the world ModelView and normalizes only the legacy LAST-event state. */
    public static void pushWorldHudModelView() {
        //#if MC>=12005
        //$$ RenderSystem.getModelViewStack().pushMatrix();
        //#else
        final MatrixStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.push();
        modelViewStack.loadIdentity();
        //#endif
        //#if MC<12103
        RenderSystem.applyModelViewMatrix();
        //#endif
    }

    /** Restores the global model-view saved by {@link #pushWorldHudModelView()}. */
    public static void popModelView() {
        //#if MC>=12005
        //$$ RenderSystem.getModelViewStack().popMatrix();
        //#else
        RenderSystem.getModelViewStack().pop();
        //#endif
        //#if MC<12103
        RenderSystem.applyModelViewMatrix();
        //#endif
    }
}
