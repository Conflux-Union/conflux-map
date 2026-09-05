import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/** Vanilla 1.17.1 base-column reference; see README.md. */
public class NetherFossilOracle117 {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registry = RegistryAccess.builtin();
        var biomes = registry.registryOrThrow(Registry.BIOME_REGISTRY);
        var settings = registry.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY)
            .getOrThrow(NoiseGeneratorSettings.NETHER);
        var height = new LevelHeightAccessor() {
            public int getHeight() { return 256; }
            public int getMinBuildHeight() { return 0; }
        };
        long seed = Long.parseLong(args[0]);
        var source = MultiNoiseBiomeSource.Preset.NETHER.biomeSource(biomes, seed);
        var generator = new NoiseBasedChunkGenerator(source, seed, () -> settings);
        for (int rz = -16; rz < 16; rz++) {
            for (int rx = -16; rx < 16; rx++) {
                int cx = rx * 2, cz = rz * 2;
                var random = new WorldgenRandom();
                random.setLargeFeatureSeed(seed, cx, cz);
                int x = cx * 16 + random.nextInt(16);
                int z = cz * 16 + random.nextInt(16);
                int start = 32 + random.nextInt(94);
                var column = generator.getBaseColumn(x, z, height);
                var bits = new StringBuilder();
                for (int y = 0; y < 128; y++) {
                    bits.append(column.getBlockState(new BlockPos(x, y, z)).isAir() ? '0' : '1');
                }
                boolean biome = source.getNoiseBiome(cx * 4 + 2, 0, cz * 4 + 2)
                    == biomes.getOrThrow(Biomes.SOUL_SAND_VALLEY);
                int y = start;
                var pos = new BlockPos.MutableBlockPos(x, y, z);
                // Mirror FeatureStart.generatePieces' landing loop; its template manager
                // is unnecessary for deciding whether a base-column landing exists.
                while (y > generator.getSeaLevel()) {
                    var above = column.getBlockState(pos);
                    pos.move(Direction.DOWN);
                    var below = column.getBlockState(pos);
                    if (above.isAir() && (below.is(Blocks.SOUL_SAND)
                        || below.isFaceSturdy(EmptyBlockGetter.INSTANCE, pos, Direction.UP))) {
                        break;
                    }
                    y--;
                }
                boolean valid = biome && y > generator.getSeaLevel();
                System.out.println("FOSSIL," + seed + "," + cx * 16 + "," + cz * 16
                    + "," + x + "," + z + "," + start + "," + (valid ? 1 : 0) + "," + bits);
            }
        }
    }
}
