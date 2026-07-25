package cn.net.rms.confluxmap.gametest.mixin;

import java.util.Collection;
//#if MC<12100
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ServerResourceManager;
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
//#else
//$$ import net.minecraft.registry.RegistryKey;
//$$ import net.minecraft.util.math.random.Random;
//$$ import net.minecraft.world.gen.WorldPreset;
//$$ import net.minecraft.world.gen.WorldPresets;
//$$ import org.objectweb.asm.Opcodes;
//#endif
import net.minecraft.test.TestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps the normal Overworld generator in the otherwise flat Vanilla GameTest server. */
@Mixin(TestServer.class)
abstract class TestServerMixin {
    //#if MC>=12100
    //$$ /**
    //$$  * Where the batch is placed, replacing the random draw below. Any tile the tests derive
    //$$  * from this must stay clear of {@code PredictionQualityCorpus}'s +-64 tile radius, whose
    //$$  * generated terrain the quality test reads and these tests would otherwise build on top of.
    //$$  */
    //$$ private static final int FIXED_TEST_POS_XZ = 1_048_576;
    //$$
    //$$ /**
    //$$  * Pins the batch position. Vanilla draws a random x/z from {@code world.random} across
    //$$  * {@code TEST_POS_XZ_RANGE} (+-14999992), so every run faced different generated terrain and
    //$$  * anything asserted about a predicted or generated surface was a lottery - the same code
    //$$  * passed one CI run and failed the next. The seed is already fixed; this fixes the place.
    //$$  *
    //$$  * <p>The redirect carries no {@code ordinal}, so it covers both the x and the z draw.
    //$$  * 1.17.1 needs no counterpart: its {@code runTestBatches} uses a constant {@code (0, 4, 0)}.
    //$$  */
    //$$ @Redirect(
    //$$     method = "runTestBatches",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/util/math/random/Random;nextBetween(II)I"
    //$$     )
    //$$ )
    //$$ private int useFixedTestPosition(final Random random, final int min, final int max) {
    //$$     return FIXED_TEST_POS_XZ;
    //$$ }
    //$$
    //$$ // The dimensions of the test world are built inside a synthetic lambda, which no mapping
    //$$ // names: yarn leaves the intermediary name in place, while an unobfuscated build ships
    //$$ // javac's own. The preprocessor can translate the descriptor but not the name, so each
    //$$ // side has to spell its own - in official names on 26.1, which carries no refMap.
    //#if MC>=260100
    //$$ @Redirect(
    //$$     method = "lambda$create$1("
    //$$         + "Lnet/minecraft/world/level/LevelSettings;"
    //$$         + "Lnet/minecraft/server/WorldLoader$DataLoadContext;"
    //$$         + ")Lnet/minecraft/server/WorldLoader$DataLoadOutput;",
    //$$     at = @At(
    //$$         value = "FIELD",
    //$$         target = "Lnet/minecraft/world/level/levelgen/presets/WorldPresets;FLAT:"
    //$$             + "Lnet/minecraft/resources/ResourceKey;",
    //$$         opcode = Opcodes.GETSTATIC
    //$$     )
    //$$ )
    //#else
    //$$ @Redirect(
    //$$     method = "method_40377",
    //$$     at = @At(
    //$$         value = "FIELD",
    //$$         target = "Lnet/minecraft/world/gen/WorldPresets;FLAT:"
    //$$             + "Lnet/minecraft/registry/RegistryKey;",
    //$$         opcode = Opcodes.GETSTATIC
    //$$     )
    //$$ )
    //#endif
    //$$ private static RegistryKey<WorldPreset> useNormalOverworldPreset() {
    //$$     return WorldPresets.DEFAULT;
    //$$ }
    //#else
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
    //#endif
}
