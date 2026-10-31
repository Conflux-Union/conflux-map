package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.mc.render.WorldMatrices;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
//#if MC>=260200
//$$ import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector;
//#endif
//#if MC>=260100 && MC<260200
//$$ import com.mojang.blaze3d.buffers.GpuBufferSlice;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//#endif
//#if MC>=260100
//$$ import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//#elseif MC>=12111
//$$ import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
//#elseif MC>=12109
//$$ import net.minecraft.client.render.command.OrderedRenderCommandQueue;
//$$ // 1.21.9's Fabric API has no world render events; WorldRendererMixin supplies them instead.
//#else
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//#endif
import net.minecraft.client.MinecraftClient;
// Camera exists on every line (relocated on 26.x); VertexConsumerProvider is pre-26.2 only.
import net.minecraft.client.render.Camera;
//#if MC<260200
import net.minecraft.client.render.VertexConsumerProvider;
//#endif
import net.minecraft.client.util.math.MatrixStack;

/**
 * The single version-facing seam for the in-world waypoint overlay: registers the render
 * hook for the running Minecraft line and adapts its native context into an {@link
 * OverlayFrame} every pass, so {@link WaypointWorldRenderer} stays a version-free
 * orchestrator and the passes never see a native context.
 *
 * <p>Hook phases by line. 1.21.11 registers {@code WorldRenderEvents.BEFORE_TRANSLUCENT}
 * for the beam and {@code END_MAIN} for the labels. 26.1 records the world view at
 * {@code LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN} for the beam and keeps the labels on
 * {@code END_MAIN}: vanilla consumes no submit nodes after the terrain events on that line
 * (custom geometry renders before translucent terrain and the submit storage is cleared right
 * after the hook), so {@code WorldRendererMixin} draws the beam at {@code renderLevel}'s
 * RETURN instead - after a deferred pack's finalize, the same slot the pre-1.21.9 END passes
 * use. 26.2 submits the beam through the level's submit collector at
 * {@code AFTER_TRANSLUCENT_TERRAIN} and moves the labels to {@code COLLECT_SUBMITS}; 26.3
 * registers both passes on
 * {@code COLLECT_SUBMITS}, because it renders classic-translucent terrain inside one render
 * pass that stays open for the whole translucent hook, so the beam can no longer issue its
 * own passes there - submitting the beam geometry instead rides executeTranslucentAfterTerrain,
 * which is still after translucent terrain - the same slot the event hook used to provide.
 * The beam pipeline writes depth, so on 26.1+ it must run after translucent terrain; drawing
 * it first makes water fail its depth test and disappear where the beam crosses it. On
 * 1.21.9-1.21.10 Fabric API ships no world render events at all (the world renderer was
 * rewritten in 1.21.9 and the events only returned, under {@code rendering.v1.world}, in
 * 1.21.11); {@code WorldRendererMixin} drives {@link #onBeforeTranslucent}/{@link
 * #onEndMain} instead, so only the plumbing differs from every other version.
 *
 * <p>Before 1.21.9 both passes run at {@code WorldRenderEvents.END}, after the whole world
 * pass. A deferred shader pack (Photon and family) finalizes from {@code
 * LevelRenderer.render}'s RETURN and rebuilds the color attachment from its gbuffers. That
 * erases the draws that bypassed render pipelines: the pack captures pipeline-drawn geometry
 * into its gbuffers, where it is shaded and survives into the final image, while raw
 * Tessellator draws write into whatever framebuffer happens to be bound mid-pass and are
 * overwritten by the rebuild. Both of this mod's passes are raw draws, so drawing at END -
 * which fires at that same RETURN, after the pack's finalize - puts the waypoints on the
 * finished frame. The beam still participates in world occlusion by depth-testing against
 * the completed scene depth buffer, while the labels draw afterwards with depth testing
 * disabled so world content cannot hide player-facing navigation information. From 1.21.9
 * on, released Iris builds exist for every line (1.21.9/1.21.10 ship 1.9.x, 1.21.11 ships
 * 1.10.x, 26.1-26.3 ship 1.11.x), and all of their hook phases fire inside the pack's
 * world-pass capture window - no Fabric world event fires after the pack's finalize on those
 * lines - so the beam still rides a terrain-adjacent phase there, drawn through the submit
 * pipeline that the capture rule keeps alive.
 *
 * <p>The LAST listener (before 1.21.9 only) exists because END fires after vanilla popped
 * the world view matrix off the global ModelView: the listener captures it read-only for the
 * END passes to reinstall, and it draws nothing, so the deferred erase cannot affect it.
 */
public final class WorldOverlayEvents {
    private WorldOverlayEvents() {
    }

    //#if MC>=12109 && MC<12111 || MC>=260100 && MC<260200
    //$$ private static WaypointWorldRenderer active;
    //#endif

