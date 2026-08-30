package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.color.BiomeSampleWindow;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Client-only extraction of the stable biome resource-id plane for one captured chunk. */
final class BiomeIdentityCapture {
    /**
     * Vanilla subtracts two blocks before choosing between adjacent quart-biome cells. Keeping
     * samples two blocks off an unloaded side guarantees every candidate stays in this chunk.
     */
    static final int VORONOI_BORDER_INSET = 2;

    @FunctionalInterface
    interface Resolver {
        String biomeIdAt(int blockX, int blockY, int blockZ);
    }

    private BiomeIdentityCapture() {
    }

    static void capture(
        final ClientWorld world,
        final BlockPos.Mutable pos,
        final int baseX,
        final int baseZ,
        final short[] surfaceY,
        final String[] biomeId,
        final BiomeSampleWindow sampleWindow
    ) {
        capture(
            baseX,
            baseZ,
            surfaceY,
            biomeId,
            sampleWindow,
            (blockX, blockY, blockZ) -> {
                pos.set(blockX, blockY, blockZ);
                final Identifier id = Regs.biomeIdAt(world, pos);
                return id == null ? null : id.toString();
            }
        );
    }

    static void capture(
        final int baseX,
        final int baseZ,
        final short[] surfaceY,
        final String[] biomeId,
        final BiomeSampleWindow sampleWindow,
        final Resolver resolver
    ) {
        final Map<String, String> chunkPalette = new HashMap<>();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int index = z * 16 + x;
                if (surfaceY[index] == ChunkSnapshot.NO_SURFACE) {
                    continue;
                }
                final String id = resolver.biomeIdAt(
                    baseX + sampleWindow.clampLocalX(x),
                    surfaceY[index],
                    baseZ + sampleWindow.clampLocalZ(z)
                );
                if (id != null) {
                    biomeId[index] = chunkPalette.computeIfAbsent(id, ignored -> id);
                }
            }
        }
    }
}
