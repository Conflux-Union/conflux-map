import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Calls vanilla 1.21.1/26.2 fossil generation-point validation; see README.md. */
public class NetherFossilOracleModern {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = VanillaRegistries.createLookup();
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.NETHER);
        var source = MultiNoiseBiomeSource.createFromPreset(
            registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)
        );
        var generator = new NoiseBasedChunkGenerator(source, settings);
        var height = LevelHeightAccessor.create(0, 256);
        long seed = Long.parseLong(args[0]);
        var state = RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), seed);
        var fossil = registries.lookupOrThrow(Registries.STRUCTURE)
            .getOrThrow(BuiltinStructures.NETHER_FOSSIL).value();
        for (int rz = -16; rz < 16; rz++) {
            for (int rx = -16; rx < 16; rx++) {
                int cx = rx * 2, cz = rz * 2;
                // Builtin registries have no bound datapack tags. Use the vanilla
                // fossil tag's only member, verified in the jar's biome tag JSON.
                var context = new Structure.GenerationContext(null, generator, source, state,
                    null, seed, new ChunkPos(cx, cz), height, b -> b.is(Biomes.SOUL_SAND_VALLEY));
                var result = fossil.findValidGenerationPoint(context);
                var random = new WorldgenRandom(new LegacyRandomSource(0));
                random.setLargeFeatureSeed(seed, cx, cz);
                int x = cx * 16 + random.nextInt(16);
                int z = cz * 16 + random.nextInt(16);
                int start = 32 + random.nextInt(94);
                var column = generator.getBaseColumn(x, z, height, state);
                var bits = new StringBuilder();
                for (int y = 0; y < 128; y++) {
                    bits.append(column.getBlock(y).isAir() ? '0' : '1');
                }
                System.out.println("FOSSIL," + seed + "," + cx * 16 + "," + cz * 16
                    + "," + x + "," + z + "," + start + "," + (result.isPresent() ? 1 : 0)
                    + "," + bits);
            }
        }
    }
}