    /**
     * The version-specific builders below are preprocessor-comment lines whose identifiers
     * cannot rely on remapping; they reach the client through this plain helper.
     */
    private static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }

    /**
     * Registers the render hooks for the running version line. Listener order matters: the
     * beam pass must draw before the label pass wherever both share one phase.
     */
    public static void register(final WaypointWorldRenderer renderer) {
        //#if MC>=260300
        //$$ LevelRenderEvents.COLLECT_SUBMITS.register(context ->
        //$$     captureWorldView());
        //$$ LevelRenderEvents.COLLECT_SUBMITS.register(context ->
        //$$     renderer.renderBeams(beamFrame(context)));
        //$$ LevelRenderEvents.COLLECT_SUBMITS.register(context ->
        //$$     renderer.renderHud(labelFrame(context)));
        //#elseif MC>=260200
        //$$ LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context ->
        //$$     captureWorldView());
        //$$ LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context ->
        //$$     renderer.renderBeams(beamFrame(context)));
        //$$ LevelRenderEvents.COLLECT_SUBMITS.register(context ->
        //$$     renderer.renderHud(labelFrame(context)));
        //#elseif MC>=260100
        //$$ // 26.1 consumes no submit nodes after the terrain events (custom geometry renders
        //$$ // before translucent terrain and the storage is cleared right after this hook), so
        //$$ // the 26.2 submit path cannot render here. This listener only records the view and
        //$$ // fog; WorldRendererMixin draws the beams at renderLevel's RETURN, after a pack's
        //$$ // finalize.
        //$$ active = renderer;
        //$$ LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> captureWorldView());
        //$$ LevelRenderEvents.END_MAIN.register(context ->
        //$$     renderer.renderHud(labelFrame(context)));
        //#elseif MC>=12111
        //$$ WorldRenderEvents.BEFORE_TRANSLUCENT.register(context -> {
        //$$     captureWorldView();
        //$$     renderer.renderBeams(frame(context));
        //$$ });
        //$$ WorldRenderEvents.END_MAIN.register(context ->
        //$$     renderer.renderHud(frame(context)));
        //#elseif MC>=12109
        //$$ active = renderer;
        //#else
        // Deferred shading packs (Photon and family) run their finalize passes from
        // LevelRenderer.render's RETURN and rebuild the whole color attachment from the
        // gbuffer, erasing this mod's raw (pipeline-bypassing) draws made inside the world
        // pass (AFTER_TRANSLUCENT/LAST). WorldRenderEvents.END fires at that same RETURN,
        // after the pack's finalize, so both passes draw on the finished frame instead: the
        // beam depth-tests against the completed scene depth buffer, and the labels draw
        // after it with depth testing disabled.
        WorldRenderEvents.LAST.register(context -> {
            WorldMatrices.captureHudProjection(context.projectionMatrix());
            captureWorldView();
        });
        WorldRenderEvents.END.register(context -> {
            final OverlayFrame beamFrame = frame(context);
            renderState("end-beam", beamFrame, null);
            renderer.renderBeams(beamFrame);
        });
        WorldRenderEvents.END.register(context -> renderer.renderHud(frame(context)));
        //#endif
    }

    /**
     * World-pass capture listener: pairs the live camera view with this frame's already
     * staged projection into the HUD item-label snapshot (see WorldMatrices), and on the
     * pre-1.21.9 lines additionally records the view matrix for the post-pass END draws
     * (see {@link WorldMatrices#snapshotWorldViewModelView}).
     */
    private static void captureWorldView() {
        WorldMatrices.captureHudView();
        //#if MC<12109 || MC>=260100 && MC<260200
        WorldMatrices.snapshotWorldViewModelView();
        //#if MC>=260100 && MC<260200
        //$$ // The RETURN draw replays the fog the in-pass beam draw saw; by then later frame
        //$$ // graph passes (weather, clouds) may have installed their own slice.
        //$$ levelReturnFog = RenderSystem.getShaderFog();
        //#else
        renderState("last-capture", null, null);
        //#endif
        //#endif
    }

    //#if MC>=260100 && MC<260200
    //$$ private static GpuBufferSlice levelReturnFog;
    //$$
    //$$ /** Entry point {@code WorldRendererMixin} drives at {@code renderLevel}'s RETURN. */
    //$$ public static void onLevelReturn() {
    //$$     if (active == null) {
    //$$         return;
    //$$     }
    //$$     final GpuBufferSlice previousFog = RenderSystem.getShaderFog();
    //$$     if (levelReturnFog != null) {
    //$$         RenderSystem.setShaderFog(levelReturnFog);
    //$$     }
    //$$     try {
    //$$         active.renderBeams(levelReturnFrame());
    //$$     } finally {
    //$$         RenderSystem.setShaderFog(previousFog);
    //$$     }
    //$$ }
    //$$
    //$$ private static OverlayFrame levelReturnFrame() {
    //$$     final Camera camera = client().gameRenderer.getMainCamera();
    //$$     return new OverlayFrame(
    //$$         new PoseStack(),
    //$$         camera.position(),
    //$$         immediate(),
    //$$         camera,
    //$$         null,
    //$$         camera.yRot(),
    //$$         camera.xRot(),
    //$$         tickDelta()
    //$$     );
    //$$ }
    //#endif

    //#if MC>=12109 && MC<12111
    //$$ /**
    //$$  * Entry point {@code WorldRendererMixin} drives at the BEFORE_TRANSLUCENT-equivalent
    //$$  * section-layer call on the 1.21.9 line.
    //$$  */
    //$$ public static void onBeforeTranslucent(final MatrixStack matrices) {
    //$$     captureWorldView();
    //$$     if (active != null) {
    //$$         active.renderBeams(frame(matrices, null));
    //$$     }
    //$$ }
    //$$
    //$$ /** Entry point {@code WorldRendererMixin} drives at the end of the main pass. */
    //$$ public static void onEndMain(final MatrixStack matrices, final OrderedRenderCommandQueue commandQueue) {
    //$$     captureWorldView();
    //$$     if (active != null) {
    //$$         active.renderHud(frame(matrices, commandQueue));
    //$$     }
    //$$ }
    //$$
    //$$ private static OverlayFrame frame(
    //$$     final MatrixStack matrices,
    //$$     final OrderedRenderCommandQueue commandQueue
    //$$ ) {
    //$$     final Camera camera = client().gameRenderer.getCamera();
    //$$     return new OverlayFrame(
    //$$         matrices,
    //$$         camera.getCameraPos(),
    //$$         immediate(),
    //$$         camera,
    //$$         commandQueue,
    //$$         camera.getYaw(),
    //$$         camera.getPitch(),
    //$$         tickDelta()
    //$$     );
    //$$ }
    //#endif

    //#if MC<12109
    private static OverlayFrame frame(final WorldRenderContext context) {
        final Camera camera = context.camera();
        //#if MC<12001
        // 1.17.1/1.18.2 only: the END context stack still carries the camera
        // rotation while the END pass also reinstalls the LAST-captured view,
        // so baking through it rotates every vertex twice. Do NOT widen onto
        // 1.20.1: its snapshot is ~identity and the context stack is its only
        // rotation source.
        final MatrixStack matrices = new MatrixStack();
        //#else
        //$$ final MatrixStack matrices = context.matrixStack();
        //#endif
        return new OverlayFrame(
            matrices,
            camera.getPos(),
            immediate(),
            camera,
            context.projectionMatrix(),
            camera.getYaw(),
            camera.getPitch(),
            tickDelta(context)
        );
    }
    //#endif

    //#if MC>=12111 && MC<260100
    //$$ /**
    //$$  * The 1.21.11 world context is a different type from the classic one the pre-1.21.9
    //$$  * builder takes; its camera lives on the game renderer, not the context.
    //$$  */
    //$$ private static OverlayFrame frame(
    //$$     final net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext context
    //$$ ) {
    //$$     final Camera camera = client().gameRenderer.getCamera();
    //$$     return new OverlayFrame(
    //$$         context.matrices(),
    //$$         camera.getCameraPos(),
    //$$         immediate(),
    //$$         camera,
    //$$         null,
    //$$         camera.getYaw(),
    //$$         camera.getPitch(),
    //$$         tickDelta()
    //$$     );
    //$$ }
    //#endif

    //#if MC>=260100
    //$$ // 26.x is unobfuscated and this region is uncommented verbatim: identifiers here
    //$$ // are official names - do not write Yarn names.
    //$$ private static OverlayFrame beamFrame(final LevelRenderContext context) {
    //#if MC<260200
    //$$     final Camera camera = client().gameRenderer.getMainCamera();
    //#else
    //$$     final Camera camera = client().gameRenderer.mainCamera();
    //#endif
    //$$     return new OverlayFrame(
    //$$         context.poseStack(),
    //$$         camera.position(),
    //#if MC<260200
    //$$         immediate(),
    //$$         camera,
    //#endif
    //$$         null,
    //#if MC>=260200
    //$$         null,
    //#endif
    //#if MC>=260300
    //$$         (FabricOrderedSubmitNodeCollector) context.submitNodeCollector(),
    //#elseif MC>=260200
    //$$         // The 26.2 order view carries the fabric submitCustom methods the way the label
    //$$         // submits already ride them; the storage-level submit delegates to order(0) too.
    //$$         (FabricOrderedSubmitNodeCollector) context.submitNodeCollector().order(0),
    //#endif
    //$$         camera.yRot(),
    //$$         camera.xRot(),
    //$$         tickDelta()
    //$$     );
    //$$ }
    //$$
    //$$ private static OverlayFrame labelFrame(final LevelRenderContext context) {
    //#if MC>=260200
    //$$     final var cameraState = context.levelState().cameraRenderState;
    //$$     return new OverlayFrame(
    //$$         context.poseStack(),
    //$$         cameraState.pos,
    //$$         context.submitNodeCollector(),
    //$$         cameraState.orientation,
    //#if MC>=260300
    //$$         (FabricOrderedSubmitNodeCollector) context.submitNodeCollector(),
    //#elseif MC>=260200
    //$$         null,
    //#endif
    //$$         cameraState.yRot,
    //$$         cameraState.xRot,
    //$$         tickDelta()
    //$$     );
    //#else
    //$$     final Camera camera = client().gameRenderer.getMainCamera();
    //$$     return new OverlayFrame(
    //$$         context.poseStack(),
    //$$         camera.position(),
    //$$         immediate(),
    //$$         camera,
    //$$         context.submitNodeCollector(),
    //$$         camera.yRot(),
    //$$         camera.xRot(),
    //$$         tickDelta()
    //$$     );
    //#endif
    //$$ }
    //#endif

    //#if MC>=12109
    //$$ private static float tickDelta() {
    //$$     return client().getRenderTickCounter().getTickProgress(false);
    //$$ }
    //#endif

    //#if MC<260200
    /** The shared entity vertex consumers, the same buffer source vanilla nametags use. */
    private static VertexConsumerProvider.Immediate immediate() {
        return MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
    }
    //#endif

    //#if MC<12109
    private static float tickDelta(final WorldRenderContext context) {
        //#if MC>=12100
        //$$ return context.tickCounter().getTickDelta(false);
        //#else
        return context.tickDelta();
        //#endif
    }
    //#endif

    /**
     * One rate-limited diagnostics line per waypoint render pass: camera state, the view
     * matrix in force for this mod's draws, and where the given camera-relative anchor
     * projects to under the pass's matrices. Compare lines across passes (or against where
     * the waypoint actually shows on screen) to place render bugs without screenshot loops.
     * {@code anchorRel} is camera-relative; {@code null} skips the anchor projection.
     *
     * <p>Only driven on the pre-1.21.9 lines: the LAST-capture/END-reinstall ladder this
     * logs does not exist once the passes run inside the world pass.
     */
    static void renderState(final String phase, final OverlayFrame frame, final double[] anchorRel) {
        //#if MC<12109
        if (!debugRenderEnabled()) {
            return;
        }
        final long now = System.nanoTime();
        final Long lastLog = lastDebugLogNanos.get(phase);
        if (lastLog != null && now - lastLog < 2_000_000_000L) {
            return;
        }
        lastDebugLogNanos.put(phase, now);
        final StringBuilder line = new StringBuilder(384).append("[wp-render] ").append(phase);
        if (frame != null) {
            line.append(String.format(Locale.ROOT,
                " cam=(%.2f, %.2f, %.2f) yaw=%.1f pitch=%.1f",
                frame.cameraPos.x, frame.cameraPos.y, frame.cameraPos.z,
                frame.cameraYaw, frame.cameraPitch));
        }
        //#if MC>=11904 && MC<12109
        //$$ final org.joml.Matrix4f view = WorldMatrices.capturedWorldViewMatrix();
        //$$ if (view != null) {
        //$$     line.append(" view=").append(WorldMatrices.debugMatrix(view));
        //$$ }
        //$$ if (frame != null && anchorRel != null && view != null) {
        //$$     line.append(WorldMatrices.debugProjectAnchor(
        //$$         (org.joml.Matrix4f) frame.projectionMatrix, view,
        //$$         anchorRel[0], anchorRel[1], anchorRel[2], MinecraftClient.getInstance()));
        //$$ }
        //#endif
        ConfluxMapMod.LOGGER.info("{}", line);
        //#endif
    }

    /**
     * Waypoint world-render diagnostics switch: {@code -Dconfluxmap.debugWaypointRender=true}
     * or {@code CONFLUXMAP_DEBUG_RENDER=1} (Gradle launches inherit env vars, not -D flags).
     */
    static boolean debugRenderEnabled() {
        return Boolean.getBoolean("confluxmap.debugWaypointRender")
            || "1".equals(System.getenv("CONFLUXMAP_DEBUG_RENDER"));
    }

    /** Per-phase rate limit: a shared timestamp would let the first phase of a frame starve the rest. */
    private static final Map<String, Long> lastDebugLogNanos = new HashMap<>();
}
