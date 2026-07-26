package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import java.util.Optional;
//#if MC<260100
import net.minecraft.server.world.ChunkHolder;
//#else
//$$ import net.minecraft.server.level.ChunkHolder;
//#endif
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/** Version-adapted read access to the server's authoritative effective chunk ticket level. */
public final class ChunkLoadStateAccess {
    private ChunkLoadStateAccess() {
    }

    public record State(int level, ChunkLoadBand band) {
    }

    public static Optional<State> read(final ServerWorld world, final ChunkPos pos) {
        final ChunkHolder holder;
        //#if MC>=260100
        //$$ holder = world.getChunkSource().chunkMap.getVisibleChunkIfPresent(chunkLong(pos));
        //#elseif MC>=12100
        //$$ holder = world.getChunkManager().chunkLoadingManager.getChunkHolder(chunkLong(pos));
        //#else
        holder = world.getChunkManager().threadedAnvilChunkStorage.getChunkHolder(chunkLong(pos));
        //#endif
        if (holder == null) {
            return Optional.empty();
        }
        //#if MC>=260100
        //$$ final int level = holder.getTicketLevel();
        //#else
        final int level = holder.getLevel();
        //#endif
        final ChunkLoadBand band = ChunkLoadBand.fromTicketLevel(level);
        return band == ChunkLoadBand.UNLOADED
            ? Optional.empty()
            : Optional.of(new State(level, band));
    }

    private static long chunkLong(final ChunkPos pos) {
        //#if MC>=260100
        //$$ return pos.pack();
        //#else
        return pos.toLong();
        //#endif
    }
}
