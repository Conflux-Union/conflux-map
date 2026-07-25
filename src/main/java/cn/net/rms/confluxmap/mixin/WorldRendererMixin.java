package cn.net.rms.confluxmap.mixin;

//#if MC>=12109 && MC<12111
//$$ import cn.net.rms.confluxmap.mc.ui.world.WaypointWorldRenderer;
//$$ import net.minecraft.client.util.math.MatrixStack;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#endif
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Supplies the two world render callbacks the waypoint renderer needs on the 1.21.9 line, where
 * Fabric API ships no world render events at all: 1.21.9 rewrote world rendering and the events
 * only returned in 1.21.11. Every other version registers with Fabric and this mixin is empty.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    //#if MC>=12109 && MC<12111
    //$$ /**
    //$$  * The section render state draws one layer group per call, in OPAQUE, TRANSLUCENT, TRIPWIRE
    //$$  * order, so ordinal 1 is the point matching Fabric's BEFORE_TRANSLUCENT on other versions.
    //$$  */
    //$$ @Inject(
    //$$     method = "method_62214",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/render/SectionRenderState;renderSection("
    //$$             + "Lnet/minecraft/client/render/BlockRenderLayerGroup;)V",
    //$$         ordinal = 1
    //$$     )
    //$$ )
    //$$ private void confluxmap$beforeTranslucent(final CallbackInfo ci) {
    //$$     WaypointWorldRenderer.onBeforeTranslucent(new MatrixStack());
    //$$ }
    //$$
    //$$ @Inject(method = "method_62214", at = @At("TAIL"))
    //$$ private void confluxmap$endMain(final CallbackInfo ci) {
    //$$     WaypointWorldRenderer.onEndMain(new MatrixStack());
    //$$ }
    //#endif
}
