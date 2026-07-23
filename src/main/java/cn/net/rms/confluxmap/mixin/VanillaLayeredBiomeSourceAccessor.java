package cn.net.rms.confluxmap.mixin;

//#if MC<11800
import net.minecraft.world.biome.source.VanillaLayeredBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
//#endif

/** Exposes the private large-biomes toggle for world-preset detection (no vanilla getter exists). */
//#if MC<11800
@Mixin(VanillaLayeredBiomeSource.class)
public interface VanillaLayeredBiomeSourceAccessor {
    @Accessor("largeBiomes")
    boolean confluxmap$isLargeBiomes();
}
//#else
//$$ public interface VanillaLayeredBiomeSourceAccessor {
//$$ }
//#endif
