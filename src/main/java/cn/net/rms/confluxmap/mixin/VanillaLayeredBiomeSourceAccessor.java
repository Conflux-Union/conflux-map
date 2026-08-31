package cn.net.rms.confluxmap.mixin;

//#if MC<11800
import net.minecraft.world.biome.source.VanillaLayeredBiomeSource;
import org.spongepowered.asm.mixin.gen.Accessor;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Exposes the private large-biomes toggle for world-preset detection (no vanilla getter exists). */
//#if MC<11800
@Mixin(VanillaLayeredBiomeSource.class)
//#else
//$$ @Pseudo
//$$ @Mixin(targets = "net.minecraft.world.biome.source.VanillaLayeredBiomeSource")
//#endif
public interface VanillaLayeredBiomeSourceAccessor {
    //#if MC<11800
    @Accessor("largeBiomes")
    boolean confluxmap$isLargeBiomes();
    //#endif
}
