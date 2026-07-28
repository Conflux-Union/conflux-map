package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.util.function.LongSupplier;

/** Region-major bounded scanner feeding one coarse correction summary grid. */
final class ProgressivePatchTask {
    @FunctionalInterface
    interface ChunkSource {
        SummaryCodec.SampledChunk load(int chunkX, int chunkZ);
    }

    private final int lod;
    private final int tileX;
    private final int tileZ;
    private final int regionsPerSide;
    private final int totalChunks;
    private final ProgressiveSummaryGrid grid;
    private int processedChunks;

    ProgressivePatchTask(final int lod, final int tileX, final int tileZ) {
        this.lod = lod;
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.regionsPerSide = 1 << lod;
        this.totalChunks = regionsPerSide * regionsPerSide * SummaryCodec.CHUNKS;
        this.grid = new ProgressiveSummaryGrid(lod, tileX, tileZ);
    }

    int advance(
        final ChunkSource source,
        final int maxChunks,
        final long maxNanos,
        final LongSupplier nanoClock
    ) {
        if (maxChunks <= 0 || maxNanos <= 0L || complete()) {
            return 0;
        }
        final long started = nanoClock.getAsLong();
        int advanced = 0;
        while (advanced < maxChunks && !complete()) {
            final int regionIndex = processedChunks / SummaryCodec.CHUNKS;
            final int chunkIndex = processedChunks % SummaryCodec.CHUNKS;
            final int regionX = tileX * regionsPerSide + regionIndex % regionsPerSide;
            final int regionZ = tileZ * regionsPerSide + regionIndex / regionsPerSide;
            final int chunkX = regionX * 16 + chunkIndex % 16;
            final int chunkZ = regionZ * 16 + chunkIndex / 16;
            final SummaryCodec.SampledChunk chunk = source.load(chunkX, chunkZ);
            grid.acceptChunk(
                chunkX,
                chunkZ,
                chunk == null ? SummaryCodec.SampledChunk.empty(1 << lod) : chunk
            );
            processedChunks++;
            advanced++;
            if (nanoClock.getAsLong() - started >= maxNanos) {
                break;
            }
        }
        return advanced;
    }

    int processedChunks() {
        return processedChunks;
    }

    boolean complete() {
        return processedChunks >= totalChunks;
    }

    SummaryView snapshot() {
        return grid.snapshot(complete());
    }
}
