package cn.net.rms.confluxmap.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
//#if MC<260100
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.gen.Invoker;
//#endif

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    //#if MC<260100
    //#if MC>=12103
    //$$ @Invoker("getFov")
    //$$ float confluxmap$getFov(Camera camera, float tickDelta, boolean changingFov);
    //#else
    @Invoker("getFov")
    double confluxmap$getFov(Camera camera, float tickDelta, boolean changingFov);
    //#endif
    //#endif
}
