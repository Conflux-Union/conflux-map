package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory summaries for chunks whose authoritative state is the live {@code WorldChunk}. */
public final class LiveChunkSummaryCache {
    private final Map<Key, SummaryCodec.Chunk> chunks = new ConcurrentHashMap<>();

    public void put(
        final String dimension,
        final int chunkX,
        final int chunkZ,
        final SummaryCodec.Chunk summary
    ) {
        if (summary == null) {
            return;
        }
        chunks.compute(new Key(dimension, chunkX, chunkZ), (ignored, previous) -> revised(previous, summary));
    }

    public SummaryCodec.Chunk get(final String dimension, final int chunkX, final int chunkZ) {
        return chunks.get(new Key(dimension, chunkX, chunkZ));
    }

    public void remove(final String dimension, final int chunkX, final int chunkZ) {
        chunks.remove(new Key(dimension, chunkX, chunkZ));
    }

    public void clear() {
        chunks.clear();
    }

    /** Returns {@code region} with every currently-live chunk slot replaced by its live summary. */
    public SummaryCodec.Region overlay(final String dimension, final SummaryCodec.Region region) {
        final SummaryCodec.Chunk[] merged = region.chunks().clone();
        boolean changed = false;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                final SummaryCodec.Chunk live = get(
                    dimension,
                    region.rx() * 16 + localX,
                    region.rz() * 16 + localZ
                );
                if (live != null) {
                    merged[localZ * 16 + localX] = live;
                    changed = true;
                }
            }
        }
        return changed
            ? new SummaryCodec.Region(region.rx(), region.rz(), region.sourceMcaMtimeMs(), merged)
            : region;
    }

    private static SummaryCodec.Chunk revised(
        final SummaryCodec.Chunk previous,
        final SummaryCodec.Chunk candidate
    ) {
        if (previous == null) {
            return candidate;
        }
        if (previous.generated() == candidate.generated()
            && Arrays.equals(previous.columns(), candidate.columns())) {
            return previous;
        }
        final long nextRevision;
        if (candidate.revision() > previous.revision()) {
            nextRevision = candidate.revision();
        } else if (previous.revision() == Long.MAX_VALUE) {
            nextRevision = Long.MAX_VALUE;
        } else {
            nextRevision = previous.revision() + 1L;
        }
        return new SummaryCodec.Chunk(candidate.generated(), nextRevision, candidate.columns());
    }

    private record Key(String dimension, int chunkX, int chunkZ) {
        private Key {
            dimension = dimension == null ? "unknown" : dimension;
        }
    }
}
