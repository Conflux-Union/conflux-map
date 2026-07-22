package cn.net.rms.confluxmap.mixin;

import net.minecraft.world.biome.source.VanillaLayeredBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the private large-biomes toggle for world-preset detection (no vanilla getter exists). */
@Mixin(VanillaLayeredBiomeSource.class)
public interface VanillaLayeredBiomeSourceAccessor {
    @Accessor("largeBiomes")
    boolean confluxmap$isLargeBiomes();
}
