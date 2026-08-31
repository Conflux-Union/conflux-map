package cn.net.rms.confluxmap.mixin;

//#if MC>=12103
//$$ import net.minecraft.client.render.entity.AgeableMobEntityRenderer;
//$$ import net.minecraft.client.render.entity.model.EntityModel;
//$$ import org.spongepowered.asm.mixin.gen.Accessor;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Exposes the model pair that vanilla switches by {@code LivingEntityRenderState#baby}. */
//#if MC>=12103
//$$ @Mixin(AgeableMobEntityRenderer.class)
//#else
@Pseudo
@Mixin(targets = "net.minecraft.client.render.entity.AgeableMobEntityRenderer")
//#endif
public interface AgeableMobEntityRendererAccessor {
    //#if MC>=12103
//$$     @Accessor("adultModel")
//$$     EntityModel<?> confluxmap$getAdultModel();
//$$
//$$     @Accessor("babyModel")
//$$     EntityModel<?> confluxmap$getBabyModel();
    //#endif
}
