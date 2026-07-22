package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.mixin.VanillaLayeredBiomeSourceAccessor;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.TheEndBiomeSource;
import net.minecraft.world.biome.source.VanillaLayeredBiomeSource;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.DebugChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

/**
 * Classifies one dimension's live generator into a {@link WorldPreset}. Runs on whichever side
 * owns the {@link ServerWorld}: the integrated server in singleplayer ({@code
 * mc.predict.PredictionBootstrap}) and the companion on a dedicated server ({@code
 * ServerNetworking}, {@code RegionSummaryService}).
 *
 * <p>Classification is deliberately biased toward not regressing normal worlds: a vanilla
 * layered biome source is enough to call the layout predictable, and {@code matchesSettings}
 * (an identity check against the builtin registry) is used only to <em>positively</em> identify
 * Amplified - if it false-negatives on an exotic registry copy, the world degrades to
 * {@code DEFAULT}/{@code LARGE_BIOMES}, which matches pre-preset behavior. Anything without a
 * vanilla layered/End biome source (single-biome buffet, datapack or modded sources, the
 * Nether's multi-noise) is {@code CUSTOM} and therefore unpredictable.
 */
public final class WorldPresetDetector {
    private WorldPresetDetector() {
    }

    public static WorldPreset detect(final ServerWorld world) {
        final ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        if (generator instanceof FlatChunkGenerator) {
            return WorldPreset.FLAT;
        }
        if (generator instanceof DebugChunkGenerator) {
            return WorldPreset.DEBUG;
        }
        if (!(generator instanceof final NoiseChunkGenerator noise)) {
            return WorldPreset.CUSTOM;
        }
        final BiomeSource source = generator.getBiomeSource();
        if (source instanceof VanillaLayeredBiomeSource) {
            final boolean largeBiomes =
                ((VanillaLayeredBiomeSourceAccessor) (Object) source).confluxmap$isLargeBiomes();
            if (noise.matchesSettings(world.getSeed(), ChunkGeneratorSettings.AMPLIFIED)) {
                // Amplified + large biomes has no vanilla UI path and no cubiomes model.
                return largeBiomes ? WorldPreset.CUSTOM : WorldPreset.AMPLIFIED;
            }
            return largeBiomes ? WorldPreset.LARGE_BIOMES : WorldPreset.DEFAULT;
        }
        if (source instanceof TheEndBiomeSource) {
            return WorldPreset.DEFAULT;
        }
        return WorldPreset.CUSTOM;
    }
}
