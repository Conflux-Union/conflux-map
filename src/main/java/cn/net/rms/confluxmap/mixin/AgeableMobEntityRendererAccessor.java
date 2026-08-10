package cn.net.rms.confluxmap.mixin;

//#if MC>=12103
//$$ import net.minecraft.client.render.entity.AgeableMobEntityRenderer;
//$$ import net.minecraft.client.render.entity.model.EntityModel;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.gen.Accessor;
//#endif

/** Exposes the model pair that vanilla switches by {@code LivingEntityRenderState#baby}. */
//#if MC>=12103
//$$ @Mixin(AgeableMobEntityRenderer.class)
//$$ public interface AgeableMobEntityRendererAccessor {
//$$     @Accessor("adultModel")
//$$     EntityModel<?> confluxmap$getAdultModel();
//$$
//$$     @Accessor("babyModel")
//$$     EntityModel<?> confluxmap$getBabyModel();
//$$ }
//#else
public interface AgeableMobEntityRendererAccessor {
}
//#endif
