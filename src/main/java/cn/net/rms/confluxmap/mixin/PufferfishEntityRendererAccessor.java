package cn.net.rms.confluxmap.mixin;

import net.minecraft.client.render.entity.PufferfishEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the three models vanilla switches by pufferfish inflation state. */
@Mixin(PufferfishEntityRenderer.class)
public interface PufferfishEntityRendererAccessor {
    //#if MC>=260100
    //$$ @Accessor("small")
    //#else
    @Accessor("smallModel")
    //#endif
    EntityModel<?> confluxmap$getSmallModel();

    //#if MC>=260100
    //$$ @Accessor("mid")
    //#else
    @Accessor("mediumModel")
    //#endif
    EntityModel<?> confluxmap$getMediumModel();

    //#if MC>=260100
    //$$ @Accessor("big")
    //#else
    @Accessor("largeModel")
    //#endif
    EntityModel<?> confluxmap$getLargeModel();
}
