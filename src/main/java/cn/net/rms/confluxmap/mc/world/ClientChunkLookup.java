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

    /**
     * Returns true only when every chunk in a saved 3x3 terrain fingerprint is already loaded
     * by the current client. This check intentionally precedes any fingerprint read, so identity
     * matching can never request historical chunks from the server.
     */
    public static boolean isSquareLoaded(final ClientWorld world, final int centerChunkX, final int centerChunkZ) {
        return isSquareLoaded(centerChunkX, centerChunkZ, world.getChunkManager()::isChunkLoaded);
    }

    static boolean isLoaded(
        final int blockX,
        final int blockZ,
        final ChunkPresence chunks
    ) {
        return chunks.isLoaded(TileMath.blockToChunk(blockX), TileMath.blockToChunk(blockZ));
    }

    static boolean isSquareLoaded(
        final int centerChunkX,
        final int centerChunkZ,
        final ChunkPresence chunks
    ) {
        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                if (!chunks.isLoaded(centerChunkX + offsetX, centerChunkZ + offsetZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    @FunctionalInterface
    interface ChunkPresence {
        boolean isLoaded(int chunkX, int chunkZ);
    }
}
