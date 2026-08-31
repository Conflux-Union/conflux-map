package cn.net.rms.confluxmap.mixin;

import net.minecraft.client.render.entity.TropicalFishEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
//#if MC<12103
import net.minecraft.client.render.entity.model.TintableCompositeModel;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the model pair vanilla switches by tropical-fish body shape. */
@Mixin(TropicalFishEntityRenderer.class)
public interface TropicalFishEntityRendererAccessor {
    @Accessor("smallModel")
    //#if MC>=12103
    //$$ EntityModel<?> confluxmap$getSmallModel();
    //#else
    TintableCompositeModel<?> confluxmap$getSmallModel();
    //#endif

    @Accessor("largeModel")
    //#if MC>=12103
    //$$ EntityModel<?> confluxmap$getLargeModel();
    //#else
    TintableCompositeModel<?> confluxmap$getLargeModel();
    //#endif
}
