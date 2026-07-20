package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.core.util.TileMath;
import net.minecraft.client.world.ClientWorld;

/**
 * Checks the client chunk manager instead of {@link ClientWorld#isChunkLoaded(int, int)}, whose
 * 1.17.1 implementation reports every coordinate as loaded.
 */
public final class ClientChunkLookup {
    private ClientChunkLookup() {
    }

    public static boolean isLoaded(final ClientWorld world, final int blockX, final int blockZ) {
        return isLoaded(blockX, blockZ, world.getChunkManager()::isChunkLoaded);
    }

    static boolean isLoaded(
        final int blockX,
        final int blockZ,
        final ChunkPresence chunks
    ) {
        return chunks.isLoaded(TileMath.blockToChunk(blockX), TileMath.blockToChunk(blockZ));
    }

    @FunctionalInterface
    interface ChunkPresence {
        boolean isLoaded(int chunkX, int chunkZ);
    }
}
