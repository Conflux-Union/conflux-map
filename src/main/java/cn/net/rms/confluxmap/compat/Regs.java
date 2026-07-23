package cn.net.rms.confluxmap.compat;

import net.minecraft.block.Block;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

//#if MC>=12100
//$$ import net.minecraft.registry.Registries;
//$$ import net.minecraft.registry.Registry;
//$$ import net.minecraft.registry.RegistryKey;
//$$ import net.minecraft.registry.RegistryKeys;
//#else
import net.minecraft.util.registry.Registry;
//#endif

import java.util.Optional;

/**
 * The one place that knows how this Minecraft version exposes registries.
 *
 * <p>Three separate breaks are folded in here: 1.19.3 moved the registry classes out of
 * {@code net.minecraft.util.registry} and split the static keys into {@code Registries} /
 * {@code RegistryKeys}, 1.21.3 replaced {@code DynamicRegistryManager.get} with
 * {@code getOrThrow}, and 1.21.5 replaced {@code Registry.getOrEmpty} with
 * {@code getOptionalValue}. Since 1.18 a world also hands out a {@code RegistryEntry<Biome>}
 * rather than a bare {@code Biome}, so biome lookups go through {@link #biomeIdAt} instead of
 * leaking that type difference into callers.
 */
public final class Regs {
    private Regs() {
    }

    /** The biome registry backing {@code world}. */
    public static Registry<Biome> biomes(final World world) {
        //#if MC>=12103
        //$$ return world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
        //#elseif MC>=12100
        //$$ return world.getRegistryManager().get(RegistryKeys.BIOME);
        //#else
        return world.getRegistryManager().get(Registry.BIOME_KEY);
        //#endif
    }

    /** The static block registry. */
    public static Registry<Block> blocks() {
        //#if MC>=12100
        //$$ return Registries.BLOCK;
        //#else
        return Registry.BLOCK;
        //#endif
    }

    /**
     * Looks a block up by identifier. Deliberately not {@code blocks().get(id)}: the block
     * registry is defaulted, so an unknown id silently resolves to air instead of reporting
     * absence, which would turn "unknown block" into "block with the air map colour".
     */
    public static Optional<Block> block(final Identifier id) {
        //#if MC>=12105
        //$$ return blocks().getOptionalValue(id);
        //#else
        return blocks().getOrEmpty(id);
        //#endif
    }

    /** The registry identifier of the biome at {@code pos}, or null when it has none. */
    public static Identifier biomeIdAt(final World world, final BlockPos pos) {
        //#if MC>=12100
        //$$ return world.getBiome(pos).getKey().map(RegistryKey::getValue).orElse(null);
        //#else
        return biomes(world).getId(world.getBiome(pos));
        //#endif
    }
}
