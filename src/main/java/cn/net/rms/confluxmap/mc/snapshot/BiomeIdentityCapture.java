package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Client-only extraction of the stable biome resource-id plane for one captured chunk. */
final class BiomeIdentityCapture {
    private static final int SAMPLED_COORDINATES = 4;
    private static final int SAMPLED_COORDINATE_STEP = 5;

    private BiomeIdentityCapture() {
    }

    static void capture(
        final ClientWorld world,
        final BlockPos.Mutable pos,
        final int baseX,
        final int baseZ,
        final short[] surfaceY,
        final String[] biomeId
    ) {
        capture(world, pos, baseX, baseZ, surfaceY, biomeId, false);
    }

    static void capture(
        final ClientWorld world,
        final BlockPos.Mutable pos,
        final int baseX,
        final int baseZ,
        final short[] surfaceY,
        final String[] biomeId,
        final boolean sampled
    ) {
        final Map<String, String> chunkPalette = new HashMap<>();
        final int coordinateStep = sampled ? SAMPLED_COORDINATES : 16;
        for (int zIndex = 0; zIndex < coordinateStep; zIndex++) {
            final int z = coordinate(sampled, zIndex);
            for (int xIndex = 0; xIndex < coordinateStep; xIndex++) {
                final int x = coordinate(sampled, xIndex);
                final int index = z * 16 + x;
                if (surfaceY[index] == ChunkSnapshot.NO_SURFACE) {
                    continue;
                }
                pos.set(baseX + x, surfaceY[index], baseZ + z);
                final Identifier id = Regs.biomeIdAt(world, pos);
                if (id != null) {
                    final String value = id.toString();
                    biomeId[index] = chunkPalette.computeIfAbsent(value, ignored -> value);
                }
            }
        }
    }

    static int coordinate(final boolean sampled, final int index) {
        return sampled ? index * SAMPLED_COORDINATE_STEP : index;
    }
}
