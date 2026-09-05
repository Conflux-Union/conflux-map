import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.configurations.RangeConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import net.minecraft.world.level.levelgen.structure.NetherFossilFeature;
import net.minecraft.world.level.levelgen.structure.pieces.PieceGeneratorSupplier;

/** Calls vanilla 1.18.2's fossil piece-generator supplier; see README.md. */
public class NetherFossilOracle118 {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registry = RegistryAccess.builtinCopy();
        var biomes = registry.registryOrThrow(Registry.BIOME_REGISTRY);
        var settings = registry.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY)
            .getHolderOrThrow(NoiseGeneratorSettings.NETHER);
        var height = new LevelHeightAccessor() {
            public int getHeight() { return 256; }
            public int getMinBuildHeight() { return 0; }
        };
        long seed = Long.parseLong(args[0]);
        var source = MultiNoiseBiomeSource.Preset.NETHER.biomeSource(biomes);
        var generator = new NoiseBasedChunkGenerator(
            registry.registryOrThrow(Registry.STRUCTURE_SET_REGISTRY),
            registry.registryOrThrow(Registry.NOISE_REGISTRY), source, seed, settings
        );
        var config = new RangeConfiguration(
            UniformHeight.of(VerticalAnchor.absolute(32), VerticalAnchor.belowTop(2))
        );
        var method = NetherFossilFeature.class.getDeclaredMethod(
            "pieceGeneratorSupplier", PieceGeneratorSupplier.Context.class
        );
        method.setAccessible(true);
        for (int rz = -16; rz < 16; rz++) {
            for (int rx = -16; rx < 16; rx++) {
                int cx = rx * 2, cz = rz * 2;
                var random = new WorldgenRandom(new LegacyRandomSource(0));
                random.setLargeFeatureSeed(seed, cx, cz);
                int x = cx * 16 + random.nextInt(16);
                int z = cz * 16 + random.nextInt(16);
                int start = 32 + random.nextInt(94);
                var column = generator.getBaseColumn(x, z, height);
                var bits = new StringBuilder();
                for (int y = 0; y < 128; y++) {
                    bits.append(column.getBlock(y).isAir() ? '0' : '1');
                }
                var context = new PieceGeneratorSupplier.Context<>(generator, source, seed,
                    new ChunkPos(cx, cz), config, height, b -> b.is(Biomes.SOUL_SAND_VALLEY),
                    null, registry);
                boolean valid = ((Optional<?>) method.invoke(null, context)).isPresent();
                System.out.println("FOSSIL," + seed + "," + cx * 16 + "," + cz * 16
                    + "," + x + "," + z + "," + start + "," + (valid ? 1 : 0) + "," + bits);
            }
        }
    }
}
