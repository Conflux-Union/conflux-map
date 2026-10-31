package cn.net.rms.confluxmap.mc.ui.world;

//#if MC<260200
import net.minecraft.client.render.Camera;
//#endif
//#if MC<12109
//#if MC<12000
import net.minecraft.util.math.Matrix4f;
//#else
//$$ import org.joml.Matrix4f;
//#endif
//#endif
//#if MC>=12109 && MC<260100
//$$ import net.minecraft.client.render.command.OrderedRenderCommandQueue;
//#endif
//#if MC<260200
import net.minecraft.client.render.VertexConsumerProvider;
//#endif
//#if MC>=260100
//$$ import net.minecraft.client.renderer.SubmitNodeCollector;
//#endif
//#if MC>=260200
//$$ import org.joml.Quaternionf;
//#endif
//#if MC>=260200
//$$ import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

/**
 * One world-overlay render pass's state, adapted by {@link WorldOverlayEvents} from the
 * native render hook's context into version-free terms: camera placement, the matrix stack
 * to bake through, the tick delta, and the submit handles the passes draw with. Everything
 * {@link WaypointWorldRenderer}, {@link BeamPass}, and {@link LabelPass} touch per frame
 * arrives through this class, so no gate is needed outside the event seam.
 *
 * <p>The version-gated fields are filled for every pass on the versions where they exist;
 * a pass only reads the fields its own gates reference (the beam never reads the label
 * submit handle, and only the label pass reads the billboard rotation).
 */
final class OverlayFrame {
    /** Stack the pass bakes its world-anchored geometry through. */
    final MatrixStack matrices;
    final Vec3d cameraPos;
    //#if MC<260200
    /** Shared entity vertex consumers; the label pass flushes it once per pass. */
    final VertexConsumerProvider.Immediate immediate;
    final Camera camera;
    //#if MC<12109
    /** The world pass's projection, reinstalled at END after a deferred pack finalized. */
    //#if MC<12000
    final Matrix4f projectionMatrix;
    //#else
    //$$ final org.joml.Matrix4f projectionMatrix;
    //#endif
    //#endif
    //#endif
    //#if MC>=12109 && MC<260100
    //$$ final OrderedRenderCommandQueue labelQueue;
    //#endif
    //#if MC>=260100
    //$$ final SubmitNodeCollector labelSubmits;
    //#endif
    //#if MC>=260200
    //$$ final Quaternionf cameraRotation;
    //#endif
    //#if MC>=260200
    //$$ final FabricOrderedSubmitNodeCollector beamSubmits;
    //#endif
    final float cameraYaw;
    final float cameraPitch;
    final float tickDelta;

    OverlayFrame(
        final MatrixStack matrices,
        final Vec3d cameraPos,
        //#if MC<260200
        final VertexConsumerProvider.Immediate immediate,
        final Camera camera,
        //#if MC<12109
        //#if MC<12000
        final Matrix4f projectionMatrix,
        //#else
        //$$ final org.joml.Matrix4f projectionMatrix,
        //#endif
        //#endif
        //#endif
        //#if MC>=12109 && MC<260100
        //$$ final OrderedRenderCommandQueue labelQueue,
        //#endif
        //#if MC>=260100
        //$$ final SubmitNodeCollector labelSubmits,
        //#endif
        //#if MC>=260200
        //$$ final Quaternionf cameraRotation,
        //#endif
        //#if MC>=260200
        //$$ final FabricOrderedSubmitNodeCollector beamSubmits,
        //#endif
        final float cameraYaw,
        final float cameraPitch,
        final float tickDelta
    ) {
        this.matrices = matrices;
        this.cameraPos = cameraPos;
        //#if MC<260200
        this.immediate = immediate;
        this.camera = camera;
        //#if MC<12109
        this.projectionMatrix = projectionMatrix;
        //#endif
        //#endif
        //#if MC>=12109 && MC<260100
        //$$ this.labelQueue = labelQueue;
        //#endif
        //#if MC>=260100
        //$$ this.labelSubmits = labelSubmits;
        //#endif
        //#if MC>=260200
        //$$ this.cameraRotation = cameraRotation;
        //#endif
        //#if MC>=260200
        //$$ this.beamSubmits = beamSubmits;
        //#endif
        this.cameraYaw = cameraYaw;
        this.cameraPitch = cameraPitch;
        this.tickDelta = tickDelta;
    }
}
