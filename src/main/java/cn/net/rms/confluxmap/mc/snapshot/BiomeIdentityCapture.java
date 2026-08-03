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
        final Map<String, String> chunkPalette = new HashMap<>();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
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
}
