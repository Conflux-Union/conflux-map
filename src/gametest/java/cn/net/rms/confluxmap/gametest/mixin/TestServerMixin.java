package cn.net.rms.confluxmap.gametest.mixin;

import java.util.Collection;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ServerResourceManager;
import net.minecraft.test.TestServer;
import net.minecraft.test.GameTestBatch;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.SimpleRegistry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps the normal Overworld generator in the otherwise flat Vanilla GameTest server. */
@Mixin(TestServer.class)
abstract class TestServerMixin {
    @Redirect(
        method = "<init>(Ljava/lang/Thread;"
            + "Lnet/minecraft/world/level/storage/LevelStorage$Session;"
            + "Lnet/minecraft/resource/ResourcePackManager;"
            + "Lnet/minecraft/resource/ServerResourceManager;"
            + "Ljava/util/Collection;"
            + "Lnet/minecraft/util/math/BlockPos;"
            + "Lnet/minecraft/util/registry/DynamicRegistryManager$Impl;"
            + "Lnet/minecraft/util/registry/Registry;"
            + "Lnet/minecraft/util/registry/Registry;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/gen/GeneratorOptions;getRegistryWithReplacedOverworldGenerator("
                + "Lnet/minecraft/util/registry/Registry;"
                + "Lnet/minecraft/util/registry/SimpleRegistry;"
                + "Lnet/minecraft/world/gen/chunk/ChunkGenerator;"
                + ")Lnet/minecraft/util/registry/SimpleRegistry;"
        )
    )
    private static SimpleRegistry<DimensionOptions> useNormalOverworld(
        final Registry<DimensionType> dimensionTypes,
        final SimpleRegistry<DimensionOptions> defaultDimensions,
        final ChunkGenerator ignoredFlatGenerator,
        final Thread serverThread,
        final LevelStorage.Session session,
        final ResourcePackManager resourcePackManager,
        final ServerResourceManager serverResourceManager,
        final Collection<GameTestBatch> batches,
        final BlockPos testPosition,
        final DynamicRegistryManager.Impl registryManager,
        final Registry<Biome> biomeRegistry,
        final Registry<DimensionType> constructorDimensionTypes
    ) {
        final NoiseChunkGenerator normalGenerator = GeneratorOptions.createOverworldGenerator(
            biomeRegistry,
            registryManager.get(Registry.CHUNK_GENERATOR_SETTINGS_KEY),
            0L
        );
        return GeneratorOptions.getRegistryWithReplacedOverworldGenerator(
            dimensionTypes,
            defaultDimensions,
            normalGenerator
        );
    }
}
