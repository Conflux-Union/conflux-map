package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;

/**
 * Derives a superflat dimension's uniform {@link FlatBaseline} from its live {@link
 * FlatChunkGenerator} config. The layer list is indexed by Y (1.17 flat worlds start at Y=0),
 * matching the {@code MOTION_BLOCKING - 1} surface the summarizer observes, so the derived
 * sample diffs cleanly against real chunk summaries. Runs on both the integrated server
 * (singleplayer bootstrap) and the companion (hello handshake, patch baseline).
 */
public final class FlatWorldBaseline {
    private static final ChunkSummarizer.MapColorResolver MAP_COLORS = new RegistryMapColors();

    private FlatWorldBaseline() {
    }

    /** Empty when the dimension is not superflat (callers should have checked the preset). */
    public static Optional<FlatBaseline> of(final ServerWorld world) {
        final ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        if (!(generator instanceof final FlatChunkGenerator flat)) {
            return Optional.empty();
        }
        final List<BlockState> layers = flat.getConfig().getLayerBlocks();
        //#if MC>=12100
        //$$ final int biomeId = biomeId(world, flat.getConfig().getBiome().value());
        //#else
        final int biomeId = biomeId(world, flat.getConfig().getBiome());
        //#endif

        int top = layers.size() - 1;
        while (top >= 0 && (layers.get(top) == null || layers.get(top).isAir())) {
            top--;
        }
        if (top < 0) {
            // "The void" preset: no surface at all; corrections alone paint anything built there.
            return Optional.of(new FlatBaseline(
                biomeId, 0, SurfaceKind.VOID.ordinal(), Proto.MAP_COLOR_NONE, 0
            ));
        }
        if (layers.get(top).isOf(Blocks.WATER)) {
            int floor = top;
            while (floor >= 0 && layers.get(floor) != null && layers.get(floor).isOf(Blocks.WATER)) {
                floor--;
            }
            return Optional.of(new FlatBaseline(
                biomeId, top, SurfaceKind.WATER.ordinal(), 12, Math.min(255, top - floor)
            ));
        }
        final String blockName = Regs.blocks().getId(layers.get(top).getBlock()).toString();
        final ChunkSummarizer.BlockInfo info = ChunkSummarizer.classify(blockName, MAP_COLORS);
        if (info.kind() == SurfaceKind.UNKNOWN) {
            return Optional.of(new FlatBaseline(
                biomeId, 0, SurfaceKind.VOID.ordinal(), Proto.MAP_COLOR_NONE, 0
            ));
        }
        return Optional.of(new FlatBaseline(biomeId, top, info.kind().ordinal(), info.mapColorId(), 0));
    }

    /**
     * The flat biome in the same id space the summarizer reports (cubiomes ids, which equal 1.17
     * raw registry ids for vanilla biomes); modded biomes fall back to their raw registry id so
     * the diff's biome equality still holds against observed chunk data.
     */
    private static int biomeId(final ServerWorld world, final Biome biome) {
        final var registry = Regs.biomes(world);
        final var id = registry.getId(biome);
        if (id != null) {
            final var mapped = CubiomesBiomeIds.idForName(id.getPath());
            if (mapped.isPresent()) {
                return mapped.getAsInt();
            }
        }
        return registry.getRawId(biome) & 255;
    }
}
