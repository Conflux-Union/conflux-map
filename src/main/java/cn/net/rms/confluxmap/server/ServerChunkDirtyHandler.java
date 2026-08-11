package cn.net.rms.confluxmap.server;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

/** Routes successful live block writes to the active server summary service. */
public final class ServerChunkDirtyHandler {
    private static volatile RegionSummaryService summaries;

    private ServerChunkDirtyHandler() {
    }

    static void bind(final RegionSummaryService service) {
        summaries = service;
    }

    public static void chunkDirty(final WorldChunk chunk) {
        final RegionSummaryService current = summaries;
        if (current == null || chunk == null) {
            return;
        }
        final World world = chunk.getWorld();
        if (world instanceof final ServerWorld serverWorld) {
            current.onChunkDirty(serverWorld, chunk);
        }
    }
}
